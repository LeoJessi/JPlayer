package top.jessi.videoplayer.cache.source;

import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.TrustManager;

/**
 * {@link SourceFactory} creating {@link HttpUrlSource} instances, backed by {@link java.net.HttpURLConnection}.
 * <p>
 * This is the default source factory.
 */
public class HttpUrlSourceFactory implements SourceFactory {

    private final HostnameVerifier hostnameVerifier;
    private final TrustManager[] trustAllCerts;

    public HttpUrlSourceFactory(HostnameVerifier hostnameVerifier, TrustManager[] trustAllCerts) {
        this.hostnameVerifier = hostnameVerifier;
        this.trustAllCerts = trustAllCerts;
    }

    @Override
    public Source create(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        return new HttpUrlSource(url, sourceInfoStorage, headerInjector, hostnameVerifier, trustAllCerts);
    }

    @Override
    public Source copy(Source source) {
        return new HttpUrlSource((HttpUrlSource) source);
    }
}
