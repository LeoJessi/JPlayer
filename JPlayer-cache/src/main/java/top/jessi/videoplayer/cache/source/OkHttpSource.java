package top.jessi.videoplayer.cache.source;

import android.text.TextUtils;

import top.jessi.videoplayer.cache.headers.EmptyHeadersInjector;
import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorageFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;
import static top.jessi.videoplayer.cache.internal.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import top.jessi.videoplayer.cache.internal.ProxyCacheUtils;
import top.jessi.videoplayer.cache.internal.Logger;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfo;
import top.jessi.videoplayer.cache.ProxyCacheException;
import top.jessi.videoplayer.cache.InterruptedProxyCacheException;

/**
 * {@link Source} that uses OkHttp as a source for {@code ProxyCache}.
 * <p>
 * Selected via {@link OkHttpSourceFactory}. Behaves like {@link HttpUrlSource} but benefits from
 * OkHttp's connection pool and redirect handling.
 */
public class OkHttpSource implements Source {

    private static final int MAX_REDIRECTS = 5;

    private final OkHttpClient client;
    private final SourceInfoStorage sourceInfoStorage;
    private final HeaderInjector headerInjector;
    private final HostnameVerifier hostnameVerifier;
    private final TrustManager[] trustAllCerts;
    private SourceInfo sourceInfo;
    private InputStream inputStream;

    public OkHttpSource(String url) {
        this(url, SourceInfoStorageFactory.newEmptySourceInfoStorage());
    }

    public OkHttpSource(String url, SourceInfoStorage sourceInfoStorage) {
        this(url, sourceInfoStorage, new EmptyHeadersInjector());
    }

