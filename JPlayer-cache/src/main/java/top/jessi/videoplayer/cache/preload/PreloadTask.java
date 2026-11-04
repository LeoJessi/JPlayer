package top.jessi.videoplayer.cache.preload;

import android.os.SystemClock;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通过缓存服务的有界 Range 接口直接填充共享缓存，避免为预加载创建代理 socket。
 * 播放器去播放 VideoCache 生成的代理地址的时候，VideoCache 会直接返回缓存数据，
 * 从而提升播放速度。
 * <p>
 * <b>包级可见</b>：任务完全由 {@link PreloadManager} 创建与注入，不对外提供。
 * 外部自行 {@code new PreloadTask()} 只会得到一个无法工作的任务。
 */
class PreloadTask implements Runnable {

    /**
     * 原始地址，由 {@link PreloadManager} 注入
     */
    String mRawUrl;

    /**
     * 列表中的位置，由 {@link PreloadManager} 注入，仅用于日志与埋点
     */
    int mPosition;

    /**
     * VideoCache 服务器，由 {@link PreloadManager} 注入
     */
    HttpProxyCacheServer mCacheServer;

    /**
     * 需要预加载的字节数，由 {@link PreloadManager} 在创建任务时注入
     */
    long mPreloadLength = PreloadManager.DEFAULT_PRELOAD_LENGTH;

    /**
     * 预加载结果回调，由 {@link PreloadManager} 注入
     */
    OnPreloadListener mListener;

    /**
     * 尾部（moov）预加载执行器，由 {@link PreloadManager} 注入（单线程串行）。
     * <p>尾部 Range 在总长度可用后尽早排队，并通过独立 Source 写入共享稀疏缓存。
     */
    ExecutorService mTailExecutor;

    /** 当前尾部范围请求的线程，用于及时响应取消。 */
    private volatile Thread mTailThread;

    /** Per-attempt token shared by head and tail ranges; old work keeps its canceled token. */
    private volatile AtomicBoolean mCancellationToken = new AtomicBoolean();

    /**
     * 预加载结果回调。
     */
    interface OnPreloadListener {

        /**
         * @param url        原始地址
         * @param preloaded  {@code true} 表示已读满 {@link #mPreloadLength}，缓存已就绪
         * @param bytes      本次实际读取的字节数
         * @param costMs     本次耗时（毫秒）
         */
        void onPreloadFinished(String url, boolean preloaded, long bytes, long costMs);
    }

    /**
     * 是否被取消。
     * <p>必须为 {@code volatile}：预加载线程读取、调度/主线程写入，
     * 否则取消指令可能长时间不可见，导致任务无法及时停止。
     */
    private volatile boolean mIsCanceled;

    /**
     * 是否已被取消（供同包的调度方统计用）
     */
    boolean isCanceled() {
        return mIsCanceled;
    }

    /** Source length discovered by the direct head-range request, or -1 if unknown. */
    private volatile long mSourceLength = -1;

    /**
     * 小黑屋容量上限
     */
    private static final int BLACK_LIST_MAX = 100;

    /**
     * 预加载文件末尾的大小（字节），用于缓存 MP4 的 moov。
     * <p>实测 moov atom 大小取决于音轨/字幕数量，本台源上 64KB ~ 370KB 不等
     * （159651.mp4 的 moov 起点在 total-377864，159662.mp4 在 total-357299），
     * 64KB 只够覆盖最小的一批，其余仍会回源。取 512KB 留足余量。
     */
    private static final int TAIL_SIZE = 512 * 1024;

