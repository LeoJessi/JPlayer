package top.jessi.videoplayer.cache.preload;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;

import top.jessi.videoplayer.cache.internal.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * AndroidVideoCache + VideoPreLoader 实现短视频预加载（缓存本地 + 预加载）。
 * <p>
 * 与 {@link PreloadManager} 的区别：本类基于 {@link HandlerThread} 串行执行，使用
 * {@link PreLoaderBean} 作为入参，适合逐个 url 精细控制的场景；{@link PreloadManager} 基于
 * 线程池 + 列表位置，适合 RecyclerView 列表预加载。
 * <p>
 * 参考链接：https://www.jianshu.com/p/590c3f67d395、https://www.jianshu.com/p/4745de02dcdc
 */
public class VideoPreLoader {

    private VideoPreLoader() {
        handlerThread = new HandlerThread("VideoPreLoader_HandlerThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
            }
        };
    }

    private static VideoPreLoader instance = null;

    public static synchronized VideoPreLoader getInstance() {
        if (instance == null) {
            instance = new VideoPreLoader();
        }
        return instance;
    }

    private final Handler handler;
    private final HandlerThread handlerThread;
    private final List<String> cancelList = new ArrayList<>();

    /**
     * 当前正在播放的 url（预留，暂未启用冲突检测）
     */
    private String currentUrl = null;

    /**
     * 添加预加载路径
     *
     * @param data 预加载数据
     */
    public void addPreLoadUrl(final PreLoaderBean data) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                realPreload(data);
            }
        });
    }

    /**
     * 取消指定的预加载 url
     *
     * @param url 原始地址
     */
    public void cancelPreLoadURLIfNeeded(String url) {
        cancelList.add(url);
    }

    /**
     * 设置当前正在播放的 url
     *
     * @param url 当前正在播放的 url
     */
    public void setCurrentUrl(String url) {
        this.currentUrl = url;
    }

    /**
     * 取消所有的预加载 url
     */
    public void cancelAnyPreLoads() {
        handler.removeCallbacksAndMessages(null);
        cancelList.clear();
    }

    /**
     * 开始预加载：只读取 {@link PreLoaderBean#getPreLoadBytes()} 的数据后停止，避免全量下载。
     *
     * @param data 预加载数据
     */
    private void realPreload(PreLoaderBean data) {
        if (data == null || isCancel(data.getOriginalUrl())) {
            return;
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            // 此处为连接本地代理服务，不会执行真正的预加载动作。真正的预加载动作在 AndroidVideoCache 中执行
            URL mUrl = new URL(data.getProxyUrl());
            conn = (HttpURLConnection) mUrl.openConnection();
            conn.connect();
            is = conn.getInputStream();
            byte[] buffer = new byte[1024];
            int downloadSize = 0;
            int numRead;
            while ((numRead = is.read(buffer)) != -1) {
                downloadSize += numRead;
                if (downloadSize >= data.getPreLoadBytes()) {
                    break;
                }
            }
            Logger.info("预加载完成，已读取：" + downloadSize + " Byte");
        } catch (Exception e) {
            Logger.info("预加载异常：" + e.getMessage());
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * url 是否已经被取消
     */
    private boolean isCancel(String url) {
        if (TextUtils.isEmpty(url)) {
            return true;
        }
        for (String cancelUrl : cancelList) {
            if (cancelUrl.equals(url)) {
                return true;
            }
        }
        return false;
    }
}
