package top.jessi.videoplayer.cache.preload;

import android.content.Context;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;

/**
 * 抖音式短视频预加载工具，基于 {@link HttpProxyCacheServer} 实现。
 * <p>
 * 原理：主动请求 VideoCache 生成的代理地址，触发其缓存机制，缓存到
 * {@link #PRELOAD_LENGTH} 的数据后停止请求。播放器随后请求同一代理地址时会直接命中缓存，
 * 从而提升起播速度。
 * <p>
 * 典型用法（列表场景）：
 * <pre><code>
 * PreloadManager preload = PreloadManager.getInstance(context);
 * preload.addPreloadTask(url, position);      // 列表滚动时调用
 * preload.pausePreload(position, reverse);    // 反向滑动时调用
 * preload.resumePreload(position, reverse);   // 正向滑动时调用
 * String playUrl = preload.getPlayUrl(url);    // 点击播放时调用
 * </code></pre>
 */
public class PreloadManager {

    private static PreloadManager sPreloadManager;

    /**
     * 单线程池，按照添加顺序依次执行 {@link PreloadTask}
     */
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    /**
     * 保存正在预加载的 {@link PreloadTask}
     */
    private final LinkedHashMap<String, PreloadTask> mPreloadTasks = new LinkedHashMap<>();

    /**
     * 标识是否需要预加载
     */
    private boolean mIsStartPreload = true;

    private final HttpProxyCacheServer mHttpProxyCacheServer;

    /**
     * 预加载的大小，每个视频预加载 20MB，这个参数可根据实际情况调整
     */
    public static final int PRELOAD_LENGTH = 1024 * 1024 * 20;

    private PreloadManager(Context context) {
        mHttpProxyCacheServer = ProxyVideoCacheManager.getProxy(context);
    }

    public static PreloadManager getInstance(Context context) {
        if (sPreloadManager == null) {
            synchronized (PreloadManager.class) {
                if (sPreloadManager == null) {
                    sPreloadManager = new PreloadManager(context.getApplicationContext());
                }
            }
        }
        return sPreloadManager;
    }

    /**
     * 删除指定 url 的缓存文件
     *
     * @param url 原始视频地址
     * @return 是否删除成功
     */
    public boolean deleteCacheFile(String url) {
        try {
            File file = mHttpProxyCacheServer.getCacheFile(url);
            if (file.exists()) {
                return file.delete();
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 开始预加载
     *
     * @param rawUrl 原始视频地址
     */
    public void addPreloadTask(String rawUrl, int position) {
        if (isPreloaded(rawUrl)) return;
        PreloadTask task = new PreloadTask();
        task.mRawUrl = rawUrl;
        task.mPosition = position;
        task.mCacheServer = mHttpProxyCacheServer;
        Logger.info("addPreloadTask: " + position);
        mPreloadTasks.put(rawUrl, task);
        if (mIsStartPreload) {
            //开始预加载
            task.executeOn(mExecutorService);
        }
    }

    /**
     * 判断该播放地址是否已经预加载
     */
    private boolean isPreloaded(String rawUrl) {
        //先判断是否有缓存文件，如果已经存在缓存文件，并且其大小大于1KB，则表示已经预加载完成了
        File cacheFile = mHttpProxyCacheServer.getCacheFile(rawUrl);
        if (cacheFile.exists()) {
            if (cacheFile.length() >= 1024) {
                return true;
            } else {
                //这种情况一般是缓存出错，把缓存删掉，重新缓存
                cacheFile.delete();
                return false;
            }
        }
        //再判断是否有临时缓存文件，如果已经存在临时缓存文件，并且临时缓存文件超过了预加载大小，则表示已经预加载完成了
        File tempCacheFile = mHttpProxyCacheServer.getTempCacheFile(rawUrl);
        if (tempCacheFile.exists()) {
            return tempCacheFile.length() >= PRELOAD_LENGTH;
        }
        return false;
    }

    /**
     * 暂停预加载
     * 根据是否反向滑动取消在position之下或之上的PreloadTask
     *
     * @param position        当前滑到的位置
     * @param isReverseScroll 列表是否反向滑动
     */
    public void pausePreload(int position, boolean isReverseScroll) {
        Logger.debug("pausePreload：" + position + " isReverseScroll: " + isReverseScroll);
        mIsStartPreload = false;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            PreloadTask task = next.getValue();
            if (isReverseScroll) {
                if (task.mPosition >= position) {
                    task.cancel();
                }
            } else {
                if (task.mPosition <= position) {
                    task.cancel();
                }
            }
        }
    }

    /**
     * 恢复预加载
     * 根据是否反向滑动开始在position之下或之上的PreloadTask
     *
     * @param position        当前滑到的位置
     * @param isReverseScroll 列表是否反向滑动
     */
    public void resumePreload(int position, boolean isReverseScroll) {
        Logger.debug("resumePreload：" + position + " isReverseScroll: " + isReverseScroll);
        mIsStartPreload = true;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            PreloadTask task = next.getValue();
            if (isReverseScroll) {
                if (task.mPosition < position) {
                    if (!isPreloaded(task.mRawUrl)) {
                        task.executeOn(mExecutorService);
                    }
                }
            } else {
                if (task.mPosition > position) {
                    if (!isPreloaded(task.mRawUrl)) {
                        task.executeOn(mExecutorService);
                    }
                }
            }
        }
    }

    /**
     * 继续执行预加载，边下载边播放
     *
     * @param position        当前滑到的位置
     * @param isReverseScroll 列表是否反向滑动
     */
    public void continuePreLoad(int position, boolean isReverseScroll) {
        Logger.debug("continuePreLoad：" + position + " isReverseScroll: " + isReverseScroll);
        mIsStartPreload = true;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            PreloadTask task = next.getValue();
            if (isReverseScroll) {
                if (task.mPosition < position) {
                    if (!isPreloaded(task.mRawUrl)) {
                        task.executeOn(mExecutorService);
                    }
                }
            } else {
                if (task.mPosition > position) {
                    if (!isPreloaded(task.mRawUrl)) {
                        task.executeOn(mExecutorService);
                    }
                }
            }
        }
    }

    /**
     * 通过原始地址取消预加载
     *
     * @param rawUrl 原始地址
     */
    public void removePreloadTask(String rawUrl) {
        PreloadTask task = mPreloadTasks.get(rawUrl);
        if (task != null) {
            task.cancel();
            mPreloadTasks.remove(rawUrl);
        }
    }

    /**
     * 取消所有的预加载
     */
    public void removeAllPreloadTask() {
        Iterator<Map.Entry<String, PreloadTask>> iterator = mPreloadTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PreloadTask> next = iterator.next();
            PreloadTask task = next.getValue();
            task.cancel();
            iterator.remove();
        }
    }

    /**
     * 停止所有的预缓存任务（保留任务记录，仅取消执行）
     */
    public void stopAllPreloadTask() {
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            next.getValue().cancel();
        }
    }

    /**
     * 获取播放地址：优先返回代理地址，让播放器复用已预加载的缓存。
     */
    public String getPlayUrl(String rawUrl) {
        PreloadTask task = mPreloadTasks.get(rawUrl);
        if (task != null) {
            task.cancel();
        }
        Logger.debug("getPlayUrl: isPreloaded=" + isPreloaded(rawUrl));
        if (isPreloaded(rawUrl)) {
            return mHttpProxyCacheServer.getProxyUrl(rawUrl);
        } else {
            return rawUrl;
        }
    }
}
