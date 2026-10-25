package top.jessi.videoplayer.cache.preload;

import android.content.Context;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.StorageUtils;

import java.io.File;

/**
 * {@link HttpProxyCacheServer} 的全局单例封装，供预加载模块与业务方共用同一个代理服务器。
 * <p>
 * 注意：被安全软件禁网后 127.0.0.1 的 getLocalPort 可能被限制权限而闪退；
 * 且 Android 9.0 之后默认不再支持 http，因此需要在 manifest 中加入
 * {@code android:usesCleartextTraffic="true"}。
 */
public final class ProxyVideoCacheManager {

    private static volatile HttpProxyCacheServer sharedProxy;

    private ProxyVideoCacheManager() {
    }

    /**
     * 获取全局唯一的代理服务器。
     * <p>加锁是因为 {@link PreloadManager#getInstance(Context)} 可能在任意线程调用，
     * 无锁的 double-check 在并发下可能创建出多个 server（各自监听一个端口），
     * 导致预加载与播放走到不同的缓存目录。
     */
    public static HttpProxyCacheServer getProxy(Context context) {
        if (sharedProxy == null) {
            synchronized (ProxyVideoCacheManager.class) {
                if (sharedProxy == null) {
                    sharedProxy = newProxy(context);
                }
            }
        }
        return sharedProxy;
    }

    private static HttpProxyCacheServer newProxy(Context context) {
        return new HttpProxyCacheServer.Builder(context)
                .maxCacheSize(512 * 1024 * 1024)       // 512MB for cache
                //缓存路径，不设置默认在 sd_card/Android/data/[app_package_name]/cache 中
//                .cacheDirectory()
                .build();
    }

    /**
     * 删除所有缓存文件
     *
     * @return 返回缓存是否删除成功
     */
    public static boolean clearAllCache(Context context) {
        getProxy(context);
        boolean deleted = StorageUtils.deleteFiles(sharedProxy.getCacheRoot());
        PreloadManager preloadManager = PreloadManager.getInstance(context);
        // 先取消在飞的任务，否则它们会继续往刚删掉的 .download 文件里写数据
        preloadManager.cancelAllTasks();
        // 缓存文件已删除，同步清空预加载状态，避免内存记录与磁盘不一致
        preloadManager.clearPreloadState();
        return deleted;
    }

    /**
     * 删除 url 对应默认缓存文件
     *
     * @return 返回缓存是否删除成功
     */
    public static boolean clearDefaultCache(Context context, String url) {
        getProxy(context);
        File pathTmp = sharedProxy.getTempCacheFile(url);
        File path = sharedProxy.getCacheFile(url);
        boolean deleted = StorageUtils.deleteFile(pathTmp.getAbsolutePath()) &&
                StorageUtils.deleteFile(path.getAbsolutePath());
        // 该 url 的预加载任务可能正在写这两个文件，一并取消并清空状态记录
        PreloadManager preloadManager = PreloadManager.getInstance(context);
        preloadManager.cancelTask(url);
        preloadManager.clearPreloadState();
        return deleted;
    }
}
