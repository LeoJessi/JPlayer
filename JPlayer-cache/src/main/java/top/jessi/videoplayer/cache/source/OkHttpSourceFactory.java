package top.jessi.videoplayer.cache.source;

import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.TrustManager;

/**
 * {@link SourceFactory} creating {@link OkHttpSource} instances, backed by OkHttp.
 * <p>
 * Requires the {@code com.squareup.okhttp3:okhttp} dependency.
 */
public class  OkHttpSourceFactory implements SourceFactory {

    private final HostnameVerifier hostnameVerifier;
    private final TrustManager[] trustAllCerts;

    public OkHttpSourceFactory(HostnameVerifier hostnameVerifier, TrustManager[] trustAllCerts) {
        this.hostnameVerifier = hostnameVerifier;
        this.trustAllCerts = trustAllCerts;
    }

    @Override
    public Source create(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        return new OkHttpSource(url, sourceInfoStorage, headerInjector, hostnameVerifier, trustAllCerts);
    }

    @Override
    public Source copy(Source source) {
        return new OkHttpSource((OkHttpSource) source);
    }
}
