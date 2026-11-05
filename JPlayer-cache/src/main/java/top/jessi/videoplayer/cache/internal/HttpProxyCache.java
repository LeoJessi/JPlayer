package top.jessi.videoplayer.cache.internal;

import static top.jessi.videoplayer.cache.internal.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;

import android.os.SystemClock;
import android.text.TextUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import top.jessi.videoplayer.cache.CacheListener;
import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.ProxyCacheException;
import top.jessi.videoplayer.cache.file.FileCache;
import top.jessi.videoplayer.cache.source.HttpUrlSource;
import top.jessi.videoplayer.cache.source.Source;
import top.jessi.videoplayer.cache.source.SourceFactory;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfo;

/**
 * {@link ProxyCache} that read http url and writes data to {@link Socket}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class HttpProxyCache extends ProxyCache {

    private static final float NO_CACHE_BARRIER = .2f;
    /** Persist direct reads near EOF because MP4 metadata commonly lives there. */
    private static final long SPARSE_TAIL_CACHE_WINDOW = 2L * 1024 * 1024;
    private static final int MAX_RANGE_READ_RETRIES = 2;

    /**
     * 响应写入的最大空闲时长（毫秒）。
     * <p>超过此时长没有任何字节成功写给客户端，就认为客户端已经离开（被滑走或已释放），
     * 主动关闭 socket 释放代理线程，避免线程被一个不再读取的连接永久占死。
     * <p>写 socket 不受 {@code SO_TIMEOUT} 约束，必须有这层看门狗兜底。
     */
    private static final long RESPONSE_IDLE_TIMEOUT_MS = 15000L;

    /** 共享的响应看门狗，单线程即可，所有 URL 的响应共用。 */
    private static final ScheduledExecutorService RESPONSE_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "proxy-response-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final Source source;
    private final SourceFactory sourceFactory;
    private final FileCache cache;
    private CacheListener listener;
    /** Independent upstream Sources used by direct, non-cache-backed proxy responses. */
    private final Set<Source> mProxyResponseSources =
            Collections.newSetFromMap(new ConcurrentHashMap<Source, Boolean>());
    private final Set<Source> mRangePreloadSources =
            Collections.newSetFromMap(new ConcurrentHashMap<Source, Boolean>());

    /** Set only when this cache instance is shutting down, never for one preload task. */
    private volatile boolean mCanceled;

    /** 正在进行的响应，用于空闲超时检测。 */
    private final List<ResponseGuard> mResponseGuards = new CopyOnWriteArrayList<>();

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
        return preloadRange(offset, maxBytes, canceled, null);
    }

    public long preloadRange(long offset, long maxBytes, AtomicBoolean canceled,
                             HttpProxyCacheServer.RangePreloadListener listener)
            throws ProxyCacheException {
        if (offset < 0 || maxBytes <= 0 || offset > Long.MAX_VALUE - maxBytes || canceled.get()) {
            return 0;
        }

        if (cache.isCompleted()) {
            long cachedLength = cache.available();
            return offset >= cachedLength ? 0 : Math.min(maxBytes, cachedLength - offset);
        }
        // Do not call source.length() here: it may issue a metadata request before a
        // cancellable per-range Source has been registered.
        long sourceLength = source.knownLength();
        long bytesToRead = maxBytes;
        if (sourceLength >= 0) {
            if (offset >= sourceLength) {
                return 0;
            }
            bytesToRead = Math.min(maxBytes, sourceLength - offset);
        }
        if (cache.isCached(offset, bytesToRead) && (listener == null || sourceLength >= 0)) {
            if (listener != null && sourceLength >= 0 && !canceled.get()) {
                listener.onSourceLengthKnown(sourceLength);
            }
            completeIfFullyCached(sourceLength, canceled);
            return bytesToRead;
        }

        Source rangeSource = sourceFactory.copy(source);
        mRangePreloadSources.add(rangeSource);
        long bytesRead = 0;
        int retries = 0;
        try {
            if (canceled.get() || Thread.currentThread().isInterrupted()) {
                return 0;
            }
            rangeSource.open(offset);
            long openedSourceLength = rangeSource.length();
            if (openedSourceLength >= 0) {
                // The upstream may cap a Range response before EOF. Keep the already-known
                // object length so the missing suffix can be fetched with another Range.
                if (sourceLength < 0 || openedSourceLength > sourceLength) {
                    sourceLength = openedSourceLength;
                }
                if (offset >= sourceLength) {
                    return 0;
                }
                bytesToRead = Math.min(maxBytes, sourceLength - offset);
            }
            if (listener != null && sourceLength >= 0 && !canceled.get()) {
                listener.onSourceLengthKnown(sourceLength);
            }
            if (cache.isCompleted() || cache.isCached(offset, bytesToRead)) {
                completeIfFullyCached(sourceLength, canceled);
                return bytesToRead;
            }
            byte[] buffer = new byte[(int) Math.min((long) DEFAULT_BUFFER_SIZE, bytesToRead)];
            while (bytesRead < bytesToRead
                    && !canceled.get()
                    && !Thread.currentThread().isInterrupted()) {
                int count = rangeSource.read(buffer);
                if (count < 0) {
                    if (retries >= MAX_RANGE_READ_RETRIES || sourceLength < 0
                            || offset + bytesRead >= sourceLength) {
                        break;
                    }
                    retries++;
                    long retryOffset = offset + bytesRead;
                    Logger.info("尾部 Range 提前结束，续取：offset=" + retryOffset
                            + " remaining=" + (bytesToRead - bytesRead)
                            + " retry=" + retries);
                    rangeSource.close();
                    rangeSource.open(retryOffset);
                    openedSourceLength = Math.max(sourceLength, rangeSource.length());
                    continue;
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
            completeIfFullyCached(sourceLength, canceled);
            return bytesRead;
        } catch (ProxyCacheException e) {
            if (canceled.get() || Thread.currentThread().isInterrupted() || cache.isCompleted()) {
                return bytesRead;
            }
            throw e;
        } finally {
            mRangePreloadSources.remove(rangeSource);
            try {
                rangeSource.close();
            } catch (ProxyCacheException e) {
                Logger.debug("Error closing independent range source: " + e.getMessage());
            }
        }
    }

    private void completeIfFullyCached(long sourceLength, AtomicBoolean canceled)
            throws ProxyCacheException {
        if (sourceLength > 0 && !canceled.get() && !cache.isCompleted()
                && cache.isCached(0, sourceLength)) {
            cache.complete();
        }
    }

    /**
     * Cancels only independent range-preload sources. Ordinary proxy playback requests
     * share this cache instance and must not be stopped by cancelling a preload task.
     */
    @Deprecated
    public void cancelPreload() {
        cancelRangePreload();
    }

    public void cancelRangePreload() {
        // Signal each independent range source; each worker owns and closes its stream.
        for (Source rangeSource : mRangePreloadSources) {
            rangeSource.cancel();
        }
    }

    @Override
    public void shutdown() {
        mCanceled = true;
        // Cancel each request's source as well as the sequential reader source.
        for (Source responseSource : mProxyResponseSources) {
            responseSource.cancel();
        }
        // 兜底：强制断开所有仍在进行的响应连接，避免线程被不再读取的客户端占死。
        for (ResponseGuard guard : mResponseGuards) {
            guard.cancelAndClose();
        }
        mResponseGuards.clear();
        cancelRangePreload();
        super.shutdown();
    }

    public void processRequest(GetRequest request, Socket socket) throws IOException, ProxyCacheException {
        ResponseGuard guard = new ResponseGuard(socket);
        guard.startWatchdog();
        mResponseGuards.add(guard);
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String responseHeaders = newResponseHeaders(request);
            out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
            guard.touch();
            if (isRangeNotSatisfiable(request)) {
                out.flush();
                return;
            }

            long offset = request.rangeOffset;
            boolean cacheBacked = isUseCache(request);
            boolean cacheSparseTail = !cacheBacked && shouldCacheSparseTail(offset);
            Logger.info("代理请求：offset=" + offset
                    + " partial=" + request.partial
                    + " path=" + (cacheBacked ? "cache-backed"
                            : cacheSparseTail ? "direct-upstream-sparse-tail" : "direct-upstream")
                    + " cachedPrefix=" + cache.available()
                    + " complete=" + cache.isCompleted());
            if (cacheBacked) {
                responseWithCache(out, offset, guard);
            } else {
                responseWithoutCache(out, offset, cacheSparseTail, guard);
            }
        } finally {
            guard.cancel();
            mResponseGuards.remove(guard);
        }
    }

    private boolean shouldCacheSparseTail(long offset) throws ProxyCacheException {
        long sourceLength = source.knownLength();
        return sourceLength > 0 && offset >= Math.max(0, sourceLength - SPARSE_TAIL_CACHE_WINDOW);
    }

    private boolean isRangeNotSatisfiable(GetRequest request) throws ProxyCacheException {
        if (!request.partial) {
            return false;
        }
        long length = cache.isCompleted() ? cache.available() : source.knownLength();
        return length >= 0 && request.rangeOffset >= length;
    }

    private boolean isUseCache(GetRequest request) throws ProxyCacheException {
        // 离散缓存区间（如末尾 moov）已就绪时直接命中，避免走不写缓存的响应路径
        if (cache.isCached(request.rangeOffset, DEFAULT_BUFFER_SIZE)) {
            return true;
        }

        long cacheAvailable = cache.available();
        long sourceLength = source.knownLength();
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

    private void responseWithCache(OutputStream out, long offset, ResponseGuard guard)
            throws ProxyCacheException, IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int readBytes;
        while (!mCanceled && !guard.isCanceled()
                && (readBytes = read(buffer, offset, buffer.length)) != -1) {
            out.write(buffer, 0, readBytes);
            offset += readBytes;
            guard.touch();
        }
        out.flush();
    }

    private void responseWithoutCache(OutputStream out, long offset, boolean cacheSparseTail,
                                      ResponseGuard guard)
            throws ProxyCacheException, IOException {
        if (mCanceled) return;
        Source newSourceNoCache = sourceFactory.copy(source);
        mProxyResponseSources.add(newSourceNoCache);
        long originalOffset = offset;
        long cacheWriteOffset = offset;
        long cachedBytes = 0;
        long sourceLength = source.knownLength();
        int retries = 0;
        try {
            if (mCanceled) {
                newSourceNoCache.cancel();
                return;
            }
            newSourceNoCache.open(offset);
            sourceLength = Math.max(sourceLength, newSourceNoCache.length());
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int readBytes;
            while (!mCanceled && !guard.isCanceled()) {
                readBytes = newSourceNoCache.read(buffer);
                if (readBytes < 0) {
                    if (sourceLength < 0 || offset >= sourceLength) {
                        break;
                    }
                    if (retries >= MAX_RANGE_READ_RETRIES) {
                        throw new ProxyCacheException("Unexpected end of source range at " + offset
                                + " of " + sourceLength + " for " + newSourceNoCache.getUrl());
                    }
                    retries++;
                    Logger.info("直连 Range 提前结束，续取：offset=" + offset
                            + " remaining=" + (sourceLength - offset)
                            + " retry=" + retries);
                    newSourceNoCache.close();
                    newSourceNoCache.open(offset);
                    sourceLength = Math.max(sourceLength, newSourceNoCache.length());
                    continue;
                }
                out.write(buffer, 0, readBytes);
                guard.touch();
                if (cacheSparseTail && !cache.isCompleted()) {
                    try {
                        cache.appendAt(cacheWriteOffset, buffer, readBytes);
                        cacheWriteOffset += readBytes;
                        cachedBytes += readBytes;
                        notifyNewCacheDataAvailable(cache.available(), sourceLength);
                    } catch (ProxyCacheException e) {
                        // This is a best-effort cache write; keep streaming if another reader
                        // completed the file or the cache became unavailable meanwhile.
                        if (!cache.isCompleted()) {
                            Logger.warn("Unable to persist direct tail range at " + cacheWriteOffset
                                    + " for " + newSourceNoCache.getUrl() + ": " + e.getMessage());
                        }
                        cacheSparseTail = false;
                    }
                }
                offset += readBytes;
            }
            out.flush();
        } finally {
            mProxyResponseSources.remove(newSourceNoCache);
            newSourceNoCache.close();
            if (cachedBytes > 0) {
                Logger.info("直连尾部 Range 已写入稀疏缓存：offset=" + originalOffset
                        + " bytes=" + cachedBytes + " sourceLength=" + sourceLength);
            }
        }
    }

    /**
     * 单个客户端连接的运行时状态：空闲检测 + 超时后强制关连接。
     * <p>每个 {@code processRequest} 调用持有自己的 guard，因此一个卡住的客户端
     * 不会影响同一 URL 的其他客户端。
     */
    private static final class ResponseGuard {

        private final Socket socket;
        private final AtomicBoolean canceled = new AtomicBoolean();
        private volatile long lastProgressAt = SystemClock.elapsedRealtime();
        private ScheduledFuture<?> watchdog;

        ResponseGuard(Socket socket) {
            this.socket = socket;
        }

        boolean isCanceled() {
            return canceled.get();
        }

        /** 每次成功写出字节后刷新进度时间。 */
        void touch() {
            lastProgressAt = SystemClock.elapsedRealtime();
        }

        void startWatchdog() {
            watchdog = RESPONSE_WATCHDOG.scheduleAtFixedRate(() -> {
                if (canceled.get()) {
                    return;
                }
                long idle = SystemClock.elapsedRealtime() - lastProgressAt;
                if (idle < RESPONSE_IDLE_TIMEOUT_MS) {
                    return;
                }
                canceled.set(true);
                Logger.warn("代理响应空闲 " + idle + "ms，强制关闭连接：" + socket);
                closeSocket(socket);
            }, RESPONSE_IDLE_TIMEOUT_MS, RESPONSE_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        void cancel() {
            canceled.set(true);
            ScheduledFuture<?> future = watchdog;
            if (future != null) {
                future.cancel(false);
            }
        }

        void cancelAndClose() {
            cancel();
            closeSocket(socket);
        }

        private static void closeSocket(Socket socket) {
            try {
                socket.shutdownInput();
            } catch (IOException ignored) {
            }
            try {
                socket.shutdownOutput();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
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
