package top.jessi.videoplayer.cache;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;

import top.jessi.videoplayer.cache.file.DiskUsage;
import top.jessi.videoplayer.cache.file.FileNameGenerator;
import top.jessi.videoplayer.cache.file.Md5FileNameGenerator;
import top.jessi.videoplayer.cache.file.TotalCountLruDiskUsage;
import top.jessi.videoplayer.cache.file.TotalSizeLruDiskUsage;
import top.jessi.videoplayer.cache.headers.EmptyHeadersInjector;
import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorageFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.TrustManager;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkAllNotNull;
import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;
import top.jessi.videoplayer.cache.source.SourceFactory;
import top.jessi.videoplayer.cache.source.HttpUrlSourceFactory;
import top.jessi.videoplayer.cache.source.OkHttpSourceFactory;
import top.jessi.videoplayer.cache.internal.Config;
import top.jessi.videoplayer.cache.internal.GetRequest;
import top.jessi.videoplayer.cache.internal.Pinger;
import top.jessi.videoplayer.cache.internal.HttpProxyCacheServerClients;
import top.jessi.videoplayer.cache.internal.IgnoreHostProxySelector;
import top.jessi.videoplayer.cache.internal.ProxyCacheUtils;
import top.jessi.videoplayer.cache.internal.StorageUtils;
import top.jessi.videoplayer.cache.internal.Logger;

