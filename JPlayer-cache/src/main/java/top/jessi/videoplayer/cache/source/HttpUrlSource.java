package top.jessi.videoplayer.cache.source;

import android.text.TextUtils;

import top.jessi.videoplayer.cache.headers.EmptyHeadersInjector;
import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorageFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;
import static top.jessi.videoplayer.cache.internal.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import top.jessi.videoplayer.cache.internal.ProxyCacheUtils;
import top.jessi.videoplayer.cache.internal.Logger;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfo;
import top.jessi.videoplayer.cache.ProxyCacheException;
import top.jessi.videoplayer.cache.InterruptedProxyCacheException;

/**
 * {@link Source} that uses http resource as source for {@code ProxyCache}.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class HttpUrlSource implements Source {

    private static final int MAX_REDIRECTS = 5;
    private static final int HTTP_TEMP_REDIRECT = 307;
    private static final int HTTP_PERM_REDIRECT = 308;
    static final long DEFAULT_TIMEOUT_MS = 10_000L;
    private final SourceInfoStorage sourceInfoStorage;
    private final HeaderInjector headerInjector;
    private final HostnameVerifier hostnameVerifier;
    private final TrustManager[] trustAllCerts;
    private final long timeoutMs;
    private SourceInfo sourceInfo;
    private volatile boolean contentInfoFetched;
    private HttpURLConnection connection;
    private InputStream inputStream;

    public HttpUrlSource(String url) {
        this(url, SourceInfoStorageFactory.newEmptySourceInfoStorage());
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage) {
        this(url, sourceInfoStorage, new EmptyHeadersInjector());
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        this(url, sourceInfoStorage, headerInjector, null, null);
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector,
                         HostnameVerifier hostnameVerifier, TrustManager[] trustAllCerts) {
        this(url, sourceInfoStorage, headerInjector, hostnameVerifier, trustAllCerts, DEFAULT_TIMEOUT_MS);
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector,
                         HostnameVerifier hostnameVerifier, TrustManager[] trustAllCerts, long timeoutMs) {
        this.sourceInfoStorage = checkNotNull(sourceInfoStorage);
        this.headerInjector = checkNotNull(headerInjector);
        this.hostnameVerifier = hostnameVerifier;
        this.trustAllCerts = trustAllCerts;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        SourceInfo sourceInfo = sourceInfoStorage.get(url);
        this.sourceInfo = sourceInfo != null ? sourceInfo :
                new SourceInfo(url, Integer.MIN_VALUE, ProxyCacheUtils.getSupposablyMime(url));
    }

    public HttpUrlSource(HttpUrlSource source) {
        this.sourceInfo = source.sourceInfo;
        this.sourceInfoStorage = source.sourceInfoStorage;
        this.headerInjector = source.headerInjector;
        this.hostnameVerifier = source.hostnameVerifier;
        this.trustAllCerts = source.trustAllCerts;
        this.timeoutMs = source.timeoutMs;
        this.contentInfoFetched = source.contentInfoFetched;
    }

    @Override
    public synchronized long length() throws ProxyCacheException {
        if (sourceInfo.length == Integer.MIN_VALUE && !contentInfoFetched) {
            fetchContentInfo();
            contentInfoFetched = true;
        }
        return sourceInfo.length;
    }

    /**
     * 直接获取 sourceInfo，不触发网络请求。
     * 用于缓存优化：当有足够缓存时，直接用数据库元数据，避免连源站。
     */
    public SourceInfo getSourceInfoDirect() {
        return sourceInfo;
    }

    @Override
    public void open(long offset) throws ProxyCacheException {
        try {
            connection = openConnection(offset, timeoutMs);
            int responseCode = connection.getResponseCode();
            if (responseCode < HTTP_OK || responseCode >= 300) {
                throw new ProxyCacheException("Error opening connection for " + sourceInfo.url
                        + " with offset " + offset + ": unexpected response code " + responseCode);
            }
            if (offset > 0 && responseCode == HTTP_OK) {
                // 请求了 Range 但服务端返回 200（忽略 Range），继续读取会导致缓存数据错位
                throw new ProxyCacheException("Error opening connection for " + sourceInfo.url
                        + " with offset " + offset + ": server ignored Range request (responded 200)");
            }
            String mime = connection.getContentType();
            inputStream = new BufferedInputStream(connection.getInputStream(), DEFAULT_BUFFER_SIZE);
            long length = readSourceAvailableBytes(connection, offset, responseCode);
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.contentInfoFetched = true;
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening connection for " + sourceInfo.url + " with offset " + offset, e);
        }
    }

    private long readSourceAvailableBytes(HttpURLConnection connection, long offset, int responseCode) throws IOException {
        long contentLength = getContentLength(connection);
        return responseCode == HTTP_OK ? contentLength
                : responseCode == HTTP_PARTIAL ? contentLength + offset : sourceInfo.length;
    }

    private long getContentLength(HttpURLConnection connection) {
        String contentLengthValue = connection.getHeaderField("Content-Length");
        if (contentLengthValue == null || contentLengthValue.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(contentLengthValue);
        } catch (NumberFormatException e) {
            Logger.error("Error parsing Content-Length '" + contentLengthValue + "' for " + sourceInfo.url);
            return -1;
        }
    }

    @Override
    public void close() throws ProxyCacheException {
        ProxyCacheUtils.close(inputStream);
        inputStream = null;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (NullPointerException | IllegalArgumentException e) {
                Logger.error("Error closing connection for " + sourceInfo.url);
            } catch (ArrayIndexOutOfBoundsException e) {
                Logger.error("Error closing connection correctly. Should happen only on Android L. " +
                        "If anybody know how to fix it, please visit https://github.com/danikula/AndroidVideoCache/issues/88. " +
                        "Until good solution is not know, just ignore this issue.");
            }
        }
    }

    @Override
    public int read(byte[] buffer) throws ProxyCacheException {
        if (inputStream == null) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url + ": connection is absent!");
        }
        try {
            return inputStream.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException e) {
            throw new InterruptedProxyCacheException("Reading source " + sourceInfo.url + " is interrupted", e);
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url, e);
        }
    }

    private void fetchContentInfo() throws ProxyCacheException {
        Logger.debug("Read content info from " + sourceInfo.url);
        HttpURLConnection urlConnection = null;
        InputStream inputStream = null;
        try {
            urlConnection = openConnection(0, timeoutMs);
            long length = getContentLength(urlConnection);
            String mime = urlConnection.getContentType();
            inputStream = urlConnection.getInputStream();
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
            Logger.debug("Source info fetched: " + sourceInfo);
        } catch (IOException e) {
            Logger.error("Error fetching info from " + sourceInfo.url);
        } finally {
            ProxyCacheUtils.close(inputStream);
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(long offset, long timeout) throws IOException, ProxyCacheException {
        HttpURLConnection connection;
        boolean redirected;
        int redirectCount = 0;
        String url = this.sourceInfo.url;
        do {
            Logger.debug("Open connection " + (offset > 0 ? " with offset " + offset : "") + " to " + url);
            connection = (HttpURLConnection) new URL(url).openConnection();
            // 禁用 HttpURLConnection 的自动重定向，由下方手动循环统一处理，
            // 避免自动重定向丢弃 Range 头导致缓存数据错位。
            connection.setInstanceFollowRedirects(false);
            applyHttpsConfiguration(connection);
            injectCustomHeaders(connection, url);
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
            }
            if (timeout > 0) {
                int t = (int) Math.min(timeout, Integer.MAX_VALUE);
                connection.setConnectTimeout(t);
                connection.setReadTimeout(t);
            }
            int code = connection.getResponseCode();
            redirected = code == HTTP_MOVED_PERM || code == HTTP_MOVED_TEMP || code == HTTP_SEE_OTHER
                    || code == HTTP_TEMP_REDIRECT || code == HTTP_PERM_REDIRECT;
            if (redirected) {
                String location = connection.getHeaderField("Location");
                if (location == null) {
                    throw new ProxyCacheException("Redirect without Location header for " + sourceInfo.url);
                }
                // 兼容相对路径（如 /v2/video.mp4）：自动跟随时由框架解析，手动处理需自行解析
                url = new URL(connection.getURL(), location).toString();
                redirectCount++;
                connection.disconnect();
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (redirected);
        return connection;
    }

    private void applyHttpsConfiguration(HttpURLConnection connection) {
        if (!(connection instanceof HttpsURLConnection)) {
            return;
        }
        HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
        if (hostnameVerifier != null) {
            httpsConnection.setHostnameVerifier(hostnameVerifier);
        }
        if (trustAllCerts != null) {
            SSLSocketFactory socketFactory = createTrustAllSocketFactory(trustAllCerts);
            if (socketFactory != null) {
                httpsConnection.setSSLSocketFactory(socketFactory);
            }
        }
    }

    private static SSLSocketFactory createTrustAllSocketFactory(TrustManager[] trustAllCerts) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Logger.error("Error creating SSL socket factory: " + e.getMessage());
            return null;
        }
    }

    private void injectCustomHeaders(HttpURLConnection connection, String url) {
        Map<String, String> extraHeaders = headerInjector.addHeaders(url);
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    @Override
    public synchronized String getMime() throws ProxyCacheException {
        if (TextUtils.isEmpty(sourceInfo.mime) && !contentInfoFetched) {
            fetchContentInfo();
            contentInfoFetched = true;
        }
        return sourceInfo.mime;
    }

    @Override
    public String getUrl() {
        return sourceInfo.url;
    }

    @Override
    public String toString() {
        return "HttpUrlSource{sourceInfo='" + sourceInfo + "}";
    }
}
