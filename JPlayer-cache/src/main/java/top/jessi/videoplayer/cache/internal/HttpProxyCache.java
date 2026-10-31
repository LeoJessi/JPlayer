package top.jessi.videoplayer.cache.internal;

import android.text.TextUtils;

import top.jessi.videoplayer.cache.file.FileCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile Source mRangePreloadSource;

    public HttpProxyCache(Source source, FileCache cache, SourceFactory sourceFactory) {
        super(source, cache);
        this.cache = cache;
        this.source = source;
        this.sourceFactory = sourceFactory;
    }

    public void registerCacheListener(CacheListener cacheListener) {
        this.listener = cacheListener;
    }

    /**
     * Loads a bounded source range independently from the sequential ProxyCache reader,
     * then appends it to the same sparse FileCache used by playback.
     */
    public long preloadRange(long offset, long maxBytes, AtomicBoolean canceled)
            throws ProxyCacheException {
        if (offset < 0 || maxBytes <= 0 || offset > Long.MAX_VALUE - maxBytes || canceled.get()) {
            return 0;
        }

        if (cache.isCompleted()) {
            long cachedLength = cache.available();
            return offset >= cachedLength ? 0 : Math.min(maxBytes, cachedLength - offset);
        }
        long sourceLength = source.length();
        long bytesToRead = maxBytes;
        if (sourceLength >= 0) {
            if (offset >= sourceLength) {
                return 0;
            }
            bytesToRead = Math.min(maxBytes, sourceLength - offset);
        }
        if (cache.isCached(offset, bytesToRead)) {
            return bytesToRead;
        }

        Source rangeSource = sourceFactory.copy(source);
        mRangePreloadSource = rangeSource;
        long bytesRead = 0;
        try {
            if (canceled.get() || Thread.currentThread().isInterrupted()) {
                return 0;
            }
            rangeSource.open(offset);
            long openedSourceLength = rangeSource.length();
            if (openedSourceLength >= 0) {
                if (offset >= openedSourceLength) {
                    return 0;
                }
                bytesToRead = Math.min(bytesToRead, openedSourceLength - offset);
            }
            if (cache.isCompleted() || cache.isCached(offset, bytesToRead)) {
                return bytesToRead;
            }
            byte[] buffer = new byte[(int) Math.min((long) DEFAULT_BUFFER_SIZE, bytesToRead)];
            while (bytesRead < bytesToRead
                    && !canceled.get()
                    && !Thread.currentThread().isInterrupted()) {
                int count = rangeSource.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                int countToWrite = (int) Math.min((long) count, bytesToRead - bytesRead);
                if (cache.isCompleted()) {
                    break;
                }
                try {
                    cache.appendAt(offset + bytesRead, buffer, countToWrite);
                } catch (ProxyCacheException e) {
                    // A concurrent sequential read may have completed and renamed the file.
                    if (!cache.isCompleted()) {
                        throw e;
                    }
                    break;
                }
                bytesRead += countToWrite;
                notifyNewCacheDataAvailable(cache.available(), openedSourceLength);
            }
            return bytesRead;
        } catch (ProxyCacheException e) {
            if (canceled.get() || Thread.currentThread().isInterrupted() || cache.isCompleted()) {
                return bytesRead;
            }
            throw e;
        } finally {
            if (mRangePreloadSource == rangeSource) {
                mRangePreloadSource = null;
            }
            try {
                rangeSource.close();
            } catch (ProxyCacheException e) {
                Logger.debug("Error closing independent range source: " + e.getMessage());
            }
        }
    }

    public void cancelRangePreload() {
        Source rangeSource = mRangePreloadSource;
        if (rangeSource == null) {
            return;
        }
        // Signal the source from this thread; the range worker owns and closes its stream.
        rangeSource.cancel();
    }

    @Override
    public void shutdown() {
        cancelRangePreload();
        super.shutdown();
    }

    public void processRequest(GetRequest request, Socket socket) throws IOException, ProxyCacheException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String responseHeaders = newResponseHeaders(request);
        out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        if (isRangeNotSatisfiable(request)) {
            out.flush();
            return;
        }

        long offset = request.rangeOffset;
        boolean cacheBacked = isUseCache(request);
        Logger.info("代理请求：offset=" + offset
                + " partial=" + request.partial
                + " path=" + (cacheBacked ? "cache-backed" : "direct-upstream")
                + " cachedPrefix=" + cache.available()
                + " complete=" + cache.isCompleted());
        if (cacheBacked) {
            responseWithCache(out, offset);
        } else {
            responseWithoutCache(out, offset);
        }
    }

    private boolean isRangeNotSatisfiable(GetRequest request) throws ProxyCacheException {
        if (!request.partial) {
            return false;
        }
        long length = cache.isCompleted() ? cache.available() : source.length();
        return length >= 0 && request.rangeOffset >= length;
    }

    private boolean isUseCache(GetRequest request) throws ProxyCacheException {
        // 离散缓存区间（如末尾 moov）已就绪时直接命中，避免走不写缓存的响应路径
        if (cache.isCached(request.rangeOffset, DEFAULT_BUFFER_SIZE)) {
            return true;
        }

        long cacheAvailable = cache.available();
        // 优化：如果有足够缓存（1MB+），假定元数据已知，优先使用缓存
        if (cacheAvailable >= 1024 * 1024) {
            return true;
        }

        long sourceLength = source.length();
        boolean sourceLengthKnown = sourceLength > 0;
        if (request.partial && sourceLengthKnown && request.rangeOffset < sourceLength) {
            long requestedBytes = Math.min((long) DEFAULT_BUFFER_SIZE, sourceLength - request.rangeOffset);
            if (cache.isCached(request.rangeOffset, requestedBytes)) {
                return true;
            }
        }
        // do not use cache for partial requests which too far from available cache. It seems user seek video.
        return !sourceLengthKnown || !request.partial || request.rangeOffset <= cacheAvailable + sourceLength * NO_CACHE_BARRIER;
    }

    private String newResponseHeaders(GetRequest request) throws IOException, ProxyCacheException {
        long cacheAvailable = cache.available();
        boolean cacheCompleted = cache.isCompleted();

        // 优化：优先从数据库读取元数据，即使完全没有缓存也先尝试，避免额外网络请求
        // 只要数据库有完整的元数据（length > 0 且 mime 不为空），就直接使用
        if (source instanceof HttpUrlSource) {
            SourceInfo info = ((HttpUrlSource) source).getSourceInfoDirect();
            if (info != null && info.length > 0 && !android.text.TextUtils.isEmpty(info.mime)) {
                // 数据库有完整元数据，直接使用，完全不调用 source.getMime() 或 source.length()
                // 这样即使是首播（cacheAvailable == 0），只要之前预加载过或播放过，就能避免额外连接
                long length = cacheCompleted ? cacheAvailable : info.length;
                return buildResponseHeaders(request, info.mime, length);
            }
        }

        // 只有数据库真的没有元数据时才连源站获取
        String mime = source.getMime();
        long length = cacheCompleted ? cacheAvailable : source.length();
        return buildResponseHeaders(request, mime, length);
    }

    private String buildResponseHeaders(GetRequest request, String mime, long length) {
        boolean mimeKnown = !TextUtils.isEmpty(mime);
        boolean lengthKnown = length >= 0;
        boolean rangeNotSatisfiable = request.partial && lengthKnown
                && request.rangeOffset >= length;
        if (rangeNotSatisfiable) {
            return new StringBuilder()
                    .append("HTTP/1.1 416 RANGE NOT SATISFIABLE\r\n")
                    .append(format("Content-Range: bytes */%d\r\n", length))
                    .append("Content-Length: 0\r\n\r\n")
                    .toString();
        }
        long contentLength = request.partial ? length - request.rangeOffset : length;
        boolean addRange = lengthKnown && request.partial;
        return new StringBuilder()
                .append(request.partial ? "HTTP/1.1 206 PARTIAL CONTENT\r\n" : "HTTP/1.1 200 OK\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append(lengthKnown ? format("Content-Length: %d\r\n", contentLength) : "")
                .append(addRange ? format("Content-Range: bytes %d-%d/%d\r\n", request.rangeOffset, length - 1, length) : "")
                .append(mimeKnown ? format("Content-Type: %s\r\n", mime) : "")
                .append("\r\n") // headers end
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
