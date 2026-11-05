package top.jessi.videoplayer.cache.internal;

import top.jessi.videoplayer.cache.file.DiskUsage;
import top.jessi.videoplayer.cache.file.FileNameGenerator;
import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;

import java.io.File;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.TrustManager;
import top.jessi.videoplayer.cache.source.SourceFactory;

/**
 * Configuration for proxy cache.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class Config {

    public final File cacheRoot;
    public final FileNameGenerator fileNameGenerator;
    public final DiskUsage diskUsage;
    public final SourceInfoStorage sourceInfoStorage;
    public final HeaderInjector headerInjector;
    public final HostnameVerifier hostnameVerifier;
    public final TrustManager[] trustAllCerts;
    public final SourceFactory sourceFactory;
    /** 本地代理 socket 的读超时（毫秒），{@code 0} 表示不设置。 */
    public final int socketTimeoutMs;

    public Config(File cacheRoot, FileNameGenerator fileNameGenerator, DiskUsage diskUsage, SourceInfoStorage sourceInfoStorage,
           HeaderInjector headerInjector, HostnameVerifier hostnameVerifier, TrustManager[] trustAllCerts,
           SourceFactory sourceFactory, int socketTimeoutMs) {
        this.cacheRoot = cacheRoot;
        this.fileNameGenerator = fileNameGenerator;
        this.diskUsage = diskUsage;
        this.sourceInfoStorage = sourceInfoStorage;
        this.headerInjector = headerInjector;
        this.hostnameVerifier = hostnameVerifier;
        this.trustAllCerts = trustAllCerts;
        this.sourceFactory = sourceFactory;
        this.socketTimeoutMs = socketTimeoutMs;
    }

    File generateCacheFile(String url) {
        String name = fileNameGenerator.generate(url);
        return new File(cacheRoot, name);
    }

}
