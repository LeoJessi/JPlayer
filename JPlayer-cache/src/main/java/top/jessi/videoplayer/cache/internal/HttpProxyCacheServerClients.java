package top.jessi.videoplayer.cache.internal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import top.jessi.videoplayer.cache.file.FileCache;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;
import top.jessi.videoplayer.cache.source.Source;
import top.jessi.videoplayer.cache.CacheListener;
import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.ProxyCacheException;

/**
 * Client for {@link HttpProxyCacheServer}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public final class HttpProxyCacheServerClients {

    private final AtomicInteger clientsCount = new AtomicInteger(0);
    private final String url;
    private volatile HttpProxyCache proxyCache;
    private final List<CacheListener> listeners = new CopyOnWriteArrayList<>();
    private final CacheListener uiCacheListener;
    private final Config config;

    public HttpProxyCacheServerClients(String url, Config config) {
        this.url = checkNotNull(url);
        this.config = checkNotNull(config);
        this.uiCacheListener = new UiListenerHandler(url, listeners);
    }

    public void processRequest(GetRequest request, Socket socket) throws ProxyCacheException, IOException {
        startProcessRequest();
        try {
            proxyCache.processRequest(request, socket);
        } finally {
            finishProcessRequest();
        }
    }

    /**
     * 建立（或复用）代理缓存，并把客户端计数 +1。
     * <p><b>计数必须在同步块内自增</b>：{@link #finishProcessRequest()} 也是 synchronized 的，
     * 计数归零时会把 {@code proxyCache} 置为 {@code null}。若自增留在锁外，
     * 线程 A 建好 proxyCache 出锁后、尚未自增即被抢占，线程 B 跑完并把 proxyCache 置空，
     * A 恢复后就会在 {@code proxyCache.processRequest()} 上抛 NPE
     * （{@code HttpProxyCacheServer#processSocket} 只捕获 SocketException /
     * ProxyCacheException / IOException，捕获不到 NullPointerException）。
     * <p>把自增挪进来之后，{@code clientsCount > 0} 与 {@code proxyCache != null}
     * 成为同一个临界区维护的不变量。
     */
    private synchronized void startProcessRequest() throws ProxyCacheException {
        proxyCache = proxyCache == null ? newHttpProxyCache() : proxyCache;
        clientsCount.incrementAndGet();
    }

    private synchronized void finishProcessRequest() {
        if (clientsCount.decrementAndGet() <= 0) {
            proxyCache.shutdown();
            proxyCache = null;
        }
    }

    public void registerCacheListener(CacheListener cacheListener) {
        listeners.add(cacheListener);
    }

    public void unregisterCacheListener(CacheListener cacheListener) {
        listeners.remove(cacheListener);
    }

    public void shutdown() {
        listeners.clear();
        if (proxyCache != null) {
            proxyCache.registerCacheListener(null);
            proxyCache.shutdown();
            proxyCache = null;
        }
        clientsCount.set(0);
    }

    public int getClientsCount() {
        return clientsCount.get();
    }

    private HttpProxyCache newHttpProxyCache() throws ProxyCacheException {
        Source source = config.sourceFactory.create(url, config.sourceInfoStorage, config.headerInjector);
        FileCache cache = new FileCache(config.generateCacheFile(url), config.diskUsage);
        HttpProxyCache httpProxyCache = new HttpProxyCache(source, cache, config.sourceFactory);
        httpProxyCache.registerCacheListener(uiCacheListener);
        return httpProxyCache;
    }

    private static final class UiListenerHandler extends Handler implements CacheListener {

        private final String url;
        private final List<CacheListener> listeners;

        public UiListenerHandler(String url, List<CacheListener> listeners) {
            super(Looper.getMainLooper());
            this.url = url;
            this.listeners = listeners;
        }

        @Override
        public void onCacheAvailable(File file, String url, int percentsAvailable) {
            Message message = obtainMessage();
            message.arg1 = percentsAvailable;
            message.obj = file;
            sendMessage(message);
        }

        @Override
        public void handleMessage(Message msg) {
            for (CacheListener cacheListener : listeners) {
                cacheListener.onCacheAvailable((File) msg.obj, url, msg.arg1);
            }
        }
    }
}
