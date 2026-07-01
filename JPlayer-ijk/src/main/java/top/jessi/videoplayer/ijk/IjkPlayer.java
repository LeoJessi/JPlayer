package top.jessi.videoplayer.ijk;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;

import top.jessi.videoplayer.player.TimedText;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.player.TrackInfo;
import top.jessi.videoplayer.player.TrackInfoBean;
import top.jessi.videoplayer.player.VideoViewManager;

public class IjkPlayer extends AbstractPlayer implements IMediaPlayer.OnErrorListener,
        IMediaPlayer.OnCompletionListener, IMediaPlayer.OnInfoListener,
        IMediaPlayer.OnBufferingUpdateListener, IMediaPlayer.OnPreparedListener,
        IMediaPlayer.OnVideoSizeChangedListener, IjkMediaPlayer.OnNativeInvokeListener,
        IMediaPlayer.OnTimedTextListener {

    /**
     * IJK 解码模式
     */
    public enum DecodeMode {
        /**
         * 软件解码（FFmpeg）
         * 不添加任何硬解参数，由 FFmpeg 进行软解
         * 兼容性最好，硬解兼容性差的机型推荐使用
         */
        SOFTWARE,
        /**
         * 硬件解码（MediaCodec）
         * 开启 MediaCodec 硬解码，降低 CPU 占用和功耗
         * 适合大部分机型，少数机型硬解异常时需回退到软解
         */
        HARDWARE
    }

    /**
     * 全局默认解码模式，默认 SOFTWARE（软解）
     * 建议在 Application.onCreate() 中调用 {@link #setDefaultDecodeMode(DecodeMode)} 设置
     */
    private static DecodeMode sDefaultDecodeMode = DecodeMode.SOFTWARE;

    /**
     * 实例级别解码模式，未设置时使用全局默认值
     */
    private DecodeMode mDecodeMode = sDefaultDecodeMode;

    private static final String TAG = "JPlayer-IjkPlayer";

    protected IjkMediaPlayer mMediaPlayer;
    private int mBufferedPercent;
    private final Context mAppContext;

    public IjkPlayer(Context context) {
        mAppContext = context;
    }

    /**
     * 设置全局默认解码模式（影响后续创建的所有 IjkPlayer 实例）
     * <p>
     * 建议在 Application.onCreate() 中调用一次
     *
     * @param mode 解码模式
     */
    public static void setDefaultDecodeMode(DecodeMode mode) {
        sDefaultDecodeMode = mode;
    }

    /**
     * 设置当前播放器的解码模式（需在 initPlayer() 之前调用才生效）
     * <p>
     * 优先级高于全局默认值
     *
     * @param mode 解码模式
     * @return 当前 IjkPlayer 实例，支持链式调用
     */
    public IjkPlayer setDecodeMode(DecodeMode mode) {
        mDecodeMode = mode;
        return this;
    }

    /**
     * 获取当前播放器的解码模式
     */
    public DecodeMode getDecodeMode() {
        return mDecodeMode;
    }

    @Override
    public void initPlayer() {
        mMediaPlayer = new IjkMediaPlayer();
        //native日志
        IjkMediaPlayer.native_setLogLevel(VideoViewManager.getConfig().mIsEnableLog ? IjkMediaPlayer.IJK_LOG_INFO :
                IjkMediaPlayer.IJK_LOG_SILENT);
        setOptions();
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnInfoListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnVideoSizeChangedListener(this);
        mMediaPlayer.setOnNativeInvokeListener(this);
    }


    @Override
    public void setOptions() {
        // ==================== CODEC 层：解码模式相关 ====================
        applyCodecOptions();

        // ==================== FORMAT 层：协议/解复用相关 ====================

        // 设置探测流格式的最大时长（微秒），值越小首开越快；默认 10000000（10秒）
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100000L);

        // 设置分析流的超时时间（毫秒），避免弱网下长时间阻塞在探测阶段
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration_timeout", 3000L);

        // 设置探测数据大小（字节），减小可以加快首开
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1024);

        // 设置解复用阶段的最大帧率，控制内存占用
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-fps", 30);

        // 开启非阻塞式网络IO，避免网络抖动时阻塞播放器线程
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "non_block", 1L);

        // 设置网络超时时间（微秒），默认 0 表示无限等待；此处设为 30 秒
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 30000000L);

        // 允许的协议白名单，按需开启需要的协议以节省探测开销
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist",
                "concat,ffconcat,ijkio,ffio,rtmp,rtsp,async,cache,crypto,file,subfile,dash,http,https,pipe,rtp,tcp," +
                        "tls,udp,data,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,ijkurlhook");

        // 禁用安全模式，允许访问文件描述符
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0L);

        // 设置 TCP 接收缓冲区大小（字节），适当增大可提升弱网下的吞吐量
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "recv_buffer_size", 1048576L);

        // ==================== SWS 层：视频缩放/像素格式转换 ====================

        // 设置缩放算法：bilinear 速度快、效果好，适合大多数场景
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_SWS, "sws_flags", "bilinear");

        // ==================== PLAYER 层：播放器行为相关 ====================

        // 设置最小缓冲帧数，达到此帧数后才开始播放；值越少首开越快，但越容易卡顿
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2);

        // 设置最大缓冲字节数，超过后丢弃多余帧防止延迟累积
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15728640L);

        // 设置最大缓存时长（微秒），超过后触发丢帧以追赶进度
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 15000000L);

        // 关闭播放缓冲，减少首开延迟；适用于低延迟场景（如直播）
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L);

        // 开启精准 seek，seek 时定位到精确位置而非最近关键帧
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L);

        // 设置丢帧阈值，当音画延迟超过此值时丢帧追赶
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 15L);

        // 视频渲染队列大小，减少渲染延迟
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "video-pictq-size", 3);

        // 开启 OpenGL 渲染（如果设备支持），提升渲染效率
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);

        // 不使用视频后处理滤镜，减少 CPU 开销
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "skip_loop_filter", 48L);
    }

    /**
     * 根据解码模式设置编解码参数
     */
    private void applyCodecOptions() {
        if (mDecodeMode == DecodeMode.HARDWARE) {
            // 开启硬解码（MediaCodec），大幅降低 CPU 占用和功耗
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-all-videos", 1L);
            // 硬解码时自动处理视频旋转
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-auto-rotate", 1L);
            // 硬解码时自动处理分辨率切换（如 HLS/DASH 自适应切换）
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-handle-resolution-change", 1L);
            // 硬解码使用同步模式，避免异步模式下的额外延迟
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-sync", 1L);
        }
        // SOFTWARE 模式下不添加硬解参数，由 FFmpeg 默认软解
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        if (mMediaPlayer == null) return;
        try {
            Uri uri = Uri.parse(path);
            if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
                RawDataSourceProvider rawDataSourceProvider = RawDataSourceProvider.create(mAppContext, uri);
                mMediaPlayer.setDataSource(rawDataSourceProvider);
            } else {
                //处理UA问题
                if (headers != null) {
                    String userAgent = headers.get("User-Agent");
                    if (!TextUtils.isEmpty(userAgent)) {
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
                        // 移除header中的User-Agent，防止重复
                        headers.remove("User-Agent");
                    }
                }
                mMediaPlayer.setDataSource(mAppContext, uri, headers);
            }
        } catch (Exception e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.setDataSource(new RawDataSourceProvider(fd));
        } catch (Exception e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void pause() {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.pause();
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void start() {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void stop() {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.stop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.prepareAsync();
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void reset() {
        if (mMediaPlayer == null) return;
        mMediaPlayer.reset();
        mMediaPlayer.setOnVideoSizeChangedListener(this);
        setOptions();
    }

    @Override
    public boolean isPlaying() {
        if (mMediaPlayer == null) return false;
        return mMediaPlayer.isPlaying();
    }

    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.seekTo((int) time);
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void release() {
        if (mMediaPlayer == null) return;
        mMediaPlayer.setOnErrorListener(null);
        mMediaPlayer.setOnCompletionListener(null);
        mMediaPlayer.setOnInfoListener(null);
        mMediaPlayer.setOnBufferingUpdateListener(null);
        mMediaPlayer.setOnPreparedListener(null);
        mMediaPlayer.setOnVideoSizeChangedListener(null);
        mMediaPlayer.release();
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null) return 0;
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mMediaPlayer == null) return 0;
        return mMediaPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mBufferedPercent;
    }

    @Override
    public void setSurface(Surface surface) {
        if (mMediaPlayer == null) return;
        mMediaPlayer.setSurface(surface);
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (mMediaPlayer == null) return;
        mMediaPlayer.setDisplay(holder);
    }

    @Override
    public void setVolume(float v1, float v2) {
        if (mMediaPlayer == null) return;
        mMediaPlayer.setVolume(v1, v2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mMediaPlayer == null) return;
        mMediaPlayer.setLooping(isLooping);
    }

    @Override
    public void setSpeed(float speed) {
        if (mMediaPlayer == null) return;
        mMediaPlayer.setSpeed(speed);
    }

    @Override
    public float getSpeed() {
        if (mMediaPlayer == null) return 1f;
        return mMediaPlayer.getSpeed();
    }

    @Override
    public long getTcpSpeed() {
        if (mMediaPlayer == null) return 0;
        return mMediaPlayer.getTcpSpeed();
    }

    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        Log.w(TAG, "onError: what=" + what + ", extra=" + extra);
        mPlayerEventListener.onError();
        return true;
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        mPlayerEventListener.onCompletion();
    }

    @Override
    public boolean onInfo(IMediaPlayer mp, int what, int extra) {
        if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
            // 底层已发送 BUFFERING_START 但未发送 BUFFERING_END，
            // 此时视频已开始渲染，说明缓冲已完成，补发 BUFFERING_END
            mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, 0);
        }
        mPlayerEventListener.onInfo(what, extra);
        return true;
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer mp, int percent) {
        mBufferedPercent = percent;
    }

    @Override
    public void onPrepared(IMediaPlayer mp) {
        mPlayerEventListener.onPrepared();
        // 修复播放纯音频时状态出错问题
        if (!isVideo()) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
        }
    }

    private boolean isVideo() {
        if (mMediaPlayer == null) return false;
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return false;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sar_num, int sar_den) {
        int videoWidth = mp.getVideoWidth();
        int videoHeight = mp.getVideoHeight();
        if (videoWidth != 0 && videoHeight != 0) {
            mPlayerEventListener.onVideoSizeChanged(videoWidth, videoHeight);
        }
    }

    @Override
    public void onTimedText(IMediaPlayer iMediaPlayer, IjkTimedText ijkTimedText) {
        if (mTimedTextListener == null) return;
        String subtitle = ijkTimedText.getText();
        if (subtitle != null) {
            TimedText timedText = new TimedText(subtitle);
            mTimedTextListener.onTimedText(timedText);
        }
    }

    @Override
    public boolean onNativeInvoke(int what, Bundle args) {
        return true;
    }

    // ==================== Track Info ====================

    /**
     * 获取音轨和字幕轨道信息
     */
    @Override
    public TrackInfo getTrackInfo() {
        if (mMediaPlayer == null) return null;
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == audioSelected;
                data.addAudio(t);
            }
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }

    /**
     * 切换音轨或字幕轨道
     *
     * @param trackBean 轨道信息对象
     * @return true 表示切换成功，false 表示失败
     */
    @Override
    public boolean setTrack(TrackInfoBean trackBean) {
        if (trackBean == null || mMediaPlayer == null) {
            return false;
        }
        try {
            mMediaPlayer.selectTrack(trackBean.trackId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