    public OkHttpSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        this(url, sourceInfoStorage, headerInjector, null, null);
    }

    public OkHttpSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector,
                        HostnameVerifier hostnameVerifier, TrustManager[] trustAllCerts) {
        this.sourceInfoStorage = checkNotNull(sourceInfoStorage);
        this.headerInjector = checkNotNull(headerInjector);
        this.hostnameVerifier = hostnameVerifier;
        this.trustAllCerts = trustAllCerts;
        this.client = new OkHttpClient();
        SourceInfo sourceInfo = sourceInfoStorage.get(url);
        this.sourceInfo = sourceInfo != null ? sourceInfo :
                new SourceInfo(url, Integer.MIN_VALUE, ProxyCacheUtils.getSupposablyMime(url));
    }

    public OkHttpSource(OkHttpSource source) {
        this.sourceInfo = source.sourceInfo;
        this.sourceInfoStorage = source.sourceInfoStorage;
        this.headerInjector = source.headerInjector;
        this.hostnameVerifier = source.hostnameVerifier;
        this.trustAllCerts = source.trustAllCerts;
        this.client = source.client;
    }

    @Override
    public synchronized long length() throws ProxyCacheException {
        if (sourceInfo.length == Integer.MIN_VALUE) {
            fetchContentInfo();
        }
        return sourceInfo.length;
    }

    @Override
    public void open(long offset) throws ProxyCacheException {
        try {
            Response response = openConnection(offset, -1);
            ResponseBody responseBody = checkNotNull(response.body(), "Response body is null");
            MediaType mediaType = responseBody.contentType();
            String mime = mediaType == null ? "" : mediaType.toString();
            long length = readSourceAvailableBytes(responseBody.contentLength(), offset, response.code());
            inputStream = new BufferedInputStream(responseBody.byteStream(), DEFAULT_BUFFER_SIZE);
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening connection for " + sourceInfo.url + " with offset " + offset, e);
        }
    }

    @Override
    public int read(byte[] buffer) throws ProxyCacheException {
        if (inputStream == null) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url + ": connection is absent!");
        }
        try {
            return inputStream.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException e) {
            throw new InterruptedProxyCacheException("Reading source " + sourceInfo.url + " is interrupted", e);
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url, e);
        }
    }

    @Override
    public void close() throws ProxyCacheException {
        ProxyCacheUtils.close(inputStream);
        inputStream = null;
    }

    @Override
    public String getUrl() {
        return sourceInfo.url;
    }

    @Override
    public synchronized String getMime() throws ProxyCacheException {
        if (TextUtils.isEmpty(sourceInfo.mime)) {
            fetchContentInfo();
        }
        return sourceInfo.mime;
    }

    @Override
    public String toString() {
        return "OkHttpSource{sourceInfo='" + sourceInfo + "}";
    }

    private long readSourceAvailableBytes(long contentLength, long offset, int responseCode) {
        return responseCode == HTTP_OK ? contentLength
                : responseCode == HTTP_PARTIAL ? contentLength + offset : sourceInfo.length;
    }

    private void fetchContentInfo() throws ProxyCacheException {
        Logger.debug("Read content info from " + sourceInfo.url);
        Response response = null;
        InputStream inputStream = null;
        try {
            response = openConnection(0, 10000);
            if (!response.isSuccessful()) {
                throw new ProxyCacheException("Fail to fetchContentInfo: " + sourceInfo.url);
            }
            ResponseBody responseBody = checkNotNull(response.body(), "Response body is null");
            MediaType mediaType = responseBody.contentType();
            String mime = mediaType == null ? "" : mediaType.toString();
            long length = responseBody.contentLength();
            inputStream = responseBody.byteStream();
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
            Logger.debug("Source info fetched: " + sourceInfo);
        } catch (IOException e) {
            throw new ProxyCacheException("Error fetching info from " + sourceInfo.url, e);
        } finally {
            ProxyCacheUtils.close(inputStream);
            if (response != null) {
                response.close();
            }
        }
    }

    private Response openConnection(long offset, int timeout) throws IOException, ProxyCacheException {
        Response response;
        boolean redirected;
        int redirectCount = 0;
        String url = this.sourceInfo.url;
        do {
            Logger.debug("Open connection " + (offset > 0 ? " with offset " + offset : "") + " to " + url);
            Request.Builder requestBuilder = new Request.Builder();
            requestBuilder.get();
            requestBuilder.url(url);
            injectCustomHeaders(requestBuilder, url);
            if (offset > 0) {
                requestBuilder.addHeader("Range", "bytes=" + offset + "-");
            }
            response = createClient(timeout).newCall(requestBuilder.build()).execute();
            redirected = response.isRedirect();
            if (redirected) {
                String newUrl = response.header("Location");
                response.close();
                url = newUrl;
                redirectCount++;
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (redirected);
        return response;
    }

    private OkHttpClient createClient(int timeout) {
        OkHttpClient.Builder builder = client.newBuilder();
        if (timeout > 0) {
            builder.connectTimeout(timeout, TimeUnit.MILLISECONDS);
            builder.readTimeout(timeout, TimeUnit.MILLISECONDS);
            builder.writeTimeout(timeout, TimeUnit.MILLISECONDS);
        }
        if (hostnameVerifier != null) {
            builder.hostnameVerifier(hostnameVerifier);
        }
        if (trustAllCerts != null && trustAllCerts.length > 0 && trustAllCerts[0] instanceof X509TrustManager) {
            SSLSocketFactory socketFactory = createTrustAllSocketFactory(trustAllCerts);
            if (socketFactory != null) {
                builder.sslSocketFactory(socketFactory, (X509TrustManager) trustAllCerts[0]);
            }
        }
        return builder.build();
    }

    private static SSLSocketFactory createTrustAllSocketFactory(TrustManager[] trustAllCerts) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Logger.error("Error creating SSL socket factory: " + e.getMessage());
            return null;
        }
    }

    private void injectCustomHeaders(Request.Builder requestBuilder, String url) {
        Map<String, String> extraHeaders = headerInjector.addHeaders(url);
        if (extraHeaders == null) {
            return;
        }
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            requestBuilder.addHeader(header.getKey(), header.getValue());
        }
    }
}
