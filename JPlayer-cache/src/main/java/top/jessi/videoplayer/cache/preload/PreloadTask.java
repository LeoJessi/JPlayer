package top.jessi.videoplayer.cache.preload;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 原理：主动去请求 VideoCache 生成的代理地址，触发 VideoCache 缓存机制
 * 缓存到 {@link #mPreloadLength} 的数据之后停止请求，完成预加载
 * 播放器去播放 VideoCache 生成的代理地址的时候，VideoCache 会直接返回缓存数据，
 * 从而提升播放速度。
 */
public class PreloadTask implements Runnable {

    /**
     * 原始地址
     */
    public String mRawUrl;

    /**
     * 列表中的位置
     */
    public int mPosition;

    /**
     * VideoCache 服务器
     */
    public HttpProxyCacheServer mCacheServer;

    /**
     * 需要预加载的字节数，由 {@link PreloadManager} 在创建任务时注入
     */
    public long mPreloadLength = PreloadManager.DEFAULT_PRELOAD_LENGTH;

    /**
     * 预加载结果回调，由 {@link PreloadManager} 注入
     */
    public OnPreloadListener mListener;

    /**
     * 预加载结果回调。
     */
    public interface OnPreloadListener {

        /**
         * @param url        原始地址
         * @param preloaded  {@code true} 表示已读满 {@link #mPreloadLength}，缓存已就绪
         */
        void onPreloadFinished(String url, boolean preloaded);
    }

    /**
     * 是否被取消。
     * <p>必须为 {@code volatile}：预加载线程读取、调度/主线程写入，
     * 否则取消指令可能长时间不可见，导致任务无法及时停止。
     */
    private volatile boolean mIsCanceled;

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
    public static boolean isBlackListed(String url) {
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
        boolean preloaded = false;
        if (!mIsCanceled) {
            preloaded = start();
        }
        if (mListener != null) {
            mListener.onPreloadFinished(mRawUrl, preloaded);
        }
    }

    /**
     * 开始预加载
     *
     * @return 是否读满了 {@link #mPreloadLength}
     */
    private boolean start() {
        // 如果在小黑屋里不加载
        if (isBlackListed(mRawUrl)) return false;
        Logger.info("预加载开始：" + mPosition);
        HttpURLConnection connection = null;
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
                return false;
            }
            URL url = new URL(proxyUrl);
            // 只预加载 http/https 资源，本地或非常规协议无法走 HttpURLConnection
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                Logger.info("预加载跳过（不支持的协议 " + protocol + "）：" + mPosition);
                return false;
            }
            connection = (HttpURLConnection) url.openConnection();
            // 先设好超时再暴露引用，保证 cancel() 一旦看到 mConnection，超时就绪
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            mConnection = connection;
            // 取消指令可能恰好在 openConnection() 与 mConnection 赋值之间到达，这里补一次检查
            if (mIsCanceled) {
                connection.disconnect();
                return false;
            }
            InputStream in = new BufferedInputStream(connection.getInputStream());
            int length;
            long read = 0;
            byte[] bytes = new byte[8 * 1024];
            while ((length = in.read(bytes)) != -1) {
                read += length;
                //被取消
                if (mIsCanceled) {
                    Logger.info("预加载取消：" + mPosition + " 读取数据：" + read + " Byte");
                    return false;
                }
                //预加载完成
                if (read >= mPreloadLength) {
                    Logger.info("预加载成功：" + mPosition + " 读取数据：" + read + " Byte");
                    return true;
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
        return false;
    }

    /**
     * 将预加载任务提交到线程池，准备执行。
     * <p>重新提交时会清除取消标记，保证任务不会因为上一次被取消而永远无法执行。
     * <p>不去重：调用方（{@link PreloadManager} 的调度线程）通过 {@code mInFlight}
     * 保证同一 url 不会被重复提交。这里若再加一层基于任务状态的守卫，
     * 一旦线程池因队列已满而丢弃任务，标记将无法复位，导致该 url 之后永久不再预加载。
     */
    public void executeOn(ExecutorService executorService) {
        mIsCanceled = false;
        executorService.submit(this);
    }

    /**
     * 取消预加载任务。
     * <p>除了置标志位，还会 {@link HttpURLConnection#disconnect()} 打断阻塞中的 read，
     * 让线程尽快释放，否则要等读超时（5s）才退出。
     */
    public void cancel() {
        mIsCanceled = true;
        HttpURLConnection connection = mConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }
}
