package top.jessi.videoplayer.cache.internal;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;
import top.jessi.videoplayer.cache.source.Source;
import top.jessi.videoplayer.cache.Cache;
import top.jessi.videoplayer.cache.ProxyCacheException;
import top.jessi.videoplayer.cache.InterruptedProxyCacheException;

/**
 * Proxy for {@link Source} with caching support ({@link Cache}).
 * <p/>
 * Can be used only for sources with persistent data (that doesn't change with time).
 * Method {@link #read(byte[], long, int)} will be blocked while fetching data from source.
 * Useful for streaming something with caching e.g. streaming video/audio etc.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class ProxyCache {

    private static final int MAX_READ_SOURCE_ATTEMPTS = 1;

    private final Source source;
    private final Cache cache;
    private final Object wc = new Object();
    private final Object stopLock = new Object();
    private final AtomicInteger readSourceErrorsCount;
    private volatile Thread sourceReaderThread;
    private volatile boolean stopped;
    private volatile int percentsAvailable = -1;

    /**
     * 最近一次 {@link #read} 请求的偏移，供 {@link #readSource()} 决定续传起点。
     * <p>{@code -1} 表示无待处理请求。
     */
    private volatile long pendingRequestOffset = -1;

    public ProxyCache(Source source, Cache cache) {
        this.source = checkNotNull(source);
        this.cache = checkNotNull(cache);
        this.readSourceErrorsCount = new AtomicInteger();
    }

    public int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        ProxyCacheUtils.assertBuffer(buffer, offset, length);
        if (length == 0) {
            return 0;
        }

        // Range 请求落在源文件末尾时，最后一次读取不足 DEFAULT_BUFFER_SIZE 是正常情况。
        // 若仍等待完整 length，稀疏尾部区间会在 EOF 前一直等待，导致尾部预加载/播放卡住。
        int requiredLength = length;
        // 完全命中时不查询源站元数据；只有读取尾部不足一个缓冲块时才需要文件总长。
        if (!cache.isCached(offset, length)) {
            long sourceLength = cache.isCompleted() ? cache.available() : source.length();
            if (sourceLength >= 0) {
                if (offset >= sourceLength) {
                    return -1;
                }
                requiredLength = (int) Math.min((long) length, sourceLength - offset);
            }
            if (requiredLength == 0) {
                return -1;
            }
        }

        // 记录本次请求位置：readSource 会优先从这里续传，
        // 使"末尾 moov"这类离散区间也能被缓存，而不总是从顺序前缀开头重下。
        pendingRequestOffset = offset;

        boolean cacheHasEnoughData = cache.isCached(offset, requiredLength);

        if (!cacheHasEnoughData && !cache.isCompleted() && !stopped) {
            // 缓存不够，启动后台线程从源读取
            while (!cache.isCached(offset, requiredLength) && !cache.isCompleted() && !stopped) {
                readSourceAsync();
                waitForSourceData();
                checkReadSourceErrorsCount();
            }
        }

        int read = cache.read(buffer, offset, requiredLength);
        if (cache.isCompleted() && percentsAvailable != 100) {
            percentsAvailable = 100;
            onCachePercentsAvailableChanged(100);
        }
        return read;
    }

    private void checkReadSourceErrorsCount() throws ProxyCacheException {
        int errorsCount = readSourceErrorsCount.get();
        if (errorsCount >= MAX_READ_SOURCE_ATTEMPTS) {
            readSourceErrorsCount.set(0);
            throw new ProxyCacheException("Error reading source " + errorsCount + " times");
        }
    }

    public void shutdown() {
        synchronized (stopLock) {
            Logger.debug("Shutdown proxy for " + source);
            try {
                stopped = true;
                if (sourceReaderThread != null) {
                    sourceReaderThread.interrupt();
                }
                cache.close();
            } catch (ProxyCacheException e) {
                onError(e);
            }
        }
    }

    private synchronized void readSourceAsync() throws ProxyCacheException {
        boolean readingInProgress = sourceReaderThread != null && sourceReaderThread.getState() != Thread.State.TERMINATED;
        if (!stopped && !cache.isCompleted() && !readingInProgress) {
            sourceReaderThread = new Thread(new SourceReaderRunnable(), "Source reader for " + source);
            sourceReaderThread.start();
        }
    }

    private void waitForSourceData() throws ProxyCacheException {
        synchronized (wc) {
            try {
                wc.wait(1000);
            } catch (InterruptedException e) {
                throw new ProxyCacheException("Waiting source data is interrupted!", e);
            }
        }
    }

    protected final void notifyNewCacheDataAvailable(long cacheAvailable, long sourceAvailable) {
        onCacheAvailable(cacheAvailable, sourceAvailable);

        synchronized (wc) {
            wc.notifyAll();
        }
    }

    protected void onCacheAvailable(long cacheAvailable, long sourceLength) {
        boolean zeroLengthSource = sourceLength == 0;
        int percents = zeroLengthSource ? 100 : (int) ((float) cacheAvailable / sourceLength * 100);
        boolean percentsChanged = percents != percentsAvailable;
        boolean sourceLengthKnown = sourceLength >= 0;
        if (sourceLengthKnown && percentsChanged) {
            onCachePercentsAvailableChanged(percents);
        }
        percentsAvailable = percents;
    }

    protected void onCachePercentsAvailableChanged(int percentsAvailable) {
    }

    private void readSource() {
        long sourceAvailable = -1;
        long offset = 0;
        long sourceReadOffset = 0;
        long sourceReadStartedAt = SystemClock.elapsedRealtime();
        try {
            offset = cache.available();
            // 若请求的是更靠后的离散区间（如末尾 moov），从请求位置续传，
            // 避免把整个文件从顺序前缀重下一遍。
            long requestOffset = pendingRequestOffset;
            if (requestOffset > offset) {
                offset = requestOffset;
            }
            sourceReadOffset = offset;
            source.open(offset);
            sourceAvailable = source.length();
            byte[] buffer = new byte[ProxyCacheUtils.DEFAULT_BUFFER_SIZE];
            int readBytes;
            while ((readBytes = source.read(buffer)) != -1) {
                synchronized (stopLock) {
                    if (isStopped()) {
                        return;
                    }
                    cache.appendAt(offset, buffer, readBytes);
                }
                offset += readBytes;
                notifyNewCacheDataAvailable(offset, sourceAvailable);
            }
            tryComplete();
            onSourceRead();
        } catch (Throwable e) {
            readSourceErrorsCount.incrementAndGet();
            onError(e);
        } finally {
            closeSource();
            Logger.info("源站读取完成：offset=" + sourceReadOffset
                    + " bytes=" + Math.max(0, offset - sourceReadOffset)
                    + " sourceLength=" + sourceAvailable
                    + " costMs=" + (SystemClock.elapsedRealtime() - sourceReadStartedAt));
            notifyNewCacheDataAvailable(offset, sourceAvailable);
        }
    }

    private void onSourceRead() {
        // guaranteed notify listeners after source read and cache completed
        percentsAvailable = 100;
        onCachePercentsAvailableChanged(percentsAvailable);
    }

    private void tryComplete() throws ProxyCacheException {
        synchronized (stopLock) {
            if (!isStopped() && cache.available() == source.length()) {
                cache.complete();
            }
        }
    }

    private boolean isStopped() {
        return Thread.currentThread().isInterrupted() || stopped;
    }

    private void closeSource() {
        try {
            source.close();
        } catch (ProxyCacheException e) {
            onError(new ProxyCacheException("Error closing source " + source, e));
        }
    }

    protected final void onError(final Throwable e) {
        boolean interruption = e instanceof InterruptedProxyCacheException;
        if (interruption) {
            Logger.debug("ProxyCache is interrupted");
        } else {
            Logger.error("ProxyCache error");
        }
    }

    private class SourceReaderRunnable implements Runnable {

        @Override
        public void run() {
            readSource();
        }
    }
}