    /**
     * 小黑屋条目有效期：10 分钟。
     * <p>超时后自动解封。一次瞬时网络抖动、源站临时 5xx 不该让某个 url 在整个进程
     * 生命周期内都失去预加载机会。
     */
    private static final long BLACK_LIST_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * 预加载失败过的 url（"小黑屋"）-&gt; 解封时间戳。
     * <p>线程安全（{@link java.util.Collections#synchronizedMap}）且有界（LRU，最多
     * {@value #BLACK_LIST_MAX} 条）。
     * <p>用 Map 而非 Set：需要记录失败时间以实现自动解封。
     */
    private static final Map<String, Long> BLACK_LIST =
            PreloadManager.newBoundedMap(BLACK_LIST_MAX);

    /**
     * 该 url 是否在小黑屋里（预加载失败过，且尚未到解封时间）。
     * <p>供调度方在提交前过滤，避免重复提交必然失败的任务。
     */
    static boolean isBlackListed(String url) {
        if (url == null) return false;
        synchronized (BLACK_LIST) {
            Long expireAt = BLACK_LIST.get(url);
            if (expireAt == null) return false;
            if (System.currentTimeMillis() >= expireAt) {
                // 已过期，解封后放行
                BLACK_LIST.remove(url);
                return false;
            }
            return true;
        }
    }

    /**
     * 关进小黑屋，{@link #BLACK_LIST_TIMEOUT_MS} 后自动解封。
     */
    private static void addToBlackList(String url) {
        synchronized (BLACK_LIST) {
            BLACK_LIST.put(url, System.currentTimeMillis() + BLACK_LIST_TIMEOUT_MS);
        }
    }

    private static boolean isTransientStreamFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.io.IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                    .contains("unexpected end of stream")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void run() {
        long startedAt = SystemClock.elapsedRealtime();
        long bytes = 0;
        boolean preloaded = false;
        try {
            if (!mIsCanceled) {
                bytes = start();
                // 视频本身小于预加载目标时，正式缓存文件存在也代表已完整缓存。
                preloaded = bytes >= mPreloadLength
                        || (mSourceLength > 0 && bytes >= mSourceLength)
                        || (mCacheServer != null && mCacheServer.isCached(mRawUrl));
            }
        } finally {
            long costMs = SystemClock.elapsedRealtime() - startedAt;
            Logger.info("预加载结果：position=" + mPosition
                    + " ready=" + preloaded
                    + " bytes=" + bytes
                    + " costMs=" + costMs
                    + " canceled=" + mIsCanceled);
            // 回调必须放在 finally：start() 只 catch 了 Exception，一旦抛 Error
            // （OOM、NoClassDefFoundError 等），回调仍是清理 mInFlight 的唯一入口。
            // 跳过它会让该 url 在进程剩余生命周期内永久失去预加载机会，且没有任何日志。
            if (mListener != null) {
                mListener.onPreloadFinished(mRawUrl, preloaded, bytes, costMs);
            }
        }
    }

    /**
     * 开始预加载
     *
     * @return 本次实际读取的字节数（也是判断是否读满的依据）
     */
    private long start() {
        if (mIsCanceled || mCancellationToken.get()) {
            Logger.info("预加载取消（start 前检查）：" + mPosition);
            return 0;
        }
        if (isBlackListed(mRawUrl)) return 0;
        Logger.info("预加载开始：" + mPosition);
        long read = 0;
        try {
            if (mIsCanceled || mCancellationToken.get()) {
                Logger.info("预加载取消（源站请求前检查）：" + mPosition);
                return 0;
            }
            if (mCacheServer == null) {
                Logger.info("预加载跳过（缓存服务不可用）：" + mPosition);
                return 0;
            }

            final AtomicBoolean canceled = mCancellationToken;
            read = mCacheServer.preloadRange(mRawUrl, 0, mPreloadLength, canceled,
                    sourceLength -> {
                        mSourceLength = sourceLength;
                        if (sourceLength > mPreloadLength) {
                            // Queue the MP4 metadata range as soon as the head response
                            // reveals the total length, while the bounded head read continues.
                            scheduleTailPreload(sourceLength);
                        }
                    });
            if (mIsCanceled || canceled.get()) {
                Logger.info("预加载取消：" + mPosition + " 读取数据：" + read + " Byte");
            } else if (read >= mPreloadLength
                    || (mSourceLength > 0 && read >= mSourceLength)) {
                Logger.info("预加载成功：" + mPosition + " 读取数据：" + read + " Byte");
            }
        } catch (Exception e) {
            if (mIsCanceled || mCancellationToken.get()) {
                Logger.info("预加载取消：" + mPosition);
            } else {
                Logger.info("预加载异常：" + mPosition + " 异常信息：" + e.getMessage());
                if (isTransientStreamFailure(e)) {
                    Logger.info("预加载网络流中断，保留重试资格：" + mPosition);
                } else {
                    addToBlackList(mRawUrl);
                }
            }
        } finally {
            Logger.info("预加载结束: " + mPosition);
        }
        return read;
    }

    private void scheduleTailPreload(final long total) {
        if (mIsCanceled) {
            return;
        }
        final AtomicBoolean canceled = mCancellationToken;
        if (canceled.get()) {
            return;
        }
        Runnable tailJob = () -> preloadTail(total, canceled);
        if (mTailExecutor == null) {
            tailJob.run();
            return;
        }
        try {
            mTailExecutor.execute(tailJob);
        } catch (RejectedExecutionException e) {
            Logger.info("尾部 Range 排队跳过：position=" + mPosition + " reason=queue-full-or-shutdown");
        }
    }

    /**
     * 预加载文件末尾的 moov 区间。
     * <p>直接对源站发 Range 请求并写入缓存文件的离散区间，不等待代理的顺序读取器。
     */
    private void preloadTail(long total, AtomicBoolean canceled) {
        long tailRead = 0;
        long tailStart = -1;
        long expectedTailBytes = 0;
        long tailStartedAt = SystemClock.elapsedRealtime();
        mTailThread = Thread.currentThread();
        try {
            tailStart = Math.max(mPreloadLength, total - TAIL_SIZE);
            expectedTailBytes = total - tailStart;
            if (mIsCanceled || canceled.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            // 直接读取独立源站 Range，并写入同一 FileCache 的稀疏区间。
            tailRead = mCacheServer.preloadRange(mRawUrl, tailStart, expectedTailBytes, canceled);
            Logger.info("预加载末尾 Range：position=" + mPosition
                    + " offset=" + tailStart
                    + " bytes=" + tailRead + "/" + expectedTailBytes
                    + " costMs=" + (SystemClock.elapsedRealtime() - tailStartedAt)
                    + " canceled=" + (mIsCanceled || canceled.get() || Thread.currentThread().isInterrupted()));
        } catch (Exception e) {
            // 末尾预加载失败不影响头部缓存的可用性，只记录
            Logger.info("预加载末尾 Range 失败：position=" + mPosition
                    + " offset=" + tailStart
                    + " bytes=" + tailRead + "/" + expectedTailBytes
                    + " costMs=" + (SystemClock.elapsedRealtime() - tailStartedAt)
                    + " canceled=" + (mIsCanceled || canceled.get() || Thread.currentThread().isInterrupted())
                    + " error=" + e.getMessage());
        } finally {
            mTailThread = null;
        }
    }

    /**
     * 将预加载任务提交到线程池，准备执行。
     * <p>重新提交时会清除取消标记，保证任务不会因为上一次被取消而永远无法执行。
     * <p>不去重：调用方（{@link PreloadManager} 的调度线程）通过 {@code mInFlight}
     * 保证同一 url 不会被重复提交。线程池拒绝时由调度器移除对应标记，
     * 后续窗口重算会再次提交。
     */
    void executeOn(ExecutorService executorService) {
        mIsCanceled = false;
        mSourceLength = -1;
        mCancellationToken = new AtomicBoolean();
        // 使用 execute 保留原始 Runnable，便于拒绝处理器清理对应 URL 的 in-flight 标记。
        executorService.execute(this);
    }

    /**
     * 取消预加载任务。
     * <p>取消令牌和 Source.cancel() 会停止本任务的上游 Range 读取，不影响播放器的
     * 普通代理请求，也不会留下等待处理的本地代理 socket。
     */
    void cancel() {
        mIsCanceled = true;
        AtomicBoolean canceled = mCancellationToken;
        canceled.set(true);
        if (mCacheServer != null) {
            mCacheServer.cancelRangePreload(mRawUrl);
        }
        // 让尾部线程尽快观察取消令牌。
        Thread tailThread = mTailThread;
        if (tailThread != null) {
            tailThread.interrupt();
        }
    }
}
