package top.jessi.videoplayer.util;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.anilbeesetti.nextlib.mediainfo.AudioStream;
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo;
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder;
import io.github.anilbeesetti.nextlib.mediainfo.SubtitleStream;

/**
 * 基于 nextlib-mediainfo 的媒体信息获取工具类。
 * <p>
 * 通过 FFmpeg 获取媒体文件的详细信息，包括：
 * • 视频流信息（编码格式、分辨率、帧率、比特率）
 * • 音频流信息（编码格式、语言、声道数、采样率、比特率）
 * • 字幕流信息（编码格式、语言）
 * <p>
 * 用于补充 ExoPlayer 自带的简单轨道名称，提供更详细的轨道信息。
 * <p>
 * 使用方式：
 * 1. 在播放开始时调用 preloadMediaInfo() 在后台预加载数据
 * 2. 在需要显示轨道信息时调用 getMediaInfo() 获取缓存的数据
 */
public class MediaInfoHelper {

    private static final String TAG = "MediaInfoHelper";

    /**
     * 后台线程池，用于异步获取媒体信息
     */
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    /**
     * 主线程 Handler，用于回调通知
     */
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 缓存媒体信息，避免重复解析
     */
    private static final Map<String, MediaInfo> sCache = new HashMap<>();

    /**
     * 正在加载中的 URI 集合，防止重复加载
     */
    private static final Map<String, Boolean> sLoading = new HashMap<>();

    /**
     * 媒体信息加载回调接口
     */
    public interface OnMediaInfoLoadedListener {
        void onMediaInfoLoaded(@Nullable MediaInfo mediaInfo);
    }

    /**
     * 在后台线程预加载媒体信息。
     * 建议在播放开始时调用，提前解析媒体信息，避免在获取轨道列表时阻塞主线程。
     *
     * @param context  上下文
     * @param uri      媒体 URI
     * @param listener 加载完成回调（在主线程执行），可为 null
     */
    public static void preloadMediaInfo(@NonNull Context context, @NonNull String uri,
                                        @Nullable OnMediaInfoLoadedListener listener) {
        // 如果已有缓存，直接回调
        MediaInfo cached = sCache.get(uri);
        if (cached != null) {
            if (listener != null) {
                sMainHandler.post(() -> listener.onMediaInfoLoaded(cached));
            }
            return;
        }

        // 如果正在加载，不再重复发起
        if (sLoading.containsKey(uri)) {
            return;
        }

        sLoading.put(uri, true);

        sExecutor.execute(() -> {
            MediaInfo mediaInfo = getMediaInfoSync(context, uri);
            sLoading.remove(uri);
            if (listener != null) {
                sMainHandler.post(() -> listener.onMediaInfoLoaded(mediaInfo));
            }
        });
    }

    /**
     * 同步获取媒体信息（带缓存）。
     * 注意：此方法可能耗时较长，建议在后台线程调用。
     *
     * @param context 上下文
     * @param uri     媒体 URI
     * @return MediaInfo 对象，解析失败返回 null
     */
    @Nullable
    public static MediaInfo getMediaInfo(@NonNull Context context, @NonNull String uri) {
        MediaInfo cached = sCache.get(uri);
        if (cached != null) {
            return cached;
        }
        return getMediaInfoSync(context, uri);
    }

    /**
     * 内部同步获取方法
     */
    @Nullable
    private static MediaInfo getMediaInfoSync(@NonNull Context context, @NonNull String uri) {
        try {
            MediaInfoBuilder builder = new MediaInfoBuilder();
            builder.from(context, Uri.parse(uri));
            MediaInfo mediaInfo = builder.build();
            if (mediaInfo != null) {
                Log.d(TAG, "getMediaInfo success: "
                        + " audioStreams=" + mediaInfo.getAudioStreams().size()
                        + " subtitleStreams=" + mediaInfo.getSubtitleStreams().size());
                sCache.put(uri, mediaInfo);
            } else {
                Log.w(TAG, "getMediaInfo: builder.build() returned null for " + uri);
            }
            return mediaInfo;
        } catch (Exception e) {
            Log.e(TAG, "getMediaInfo failed for " + uri, e);
            return null;
        }
    }

    /**
     * 获取指定索引的音频流信息
     */
    @Nullable
    public static AudioStream getAudioStream(@NonNull MediaInfo mediaInfo, int index) {
        List<AudioStream> streams = mediaInfo.getAudioStreams();
        if (streams != null && index >= 0 && index < streams.size()) {
            return streams.get(index);
        }
        return null;
    }

    /**
     * 获取指定索引的字幕流信息
     */
    @Nullable
    public static SubtitleStream getSubtitleStream(@NonNull MediaInfo mediaInfo, int index) {
        List<SubtitleStream> streams = mediaInfo.getSubtitleStreams();
        if (streams != null && index >= 0 && index < streams.size()) {
            return streams.get(index);
        }
        return null;
    }

    /**
     * 获取音频流的详细描述名称
     * 格式：语言 [标题] 或 语言 [编码 声道 采样率 比特率]
     */
    @Nullable
    public static String getAudioTrackName(@NonNull MediaInfo mediaInfo, int index) {
        AudioStream stream = getAudioStream(mediaInfo, index);
        if (stream == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // 语言
        String language = stream.getLanguage();
        if (language != null && !language.isEmpty()) {
            sb.append(language);
        }

        // 优先使用 title（如 "Director's Commentary"、"Stereo Mix" 等）
        String title = stream.getTitle();
        if (title != null && !title.isEmpty()) {
            sb.append(" [").append(title).append("]");
            return sb.toString();
        }

        // 没有 title 时，使用编码信息拼接
        String codec = stream.getCodecName();
        if (codec != null && !codec.isEmpty()) {
            sb.append(" [").append(codec);
        }

        // 声道布局（比简单的声道数更详细，如 "5.1"、"7.1"）
        String channelLayout = stream.getChannelLayout();
        if (channelLayout != null && !channelLayout.isEmpty()) {
            sb.append(" ").append(channelLayout);
        } else {
            // 没有 channelLayout 时使用声道数
            int channels = stream.getChannels();
            if (channels > 0) {
                sb.append(" ").append(channels).append("ch");
            }
        }

        // 采样率
        int sampleRate = stream.getSampleRate();
        if (sampleRate > 0) {
            sb.append(" ").append(sampleRate / 1000).append("kHz");
        }

        // 比特率
        long bitRate = stream.getBitRate();
        if (bitRate > 0) {
            sb.append(" ").append(bitRate / 1000).append("kbps");
        }

        sb.append("]");

        return sb.toString();
    }

    /**
     * 获取字幕流的详细描述名称
     */
    @Nullable
    public static String getSubtitleTrackName(@NonNull MediaInfo mediaInfo, int index) {
        SubtitleStream stream = getSubtitleStream(mediaInfo, index);
        if (stream == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // 语言
        String language = stream.getLanguage();
        if (language != null && !language.isEmpty()) {
            sb.append(language);
        }

        // 标题
        String title = stream.getTitle();
        if (title != null && !title.isEmpty()) {
            sb.append(" [").append(title).append("]");
        }

        return sb.toString();
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        sCache.clear();
    }

    /**
     * 移除指定 URI 的缓存
     */
    public static void removeCache(@NonNull String uri) {
        sCache.remove(uri);
    }

    /**
     * 释放资源，在应用退出时调用
     */
    public static void release() {
        sExecutor.shutdownNow();
        sCache.clear();
        sLoading.clear();
    }
}
