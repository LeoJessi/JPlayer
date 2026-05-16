package top.jessi.videoplayer.util;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * HLS 本地 HTTP 代理
 * <p>
 * 解决特殊 HLS 流的兼容性问题：
 * <ul>
 *   <li>服务器对 TS 片段使用 .html 后缀，返回 Content-Type: text/html</li>
 * </ul>
 * <p>
 * 作为公共工具类，可复用于任意播放内核（VLC、IjkPlayer 等）。
 * 使用时在播放器的 setDataSource() 中启动代理，将原始 URL 替换为代理 URL 即可。
 * <p>
 * 策略：
 * 1. m3u8 请求：将片段 URL 改写为经过代理的绝对 URL（保持原始后缀），
 *    确保后续片段请求也经过代理
 * 2. 片段请求（.html/.htm）：修正 Content-Type 为 video/MP2T，流式转发
 * 3. 其他请求：直接透传
 */
public class HlsProxy {

    private static final String TAG = "JPlayer—HlsProxy";

    private ServerSocket mServerSocket;
    private ExecutorService mExecutor;
    private int mPort = -1;
    private volatile boolean mRunning = false;

    public synchronized int start() {
        if (mRunning) return mPort;
        try {
            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            mPort = mServerSocket.getLocalPort();
            mRunning = true;
            mExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "HlsProxy-W");
                t.setDaemon(true);
                return t;
            });
            Thread t = new Thread(this::acceptLoop, "HlsProxy-Acc");
            t.setDaemon(true);
            t.start();
            L.d( "HLS proxy started on 127.0.0.1:" + mPort);
            return mPort;
        } catch (IOException e) {
            Log.w(TAG, "Failed to start HLS proxy", e);
            mPort = -1;
            return -1;
        }
    }

    public synchronized void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try { mServerSocket.close(); } catch (IOException ignored) {}
            mServerSocket = null;
        }
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        mPort = -1;
        L.d("HLS proxy stopped");
    }

    public String getProxyUrl(String originalUrl) {
        if (mPort <= 0) {
            int port = start();
            if (port <= 0) return originalUrl;
        }
        return "http://127.0.0.1:" + mPort + "/?url=" + android.net.Uri.encode(originalUrl);
    }

    private String getProxyPrefix() {
        return "http://127.0.0.1:" + mPort + "/?url=";
    }

    // ==================== 网络层 ====================

    private void acceptLoop() {
        while (mRunning) {
            try {
                Socket client = mServerSocket.accept();
                mExecutor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (mRunning) Log.w(TAG, "Accept error", e);
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();

            String requestLine = readLine(is);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(os, 400, "Bad Request");
                client.close();
                return;
            }

            String targetUrl = extractTargetUrl(parts[1]);
            if (targetUrl == null || targetUrl.isEmpty()) {
                sendError(os, 400, "Missing target URL");
                client.close();
                return;
            }

            // 读取请求头
            Map<String, String> reqHeaders = new HashMap<>();
            String hdr;
            while ((hdr = readLine(is)) != null && !hdr.isEmpty()) {
                int colon = hdr.indexOf(':');
                if (colon > 0) {
                    reqHeaders.put(
                            hdr.substring(0, colon).trim().toLowerCase(Locale.US),
                            hdr.substring(colon + 1).trim());
                }
            }

            proxyRequest(targetUrl, reqHeaders, os);
            client.close();
        } catch (Exception e) {
            Log.w(TAG, "Client handler error", e);
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private String extractTargetUrl(String path) {
        int idx = path.indexOf("?url=");
        if (idx >= 0) {
            return android.net.Uri.decode(path.substring(idx + 5));
        }
        if (path.startsWith("/?http")) {
            return path.substring(2);
        }
        if (path.startsWith("/proxy/")) {
            return android.net.Uri.decode(path.substring(7));
        }
        return null;
    }

    // ==================== 代理请求处理 ====================

    private void proxyRequest(String targetUrl, Map<String, String> reqHeaders,
                              OutputStream clientOs) {
        HttpURLConnection conn = null;
        try {
            if (targetUrl.startsWith("https://")) {
                initHttpsTrust();
            }

            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                String k = e.getKey();
                if (!"host".equals(k) && !"connection".equals(k)) {
                    conn.setRequestProperty(k, e.getValue());
                }
            }

            conn.connect();

            int code = conn.getResponseCode();
            String contentType = conn.getContentType();
            boolean isM3u8 = isM3u8Content(targetUrl, contentType);
            boolean isTsSegment = !isM3u8 && isTsSegmentRequest(targetUrl, contentType);

            InputStream bodyStream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();

            if (isM3u8) {
                // m3u8：读取 → 改写片段 URL → 发送
                String body = readAllText(bodyStream);
                body = rewriteM3u8(body, targetUrl);
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                sendHeaders(clientOs, code, conn, bodyBytes.length, false);
                clientOs.write(bodyBytes);
                clientOs.flush();

            } else if (isTsSegment && bodyStream != null) {
                // TS 片段：流式转发，修正 Content-Type
                sendHeaders(clientOs, code, conn, -1, true);
                byte[] buf = new byte[8192];
                int len;
                while ((len = bodyStream.read(buf)) != -1) {
                    clientOs.write(buf, 0, len);
                    clientOs.flush();
                }
            } else {
                // 其他：直接透传
                sendHeaders(clientOs, code, conn, -1, false);
                if (bodyStream != null) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = bodyStream.read(buf)) != -1) {
                        clientOs.write(buf, 0, len);
                    }
                    clientOs.flush();
                }
            }

        } catch (java.net.SocketException e) {
            // 客户端（VLC）主动断开连接，正常现象（如退出播放），无需打印堆栈
            L.d( "Proxy client disconnected");
        } catch (Exception e) {
            Log.w(TAG, "Proxy failed", e);
            try { sendError(clientOs, 502, "Bad Gateway"); } catch (IOException ignored) {}
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== m3u8 重写 ====================

    /**
     * 重写 m3u8：将片段 URL 改为经过代理的绝对 URL（保持 .html 后缀不变）
     */
    String rewriteM3u8(String content, String m3u8Url) {
        if (content == null || content.isEmpty()) return content;

        String proxyPrefix = getProxyPrefix();
        StringBuilder out = new StringBuilder(content.length());
        String[] lines = content.split("\n", -1);
        boolean changed = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line);
            } else {
                // 片段行：相对路径 → 绝对 URL → 代理 URL
                String segmentUrl = trimmed;

                if (!isAbsoluteUrl(segmentUrl)) {
                    try {
                        segmentUrl = new URL(new URL(m3u8Url), segmentUrl).toString();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to resolve: " + trimmed);
                    }
                }

                if (isAbsoluteUrl(segmentUrl)) {
                    String proxyUrl = proxyPrefix + android.net.Uri.encode(segmentUrl);
                    if (!proxyUrl.equals(trimmed)) changed = true;
                    out.append(proxyUrl);
                } else {
                    out.append(line);
                }
            }

            if (i < lines.length - 1) out.append('\n');
        }

        if (changed) {
            L.d( "M3U8 rewritten: segment URLs → proxy URLs");
        }
        return out.toString();
    }

    private boolean isAbsoluteUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    // ==================== HTTP 响应头 ====================

    private void sendHeaders(OutputStream os, int code, HttpURLConnection conn,
                             int forcedContentLength, boolean fixContentType) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(" ").append(statusText(code)).append("\r\n");

        Map<String, List<String>> respHeaders = conn.getHeaderFields();
        if (respHeaders != null) {
            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                if (key.equalsIgnoreCase("transfer-encoding")) continue;
                if (key.equalsIgnoreCase("content-encoding")) continue;
                if (key.equalsIgnoreCase("content-length")) continue;
                if (fixContentType && key.equalsIgnoreCase("content-type")) continue;
                for (String v : e.getValue()) {
                    h.append(key).append(": ").append(v).append("\r\n");
                }
            }
        }

        if (fixContentType) {
            h.append("Content-Type: video/MP2T\r\n");
        }

        if (forcedContentLength >= 0) {
            h.append("Content-Length: ").append(forcedContentLength).append("\r\n");
        }

        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("ISO-8859-1"));
    }

    // ==================== 内容类型判断 ====================

    private boolean isM3u8Content(String url, String contentType) {
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.US);
            if (ct.contains("mpegurl") || ct.contains("m3u8") || ct.contains("x-mpegurl")) {
                return true;
            }
        }
        String u = url.toLowerCase(Locale.US);
        return u.contains(".m3u8") || u.contains(".m3u");
    }

    /**
     * 判断是否是 TS 片段请求（.html/.htm 后缀 + text/html Content-Type）
     */
    private boolean isTsSegmentRequest(String url, String contentType) {
        String u = url.toLowerCase(Locale.US);
        boolean hasHtmlExt = u.contains(".html") || u.contains(".htm");
        if (!hasHtmlExt) return false;
        if (contentType != null) {
            return contentType.toLowerCase(Locale.US).contains("text/html");
        }
        return true;
    }

    // ==================== 工具方法 ====================

    private String readAllText(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder(4096);
        char[] buf = new char[4096];
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, "UTF-8"));
        int len;
        while ((len = r.read(buf)) != -1) {
            sb.append(buf, 0, len);
        }
        return sb.toString();
    }

    private String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int ch;
        while ((ch = is.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
        }
        return (sb.length() == 0 && ch == -1) ? null : sb.toString();
    }

    private void sendError(OutputStream os, int code, String text) throws IOException {
        String body = code + " " + text;
        String resp = "HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n" + body;
        os.write(resp.getBytes("ISO-8859-1"));
        os.flush();
    }

    private String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Unknown";
        }
    }

    private static volatile boolean sHttpsReady = false;

    private static void initHttpsTrust() {
        if (sHttpsReady) return;
        synchronized (HlsProxy.class) {
            if (sHttpsReady) return;
            try {
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                            public void checkClientTrusted(
                                    java.security.cert.X509Certificate[] c, String a) {}
                            public void checkServerTrusted(
                                    java.security.cert.X509Certificate[] c, String a) {}
                        }
                }, new java.security.SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
                sHttpsReady = true;
            } catch (Exception e) {
                Log.w(TAG, "HTTPS init failed", e);
            }
        }
    }
}
