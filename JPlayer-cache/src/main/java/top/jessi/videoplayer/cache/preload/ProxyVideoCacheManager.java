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

    private static HttpProxyCacheServer sharedProxy;

    private ProxyVideoCacheManager() {
    }

    public static HttpProxyCacheServer getProxy(Context context) {
        return sharedProxy == null ? (sharedProxy = newProxy(context)) : sharedProxy;
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
        return StorageUtils.deleteFiles(sharedProxy.getCacheRoot());
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
        return StorageUtils.deleteFile(pathTmp.getAbsolutePath()) &&
                StorageUtils.deleteFile(path.getAbsolutePath());
    }
}
