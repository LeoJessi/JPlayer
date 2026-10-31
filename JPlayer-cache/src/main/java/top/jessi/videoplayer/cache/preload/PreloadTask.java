package top.jessi.videoplayer.cache.preload;

import android.os.SystemClock;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;
import top.jessi.videoplayer.cache.internal.ProxyCacheUtils;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static top.jessi.videoplayer.cache.internal.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;

/**
 * 原理：主动去请求 VideoCache 生成的代理地址，触发 VideoCache 缓存机制
 * 缓存到 {@link #mPreloadLength} 的数据之后停止请求，完成预加载
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

    /** 独立令牌：重试时旧的排队尾部请求不会被重新激活。 */
    private volatile AtomicBoolean mTailCanceled = new AtomicBoolean();

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

    /**
     * 当前正在读取的连接。{@link #cancel()} 时调用 {@link HttpURLConnection#disconnect()}
     * 立刻打断阻塞中的 read，避免线程被一直占用到读超时。
     */
    private volatile HttpURLConnection mConnection;

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
        // 如果在小黑屋里不加载
        if (isBlackListed(mRawUrl)) return 0;
        Logger.info("预加载开始：" + mPosition);
        HttpURLConnection connection = null;
        long read = 0;
        try {
            //获取 HttpProxyCacheServer 的代理地址。
            // 必须传 allowCachedFileUri=false：否则缓存已完整时 getProxyUrl 会返回 file:// uri，
            // openConnection() 得到的就不是 HttpURLConnection，强转会抛 ClassCastException
            // 并把该 url 误关进小黑屋。预加载的目的是触发代理去拉取并缓存，本就应该走代理地址。
            String proxyUrl = mCacheServer.getProxyUrl(mRawUrl, false);
            // 代理未存活时 getProxyUrl 会原样返回源地址，此时直连源站不会写入缓存，
            // 2MB 带宽白花还会被误标记为"已预加载"，直接放弃
            if (proxyUrl == null || proxyUrl.equals(mRawUrl)) {
                Logger.info("预加载跳过（缓存服务未启动）：" + mPosition);
                return 0;
            }
            URL url = new URL(proxyUrl);
            // 只预加载 http/https 资源，本地或非常规协议无法走 HttpURLConnection
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                Logger.info("预加载跳过（不支持的协议 " + protocol + "）：" + mPosition);
                return 0;
            }
            connection = (HttpURLConnection) url.openConnection();
            // 先设好超时再暴露引用，保证 cancel() 一旦看到 mConnection，超时就绪
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            mConnection = connection;
            // 取消指令可能恰好在 openConnection() 与 mConnection 赋值之间到达，这里补一次检查
            if (mIsCanceled) {
                connection.disconnect();
                return 0;
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                // The initial 200 response already exposes the total length. Start the tail
                // Range while the head is still downloading instead of waiting for 2 MB.
                Long total = parseContentLength(connection);
                if (total != null && total > mPreloadLength) {
                    scheduleTailPreload(total);
                }
                int length;
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                while ((length = in.read(buffer)) != -1) {
                    read += length;
                    //被取消
                    if (mIsCanceled) {
                        Logger.info("预加载取消：" + mPosition + " 读取数据：" + read + " Byte");
                        return read;
                    }
                    //预加载完成
                    if (read >= mPreloadLength) {
                        Logger.info("预加载成功：" + mPosition + " 读取数据：" + read + " Byte");
                        return read;
                    }
                }
            }
        } catch (Exception e) {
            if (mIsCanceled) {
                // 取消（disconnect）会打断阻塞中的 read 并抛 IOException，这不是源失效。
                // 若一并关进小黑屋，则每次滑出预加载窗口都会让该 url 永久失去预加载机会，
                // 滑动几十次后整个列表都会失效。
                Logger.info("预加载取消：" + mPosition);
            } else {
                Logger.info("预加载异常：" + mPosition + " 异常信息：" + e.getMessage());
                // 关入小黑屋
                addToBlackList(mRawUrl);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            mConnection = null;
            Logger.info("预加载结束: " + mPosition);
        }
        return read;
    }

    /**
     * 从响应头解析总长度。
     * <p>代理对 {@code rangeOffset == 0} 的请求会返回完整的 {@code Content-Length}。
     */
    private static Long parseContentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            long length = Long.parseLong(value.trim());
            return length > 0 ? length : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void scheduleTailPreload(final long total) {
        if (mIsCanceled) {
            return;
        }
        final AtomicBoolean canceled = mTailCanceled;
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
                    + " costMs=" + (SystemClock.elapsedRealtime() - tailStartedAt));
        } catch (Exception e) {
            // 末尾预加载失败不影响头部缓存的可用性，只记录
            Logger.info("预加载末尾 Range 失败：position=" + mPosition
                    + " offset=" + tailStart
                    + " bytes=" + tailRead + "/" + expectedTailBytes
                    + " costMs=" + (SystemClock.elapsedRealtime() - tailStartedAt)
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
        mTailCanceled = new AtomicBoolean();
        // 使用 execute 保留原始 Runnable，便于拒绝处理器清理对应 URL 的 in-flight 标记。
        executorService.execute(this);
    }

    /**
     * 取消预加载任务。
     * <p>除了置标志位，还会 {@link HttpURLConnection#disconnect()} 打断阻塞中的 read，
     * 让线程尽快释放，否则要等读超时（5s）才退出。
     */
    void cancel() {
        mIsCanceled = true;
        AtomicBoolean canceled = mTailCanceled;
        canceled.set(true);
        HttpURLConnection connection = mConnection;
        if (connection != null) {
            connection.disconnect();
        }
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
