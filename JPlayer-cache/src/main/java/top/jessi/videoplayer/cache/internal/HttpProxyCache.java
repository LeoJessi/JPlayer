package top.jessi.videoplayer.cache.internal;

import android.text.TextUtils;

import top.jessi.videoplayer.cache.file.FileCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static top.jessi.videoplayer.cache.internal.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import top.jessi.videoplayer.cache.source.Source;
import top.jessi.videoplayer.cache.source.HttpUrlSource;
import top.jessi.videoplayer.cache.source.SourceFactory;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfo;
import top.jessi.videoplayer.cache.CacheListener;
import top.jessi.videoplayer.cache.ProxyCacheException;

/**
 * {@link ProxyCache} that read http url and writes data to {@link Socket}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class HttpProxyCache extends ProxyCache {

    private static final float NO_CACHE_BARRIER = .2f;

    private final Source source;
    private final SourceFactory sourceFactory;
    private final FileCache cache;
    private CacheListener listener;

    public HttpProxyCache(Source source, FileCache cache, SourceFactory sourceFactory) {
        super(source, cache);
        this.cache = cache;
        this.source = source;
        this.sourceFactory = sourceFactory;
    }

    public void registerCacheListener(CacheListener cacheListener) {
        this.listener = cacheListener;
    }

    public void processRequest(GetRequest request, Socket socket) throws IOException, ProxyCacheException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String responseHeaders = newResponseHeaders(request);
        out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));

        long offset = request.rangeOffset;
        if (isUseCache(request)) {
            responseWithCache(out, offset);
        } else {
            responseWithoutCache(out, offset);
        }
    }

    private boolean isUseCache(GetRequest request) throws ProxyCacheException {
        long cacheAvailable = cache.available();
        // 优化：如果有足够缓存（1MB+），假定元数据已知，优先使用缓存
        if (cacheAvailable >= 1024 * 1024) {
            return true;
        }

        long sourceLength = source.length();
        boolean sourceLengthKnown = sourceLength > 0;
        // do not use cache for partial requests which too far from available cache. It seems user seek video.
        return !sourceLengthKnown || !request.partial || request.rangeOffset <= cacheAvailable + sourceLength * NO_CACHE_BARRIER;
    }

    private String newResponseHeaders(GetRequest request) throws IOException, ProxyCacheException {
        long cacheAvailable = cache.available();
        boolean cacheCompleted = cache.isCompleted();

        // 优化：如果缓存足够（完整或 ≥ 1MB），优先使用数据库元数据，避免调用 source 方法
        if ((cacheCompleted || cacheAvailable >= 1024 * 1024) && source instanceof HttpUrlSource) {
            SourceInfo info = ((HttpUrlSource) source).getSourceInfoDirect();
            if (info != null && info.length > 0) {
                // 数据库有完整元数据，直接使用，完全不调用 source.getMime() 或 source.length()
                long length = cacheCompleted ? cacheAvailable : info.length;
                return buildResponseHeaders(request, info.mime, length);
            }
        }

        // 正常流程：调用 source 获取元数据（可能触发连接）
        String mime = source.getMime();
        long length = cacheCompleted ? cacheAvailable : source.length();
        return buildResponseHeaders(request, mime, length);
    }

    private String buildResponseHeaders(GetRequest request, String mime, long length) {
        boolean mimeKnown = !TextUtils.isEmpty(mime);
        boolean lengthKnown = length >= 0;
        long contentLength = request.partial ? length - request.rangeOffset : length;
        boolean addRange = lengthKnown && request.partial;
        return new StringBuilder()
                .append(request.partial ? "HTTP/1.1 206 PARTIAL CONTENT\n" : "HTTP/1.1 200 OK\n")
                .append("Accept-Ranges: bytes\n")
                .append(lengthKnown ? format("Content-Length: %d\n", contentLength) : "")
                .append(addRange ? format("Content-Range: bytes %d-%d/%d\n", request.rangeOffset, length - 1, length) : "")
                .append(mimeKnown ? format("Content-Type: %s\n", mime) : "")
                .append("\n") // headers end
                .toString();
    }

    private void responseWithCache(OutputStream out, long offset) throws ProxyCacheException, IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int readBytes;
        while ((readBytes = read(buffer, offset, buffer.length)) != -1) {
            out.write(buffer, 0, readBytes);
            offset += readBytes;
        }
        out.flush();
    }

    private void responseWithoutCache(OutputStream out, long offset) throws ProxyCacheException, IOException {
        Source newSourceNoCache = sourceFactory.copy(source);
        try {
            newSourceNoCache.open(offset);
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int readBytes;
            while ((readBytes = newSourceNoCache.read(buffer)) != -1) {
                out.write(buffer, 0, readBytes);
                offset += readBytes;
            }
            out.flush();
        } finally {
            newSourceNoCache.close();
        }
    }

    private String format(String pattern, Object... args) {
        return String.format(Locale.US, pattern, args);
    }

    @Override
    protected void onCachePercentsAvailableChanged(int percents) {
        if (listener != null) {
            listener.onCacheAvailable(cache.file, source.getUrl(), percents);
        }
    }
}
