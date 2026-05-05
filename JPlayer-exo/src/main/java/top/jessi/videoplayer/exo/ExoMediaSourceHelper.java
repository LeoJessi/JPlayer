package top.jessi.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import okhttp3.OkHttpClient;

@OptIn(markerClass = UnstableApi.class)
public final class ExoMediaSourceHelper {

    private static volatile ExoMediaSourceHelper sInstance;

    private final String mUserAgent;
    private final Context mAppContext;
    private OkHttpDataSource.Factory mHttpDataSourceFactory;
    private OkHttpClient mOkClient = null;
    private Cache mCache;

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
        mUserAgent = Util.getUserAgent(mAppContext, mAppContext.getApplicationInfo().name);
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public void setOkClient(OkHttpClient client) {
        mOkClient = client;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, -1);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int errorCode) {
        Uri contentUri = Uri.parse(uri);

        // 对于 rtsp 等特殊协议，使用 DefaultMediaSourceFactory 自动处理
        if ("rtsp".equals(contentUri.getScheme())) {
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(contentUri)
                    .setMimeType(MimeTypes.APPLICATION_RTSP)
                    .build();
            return new DefaultMediaSourceFactory(getDataSourceFactory())
                    .createMediaSource(mediaItem);
        }

        int contentType = inferContentType(uri);
        DataSource.Factory upstreamFactory;
        if (isCache) {
            upstreamFactory = getCacheDataSourceFactory();
        } else {
            upstreamFactory = getDataSourceFactory();
        }

        if (mHttpDataSourceFactory != null) {
            setHeaders(headers);
        }

        // 构建 MediaItem，根据内容类型设置正确的 mimeType
        MediaItem mediaItem = buildMediaItem(uri, contentType, errorCode);

        // 对于 DASH 和 HLS，使用 DefaultMediaSourceFactory 配合 ExtractorsFactory
        // 对于普通渐进式下载，使用 ProgressiveMediaSource
        if (contentType == C.CONTENT_TYPE_DASH) {
            // DASH: 使用 DefaultMediaSourceFactory
            return new DefaultMediaSourceFactory(upstreamFactory, getExtractorsFactory())
                    .createMediaSource(mediaItem);
        } else if (contentType == C.CONTENT_TYPE_HLS) {
            // HLS: 使用 DefaultMediaSourceFactory
            return new DefaultMediaSourceFactory(upstreamFactory, getExtractorsFactory())
                    .createMediaSource(mediaItem);
        } else {
            // 普通渐进式
            return new ProgressiveMediaSource.Factory(upstreamFactory)
                    .createMediaSource(mediaItem);
        }
    }

    private MediaItem buildMediaItem(String uri, int contentType, int errorCode) {
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(Uri.parse(uri.trim().replace("\\", "")));

        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (contentType == C.CONTENT_TYPE_DASH) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        } else if (contentType == C.CONTENT_TYPE_HLS) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }

        return builder.build();
    }

    private ExtractorsFactory getExtractorsFactory() {
        return new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);
    }

    private int inferContentType(String fileName) {
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd")) {
            return C.CONTENT_TYPE_DASH;
        } else if (fileName.contains(".m3u8")) {
            return C.CONTENT_TYPE_HLS;
        } else {
            return C.CONTENT_TYPE_OTHER;
        }
    }

    private DataSource.Factory getCacheDataSourceFactory() {
        if (mCache == null) {
            mCache = newCache();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private Cache newCache() {
        return new SimpleCache(
                new File(mAppContext.getExternalCacheDir(), "exo-video-cache"),
                new LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024),
                new StandaloneDatabaseProvider(mAppContext));
    }

    private DataSource.Factory getDataSourceFactory() {
        return new DefaultDataSource.Factory(mAppContext, getHttpDataSourceFactory());
    }

    private DataSource.Factory getHttpDataSourceFactory() {
        if (mHttpDataSourceFactory == null) {
            OkHttpClient client = mOkClient != null ? mOkClient : new OkHttpClient();
            mHttpDataSourceFactory = new OkHttpDataSource.Factory(client)
                    .setUserAgent(mUserAgent);
        }
        return mHttpDataSourceFactory;
    }

    private void setHeaders(Map<String, String> headers) {
        if (headers != null && headers.size() > 0) {
            if (headers.containsKey("User-Agent")) {
                String value = headers.remove("User-Agent");
                if (!TextUtils.isEmpty(value)) {
                    try {
                        Field userAgentField = mHttpDataSourceFactory.getClass().getDeclaredField("userAgent");
                        userAgentField.setAccessible(true);
                        userAgentField.set(mHttpDataSourceFactory, value.trim());
                    } catch (Exception e) {
                        //ignore
                    }
                }
            }
            for (String k : headers.keySet()) {
                String v = headers.get(k);
                if (v != null)
                    headers.put(k, v.trim());
            }
            mHttpDataSourceFactory.setDefaultRequestProperties(headers);
        }
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }
}
