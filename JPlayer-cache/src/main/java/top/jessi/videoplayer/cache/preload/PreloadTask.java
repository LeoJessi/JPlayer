package top.jessi.videoplayer.cache.preload;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 原理：主动去请求 VideoCache 生成的代理地址，触发 VideoCache 缓存机制
 * 缓存到 {@link PreloadManager#PRELOAD_LENGTH} 的数据之后停止请求，完成预加载
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
     * 是否被取消
     */
    private boolean mIsCanceled;

    /**
     * 是否正在预加载
     */
    private boolean mIsExecuted;

    /**
     * 预加载失败过的 url（"小黑屋"），避免反复请求坏源
     */
    private static final List<String> BLACK_LIST = new ArrayList<>();

    @Override
    public void run() {
        if (!mIsCanceled) {
            start();
        }
        mIsExecuted = false;
        mIsCanceled = false;
    }

    /**
     * 开始预加载
     */
    private void start() {
        // 如果在小黑屋里不加载
        if (BLACK_LIST.contains(mRawUrl)) return;
        Logger.info("预加载开始：" + mPosition);
        HttpURLConnection connection = null;
        try {
            //获取 HttpProxyCacheServer 的代理地址
            String proxyUrl = mCacheServer.getProxyUrl(mRawUrl);
            URL url = new URL(proxyUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            InputStream in = new BufferedInputStream(connection.getInputStream());
            int length;
            int read = -1;
            byte[] bytes = new byte[8 * 1024];
            while ((length = in.read(bytes)) != -1) {
                read += length;
                //预加载完成或者取消预加载
                if (mIsCanceled || read >= PreloadManager.PRELOAD_LENGTH) {
                    if (mIsCanceled) {
                        Logger.info("预加载取消：" + mPosition + " 读取数据：" + read + " Byte");
                    } else {
                        Logger.info("预加载成功：" + mPosition + " 读取数据：" + read + " Byte");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Logger.info("预加载异常：" + mPosition + " 异常信息：" + e.getMessage());
            // 关入小黑屋
            BLACK_LIST.add(mRawUrl);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            Logger.info("预加载结束: " + mPosition);
        }
    }

    /**
     * 将预加载任务提交到线程池，准备执行
     */
    public void executeOn(ExecutorService executorService) {
        if (mIsExecuted) return;
        mIsExecuted = true;
        executorService.submit(this);
    }

    /**
     * 取消预加载任务
     */
    public void cancel() {
        if (mIsExecuted) {
            mIsCanceled = true;
        }
    }
}