/**
 * Simple lightweight proxy server with file caching support that handles HTTP requests.
 * Typical usage:
 * <pre><code>
 * public onCreate(Bundle state) {
 *      super.onCreate(state);
 *
 *      HttpProxyCacheServer proxy = getProxy();
 *      String proxyUrl = proxy.getProxyUrl(VIDEO_URL);
 *      videoView.setVideoPath(proxyUrl);
 * }
 *
 * private HttpProxyCacheServer getProxy() {
 * // should return single instance of HttpProxyCacheServer shared for whole app.
 * }
 * </code></pre>
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class HttpProxyCacheServer {

    private static final String PROXY_HOST = "127.0.0.1";

    private static volatile HttpProxyCacheServer sInstance;

    private final Object clientsLock = new Object();
    private final ExecutorService socketProcessor = Executors.newFixedThreadPool(8);
    private final Map<String, HttpProxyCacheServerClients> clientsMap = new ConcurrentHashMap<>();
    private final ServerSocket serverSocket;
    private final int port;
    private final Thread waitConnectionThread;
    private final Config config;
    private final Pinger pinger;
    private volatile boolean mShutdown;

    public HttpProxyCacheServer(Context context) {
        this(new Builder(context).buildConfig());
    }

    /**
     * Returns the process-wide shared instance, creating it on first call.
     * <p>
     * Use this instead of building multiple {@link HttpProxyCacheServer} instances:
     * each instance opens its own local port and may use a different cache directory,
     * which makes preload and playback hit different caches.
     *
     * @param context any context; its application context is used.
     * @return the shared {@link HttpProxyCacheServer} instance.
     */
    public static HttpProxyCacheServer getInstance(Context context) {
        if (sInstance == null) {
            synchronized (HttpProxyCacheServer.class) {
                if (sInstance == null) {
                    sInstance = new Builder(context.getApplicationContext()).build();
                }
            }
        }
        return sInstance;
    }

    private HttpProxyCacheServer(Config config) {
        this.config = checkNotNull(config);
        try {
            InetAddress inetAddress = InetAddress.getByName(PROXY_HOST);
            this.serverSocket = new ServerSocket(0, 8, inetAddress);
            this.port = serverSocket.getLocalPort();
            IgnoreHostProxySelector.install(PROXY_HOST, port);
            this.pinger = new Pinger(PROXY_HOST, port);
            CountDownLatch startSignal = new CountDownLatch(1);
            this.waitConnectionThread = new Thread(new WaitRequestsRunnable(startSignal));
            this.waitConnectionThread.start();
            startSignal.await(); // freeze thread, wait for server starts
        } catch (IOException | InterruptedException e) {
            socketProcessor.shutdown();
            throw new IllegalStateException("Error starting local proxy server", e);
        }
    }

    /**
     * Returns url that wrap original url and should be used for client (MediaPlayer, ExoPlayer, etc).
     * <p>
     * If file for this url is fully cached (it means method {@link #isCached(String)} returns {@code true})
     * then file:// uri to cached file will be returned.
     * <p>
     * Calling this method has same effect as calling {@link #getProxyUrl(String, boolean)} with 2nd parameter set to {@code true}.
     *
     * @param url a url to file that should be cached.
     * @return a wrapped by proxy url if file is not fully cached or url pointed to cache file otherwise.
     */
    public String getProxyUrl(String url) {
        return getProxyUrl(url, true);
    }

    /**
     * Returns url that wrap original url and should be used for client (MediaPlayer, ExoPlayer, etc).
     * <p>
     * If parameter {@code allowCachedFileUri} is {@code true} and file for this url is fully cached
     * (it means method {@link #isCached(String)} returns {@code true}) then file:// uri to cached file will be returned.
     *
     * @param url                a url to file that should be cached.
     * @param allowCachedFileUri {@code true} if allow to return file:// uri if url is fully cached
     * @return a wrapped by proxy url if file is not fully cached or url pointed to cache file otherwise (if {@code allowCachedFileUri} is {@code true}).
     */
    public String getProxyUrl(String url, boolean allowCachedFileUri) {
        if (allowCachedFileUri && isCached(url)) {
            File cacheFile = getCacheFile(url);
            touchFileSafely(cacheFile);
            return Uri.fromFile(cacheFile).toString();
        }
        // 服务端构造完成后已经监听本地端口，避免每次获取播放地址都同步 Ping。
        return !mShutdown && !serverSocket.isClosed() ? appendToProxyUrl(url) : url;
    }

    public void registerCacheListener(CacheListener cacheListener, String url) {
        checkAllNotNull(cacheListener, url);
        synchronized (clientsLock) {
            try {
                getClients(url).registerCacheListener(cacheListener);
            } catch (ProxyCacheException e) {
                Logger.warn("Error registering cache listener");
            }
        }
    }

    public void unregisterCacheListener(CacheListener cacheListener, String url) {
        checkAllNotNull(cacheListener, url);
        synchronized (clientsLock) {
            try {
                getClients(url).unregisterCacheListener(cacheListener);
            } catch (ProxyCacheException e) {
                Logger.warn("Error registering cache listener");
            }
        }
    }

    public void unregisterCacheListener(CacheListener cacheListener) {
        checkNotNull(cacheListener);
        synchronized (clientsLock) {
            for (HttpProxyCacheServerClients clients : clientsMap.values()) {
                clients.unregisterCacheListener(cacheListener);
            }
        }
    }

    /**
     * Checks is cache contains fully cached file for particular url.
     *
     * @param url an url cache file will be checked for.
     * @return {@code true} if cache contains fully cached file for passed in parameters url.
     */
    public boolean isCached(String url) {
        checkNotNull(url, "Url can't be null!");
        return getCacheFile(url).exists();
    }

    public void shutdown() {
        if (mShutdown) {
            return;
        }
        mShutdown = true;
        Logger.info("Shutdown proxy server");

        shutdownClients();

        config.sourceInfoStorage.release();

        waitConnectionThread.interrupt();
        try {
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            onError(new ProxyCacheException("Error shutting down proxy server", e));
        } finally {
            // 关闭尚未开始处理的 accepted socket，避免 shutdownNow 丢弃任务时泄漏 socket。
            for (Runnable pending : socketProcessor.shutdownNow()) {
                if (pending instanceof SocketProcessorRunnable) {
                    releaseSocket(((SocketProcessorRunnable) pending).socket);
                }
            }
            config.diskUsage.shutdown();
            pinger.shutdown();
        }
    }

    private String appendToProxyUrl(String url) {
        return String.format(Locale.US, "http://%s:%d/%s", PROXY_HOST, port, ProxyCacheUtils.encode(url));
    }

    public File getCacheFile(String url) {
        File cacheDir = config.cacheRoot;
        String fileName = config.fileNameGenerator.generate(url);
        return new File(cacheDir, fileName);
    }

    public File getTempCacheFile(String url) {
        File cacheDir = config.cacheRoot;
        String fileName = config.fileNameGenerator.generate(url) + ".download";
        return new File(cacheDir, fileName);
    }

    public File getCacheRoot() {
        return config.cacheRoot;
    }

    /**
     * 直接从源站读取指定 Range 并写入该 URL 的共享稀疏缓存。
     * <p>与代理播放的顺序 SourceReader 分开，避免尾部 moov Range 排在头部下载之后。
     */
    public long preloadRange(String url, long offset, long maxBytes, AtomicBoolean canceled)
            throws ProxyCacheException {
        checkNotNull(url);
        checkNotNull(canceled);
        if (offset < 0 || maxBytes <= 0 || mShutdown || canceled.get()) {
            return 0;
        }
        return getClients(url).preloadRange(offset, maxBytes, canceled);
    }

    /**
     * 取消指定 URL 正在执行的独立 Range 缓存任务。
     */
    public void cancelRangePreload(String url) {
        if (url == null) {
            return;
        }
        HttpProxyCacheServerClients clients = clientsMap.get(url);
        if (clients != null) {
            clients.cancelRangePreload();
        }
    }

    /**
     * Returns total disk space (in bytes) currently occupied by cache.
     * <p>
     * Both completed cache files and unfinished {@code .download} temp files are counted.
     * The result is a real-time snapshot.
     *
     * @return total size in bytes of all cache files, or {@code 0} if the cache directory is missing.
     * @throws IllegalStateException if called on the main thread, because this method performs disk IO.
     */
    public long getCacheSize() {
        checkNotMainThread();
        return countDirSize(config.cacheRoot);
    }

    /**
     * Deletes all cache files, including unfinished {@code .download} temp files.
     * <p>
     * <b>Note:</b> this only removes files from the cache directory. It does not stop
     * any in-flight download. When preload is in use, callers should also cancel
     * preload tasks (see {@code PreloadManager#clearAllCache()}), otherwise those
     * tasks keep writing into the deleted {@code .download} files.
     *
     * @return {@code true} if all cache files were deleted successfully.
     */
    public boolean clearAllCache() {
        return StorageUtils.deleteFiles(config.cacheRoot);
    }

    /**
     * Deletes both the completed cache file and the unfinished {@code .download}
     * temp file for the given url.
     *
     * @param url the original video url.
     * @return {@code true} if all related files were deleted successfully.
     */
    public boolean clearCacheFile(String url) {
        File tempFile = getTempCacheFile(url);
        File cacheFile = getCacheFile(url);
        File tempMetaFile = new File(tempFile.getParentFile(), tempFile.getName() + ".meta");
        File cacheMetaFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta");
        // 两个文件都尝试删除，避免因其中一个失败而漏删另一个
        boolean deletedTemp = StorageUtils.deleteFile(tempFile.getAbsolutePath());
        boolean deletedCache = StorageUtils.deleteFile(cacheFile.getAbsolutePath());
        boolean deletedTempMeta = StorageUtils.deleteFile(tempMetaFile.getAbsolutePath());
        boolean deletedCacheMeta = StorageUtils.deleteFile(cacheMetaFile.getAbsolutePath());
        return deletedTemp && deletedCache && deletedTempMeta && deletedCacheMeta;
    }

    private static void checkNotMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("getCacheSize() performs disk IO and must not be called on the main thread");
        }
    }

    private static long countDirSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    private void touchFileSafely(File cacheFile) {
        try {
            config.diskUsage.touch(cacheFile);
        } catch (IOException e) {
            Logger.error("Error touching file " + cacheFile);
        }
    }

    private void shutdownClients() {
        synchronized (clientsLock) {
            for (HttpProxyCacheServerClients clients : clientsMap.values()) {
                clients.shutdown();
            }
            clientsMap.clear();
        }
    }

    private void waitForRequest() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                Logger.debug("Accept new socket " + socket);
                socketProcessor.submit(new SocketProcessorRunnable(socket));
            }
        } catch (IOException e) {
            onError(new ProxyCacheException("Error during waiting connection", e));
        }
    }

    private void processSocket(Socket socket) {
        try {
            GetRequest request = GetRequest.read(socket.getInputStream());
            Logger.debug("Request to cache proxy:" + request);
            String url = ProxyCacheUtils.decode(request.uri);
            if (pinger.isPingRequest(url)) {
                pinger.responseToPing(socket);
            } else {
                HttpProxyCacheServerClients clients = getClients(url);
                clients.processRequest(request, socket);
            }
        } catch (SocketException e) {
            // There is no way to determine that client closed connection http://stackoverflow.com/a/10241044/999458
            // So just to prevent log flooding don't log stacktrace
            Logger.debug("Closing socket… Socket is closed by client.");
        } catch (ProxyCacheException | IOException e) {
            onError(new ProxyCacheException("Error processing request", e));
        } finally {
            releaseSocket(socket);
            Logger.debug("Opened connections: " + getClientsCount());
        }
    }

    private HttpProxyCacheServerClients getClients(String url) throws ProxyCacheException {
        synchronized (clientsLock) {
            HttpProxyCacheServerClients clients = clientsMap.get(url);
            if (clients == null) {
                clients = new HttpProxyCacheServerClients(url, config);
                clientsMap.put(url, clients);
            }
            return clients;
        }
    }

    private int getClientsCount() {
        synchronized (clientsLock) {
            int count = 0;
            for (HttpProxyCacheServerClients clients : clientsMap.values()) {
                count += clients.getClientsCount();
            }
            return count;
        }
    }

    private void releaseSocket(Socket socket) {
        closeSocketInput(socket);
        closeSocketOutput(socket);
        closeSocket(socket);
    }

    private void closeSocketInput(Socket socket) {
        try {
            if (!socket.isInputShutdown()) {
                socket.shutdownInput();
            }
        } catch (SocketException e) {
            // There is no way to determine that client closed connection http://stackoverflow.com/a/10241044/999458
            // So just to prevent log flooding don't log stacktrace
            Logger.debug("Releasing input stream… Socket is closed by client.");
        } catch (IOException e) {
            onError(new ProxyCacheException("Error closing socket input stream", e));
        }
    }

    private void closeSocketOutput(Socket socket) {
        try {
            if (!socket.isOutputShutdown()) {
                socket.shutdownOutput();
            }
        } catch (IOException e) {
            Logger.warn("Failed to close socket on proxy side: {}. It seems client have already closed connection.");
        }
    }

    private void closeSocket(Socket socket) {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            onError(new ProxyCacheException("Error closing socket", e));
        }
    }

    private void onError(Throwable e) {
        Logger.error("HttpProxyCacheServer error");
    }

    private final class WaitRequestsRunnable implements Runnable {

        private final CountDownLatch startSignal;

        public WaitRequestsRunnable(CountDownLatch startSignal) {
            this.startSignal = startSignal;
        }

        @Override
        public void run() {
            startSignal.countDown();
            waitForRequest();
        }
    }

    private final class SocketProcessorRunnable implements Runnable {

        private final Socket socket;

        public SocketProcessorRunnable(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            processSocket(socket);
        }
    }

    /**
     * Builder for {@link HttpProxyCacheServer}.
     */
    public static final class Builder {

        private static final long DEFAULT_MAX_SIZE = 512 * 1024 * 1024;
        private static final long DEFAULT_SOURCE_TIMEOUT_MS = 10_000L;

        private File cacheRoot;
        private FileNameGenerator fileNameGenerator;
        private DiskUsage diskUsage;
        private SourceInfoStorage sourceInfoStorage;
        private HeaderInjector headerInjector;
        private HostnameVerifier hostnameVerifier;
        private TrustManager[] trustAllCerts;
        private SourceFactory sourceFactory;
        private long sourceTimeoutMs = DEFAULT_SOURCE_TIMEOUT_MS;

        public Builder(Context context) {
            this.sourceInfoStorage = SourceInfoStorageFactory.newSourceInfoStorage(context);
            this.cacheRoot = StorageUtils.getIndividualCacheDirectory(context);
            this.diskUsage = new TotalSizeLruDiskUsage(DEFAULT_MAX_SIZE);
            this.fileNameGenerator = new Md5FileNameGenerator();
            this.headerInjector = new EmptyHeadersInjector();
        }

        /**
         * Overrides default cache folder to be used for caching files.
         * <p>
         * By default AndroidVideoCache uses
         * '/Android/data/[app_package_name]/cache/video-cache/' if card is mounted and app has appropriate permission
         * or 'video-cache' subdirectory in default application's cache directory otherwise.
         * </p>
         * <b>Note</b> directory must be used <b>only</b> for AndroidVideoCache files.
         *
         * @param file a cache directory, can't be null.
         * @return a builder.
         */
        public Builder cacheDirectory(File file) {
            this.cacheRoot = checkNotNull(file);
            return this;
        }

        /**
         * Overrides default cache file name generator {@link Md5FileNameGenerator} .
         *
         * @param fileNameGenerator a new file name generator.
         * @return a builder.
         */
        public Builder fileNameGenerator(FileNameGenerator fileNameGenerator) {
            this.fileNameGenerator = checkNotNull(fileNameGenerator);
            return this;
        }

        /**
         * Sets max cache size in bytes.
         * <p>
         * All files that exceeds limit will be deleted using LRU strategy.
         * Default value is 512 Mb.
         * </p>
         * Note this method overrides result of calling {@link #maxCacheFilesCount(int)}
         *
         * @param maxSize max cache size in bytes.
         * @return a builder.
         */
        public Builder maxCacheSize(long maxSize) {
            this.diskUsage = new TotalSizeLruDiskUsage(maxSize);
            return this;
        }

        /**
         * Sets max cache files count.
         * All files that exceeds limit will be deleted using LRU strategy.
         * Note this method overrides result of calling {@link #maxCacheSize(long)}
         *
         * @param count max cache files count.
         * @return a builder.
         */
        public Builder maxCacheFilesCount(int count) {
            this.diskUsage = new TotalCountLruDiskUsage(count);
            return this;
        }

        /**
         * Set custom DiskUsage logic for handling when to keep or clean cache.
         *
         * @param diskUsage a disk usage strategy, cant be {@code null}.
         * @return a builder.
         */
        public Builder diskUsage(DiskUsage diskUsage) {
            this.diskUsage = checkNotNull(diskUsage);
            return this;
        }

        /**
         * Add headers along the request to the server
         *
         * @param headerInjector to inject header base on url
         * @return a builder
         */
        public Builder headerInjector(HeaderInjector headerInjector) {
            this.headerInjector = checkNotNull(headerInjector);
            return this;
        }

        /**
         * Sets a custom {@link HostnameVerifier} for HTTPS connections.
         * <p>
         * Can be useful for servers using a self-signed or otherwise untrusted certificate.
         * Disabled by default.
         *
         * @param hostnameVerifier a hostname verifier, can be {@code null} to disable.
         * @return a builder.
         */
        public Builder hostnameVerifier(HostnameVerifier hostnameVerifier) {
            this.hostnameVerifier = hostnameVerifier;
            return this;
        }

        /**
         * Sets {@link TrustManager}s trusting any certificate for HTTPS connections.
         * <p>
         * <b>Note:</b> this is insecure and should only be used for servers with a self-signed
         * or otherwise untrusted certificate.
         * Disabled by default.
         *
         * @param trustAllCerts trust managers, can be {@code null} to disable.
         * @return a builder.
         */
        public Builder trustAllCerts(TrustManager[] trustAllCerts) {
            this.trustAllCerts = trustAllCerts;
            return this;
        }

        /**
         * Sets the {@link SourceFactory} used to read remote content.
         * <p>
         * By default {@link HttpUrlSourceFactory} is used. Pass {@link OkHttpSourceFactory} to read
         * content with OkHttp instead (requires the {@code com.squareup.okhttp3:okhttp} dependency).
         * <p>
         * Note: if this method is not called, the {@link #hostnameVerifier} and {@link #trustAllCerts}
         * settings are applied to the default {@link HttpUrlSourceFactory}.
         *
         * @param sourceFactory a source factory, can't be {@code null}.
         * @return a builder.
         */
        public Builder sourceFactory(SourceFactory sourceFactory) {
            this.sourceFactory = checkNotNull(sourceFactory);
            return this;
        }

        /**
         * Sets the timeout (in milliseconds) for network connections opened by the default
         * {@link HttpUrlSourceFactory}.
         * <p>
         * Default value is {@value #DEFAULT_SOURCE_TIMEOUT_MS} ms.
         * <p>
         * <b>Note:</b> this setting only applies when the default {@link HttpUrlSourceFactory} is used.
         * If a custom {@link SourceFactory} is set via {@link #sourceFactory(SourceFactory)}, the timeout
         * must be configured inside that factory.
         *
         * @param timeoutMs timeout in milliseconds, {@code <= 0} keeps the current value.
         * @return a builder.
         */
        public Builder sourceTimeoutMs(long timeoutMs) {
            if (timeoutMs > 0) {
                this.sourceTimeoutMs = timeoutMs;
            }
            return this;
        }

        /**
         * Builds new instance of {@link HttpProxyCacheServer}.
         *
         * @return proxy cache. Only single instance should be used across whole app.
         */
        public HttpProxyCacheServer build() {
            Config config = buildConfig();
            return new HttpProxyCacheServer(config);
        }

        private Config buildConfig() {
            SourceFactory sourceFactory = this.sourceFactory != null ? this.sourceFactory
                    : new HttpUrlSourceFactory(hostnameVerifier, trustAllCerts, sourceTimeoutMs);
            return new Config(cacheRoot, fileNameGenerator, diskUsage, sourceInfoStorage, headerInjector,
                    hostnameVerifier, trustAllCerts, sourceFactory);
        }

    }
}
