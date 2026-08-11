package top.jessi.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.TrafficStats;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.player.TimedText;
import top.jessi.videoplayer.player.TrackInfo;
import top.jessi.videoplayer.player.TrackInfoBean;

@OptIn(markerClass = UnstableApi.class)
public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    private static final String TAG = "ExoMediaPlayer";
    protected Context mAppContext;
    protected ExoPlayer mMediaPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;
    protected ExoTrackNameProvider trackNameProvider;
    private PlaybackParameters mSpeedPlaybackParameters;
    private boolean mIsPreparing;
    private LoadControl mLoadControl;
    private DefaultRenderersFactory mRenderersFactory;
    private DefaultTrackSelector mTrackSelector;

    private int errorCode = -100;
    private String path;
    private Map<String, String> headers;
    // 标记是否已尝试过 FFmpeg 软解回退
    private boolean mTriedFfmpegFallback = false;
    // 保存当前 Surface，用于 Player 重建后重新绑定
    private Surface mCurrentSurface = null;
    private long lastSpeedBytes = 0;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mRenderersFactory == null) {
            // 使用 NextRenderersFactory，集成 FFmpeg 软解能力
            // 支持 H265/HEVC 视频解码和 AAC 音频解码，解决设备兼容性问题
            mRenderersFactory = new NextRenderersFactory(mAppContext);
            // 硬解失败时自动回退到列表中下一个解码器
            mRenderersFactory.setEnableDecoderFallback(true);
            // 默认使用 ON 模式：硬解优先，失败后通过 onPlayerError 自动切换到 FFmpeg
            mRenderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        }
        if (mTrackSelector == null) {
            mTrackSelector = new DefaultTrackSelector(mAppContext);
        }
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl();
        }
        mTrackSelector.setParameters(
                mTrackSelector.buildUponParameters()
                        .setTunnelingEnabled(true)
        );
        mMediaPlayer = new ExoPlayer.Builder(mAppContext)
                .setLoadControl(mLoadControl)
                .setRenderersFactory(mRenderersFactory)
                .setTrackSelector(mTrackSelector).build();

        setOptions();

        mMediaPlayer.addListener(this);
    }

    public DefaultTrackSelector getTrackSelector() {
        return mTrackSelector;
    }

    @Override
    public void setDataSource(String path) {
        setDataSource(path, new HashMap<>());
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        this.path = path;
        this.headers = headers;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers, false, errorCode);
        errorCode = -1;
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        //no support
    }

    @Override
    public void start() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mMediaPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mMediaPlayer.setMediaSource(mMediaSource);
        mMediaPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.clearMediaItems();
            mIsPreparing = false;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        lastSpeedBytes = 0;
        // 切换播放源时重置 FFmpeg 回退标志，下次播放重新尝试硬解
        mTriedFfmpegFallback = false;
        // 恢复为 ON 模式（硬解优先）
        if (mRenderersFactory != null) {
            mRenderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        }
    }

    @Override
    public boolean isPlaying() {
        if (mMediaPlayer == null)
            return false;
        int state = mMediaPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mMediaPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null) return;
        // 边界保护：负数置 0，超过时长则钳位到 duration
        // ExoPlayer 内部已有 clamp，但显式钳位可在 duration 尚未加载完时提供一致行为，
        long duration = mMediaPlayer.getDuration();
        if (duration <= 0) return;
        if (time >= duration) {
            time = duration - 2000;
        } else if (time < 0) {
            time = 0;
        }
        mMediaPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.removeListener(this);
            mMediaPlayer.setVideoSurface(null);
            mMediaPlayer.clearMediaItems();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mIsPreparing = false;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        lastSpeedBytes = 0;
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer == null ? 0 : mMediaPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        mCurrentSurface = surface;
        if (mMediaPlayer != null) {
            mMediaPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mMediaPlayer != null)
            mMediaPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mMediaPlayer != null)
            mMediaPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mMediaPlayer != null) {
            mMediaPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    private long lastTotalRxBytes = 0;

    private long lastTimeStamp = 0;

    private boolean unsupported() {
        if (mAppContext == null) {
            return true;
        }
        return TrafficStats.getUidRxBytes(mAppContext.getApplicationInfo().uid) == TrafficStats.UNSUPPORTED;
    }

    @Override
    public long getTcpSpeed() {
        if (mAppContext == null || unsupported()) {
            return 0;
        }
        long total = TrafficStats.getTotalRxBytes();
        long time = System.currentTimeMillis();
        long timeDiff = time - lastTimeStamp;
        // 避免除以零，同时过滤掉时间差过小的情况（< 100ms 视为同一次采样）
        if (timeDiff < 100) {
            return lastSpeedBytes;
        }
        long diff = total - lastTotalRxBytes;
        long speed = (diff * 1000) / timeDiff; // 转换为字节/秒
        lastTimeStamp = time;
        lastTotalRxBytes = total;
        lastSpeedBytes = speed;
        return speed;
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (trackNameProvider == null)
            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
            case Player.STATE_IDLE:
                break;
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        errorCode = error.errorCode;
        Log.w(TAG, "onPlayerError: " + error.errorCode, error);

        // 解码器错误处理：硬解失败后自动切换到 FFmpeg 软解
        if (isDecoderError(error)) {
            // 如果还没尝试过 FFmpeg 回退，则切换到 PREFER 模式重新播放
            if (!mTriedFfmpegFallback && path != null) {
                Log.w(TAG, "Hardware decoder failed, fallback to FFmpeg software decoder");
                mTriedFfmpegFallback = true;
                // 切换到 PREFER 模式（FFmpeg 优先）
                mRenderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
                // 重新创建 Player 以应用新的渲染器模式
                if (mMediaPlayer != null) {
                    mMediaPlayer.release();
                }
                mMediaPlayer = new ExoPlayer.Builder(mAppContext)
                        .setLoadControl(mLoadControl)
                        .setRenderersFactory(mRenderersFactory)
                        .setTrackSelector(mTrackSelector).build();
                mMediaPlayer.addListener(this);
                setOptions();
                // 重新绑定 Surface（关键！否则视频无画面）
                if (mCurrentSurface != null) {
                    mMediaPlayer.setVideoSurface(mCurrentSurface);
                }
                // 重新设置数据源并播放
                String savedPath = path;
                Map<String, String> savedHeaders = headers;
                setDataSource(savedPath, savedHeaders);
                path = null;
                prepareAsync();
                start();
                return;
            }
            // 已经尝试过 FFmpeg 或者没有路径，上报错误
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
            return;
        }

        // 非解码器错误，尝试重试
        if (path != null) {
            setDataSource(path, headers);
            path = null;
            prepareAsync();
            start();
        } else {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 判断是否为解码器相关错误（硬解不支持该格式/编码）
     * 这类错误重试无意义，应直接上报给上层处理
     */
    private boolean isDecoderError(@NonNull PlaybackException error) {
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof IllegalStateException) {
                String msg = cause.getMessage();
                if (msg != null && (msg.contains("dequeueOutputBuffer")
                        || msg.contains("configure")
                        || msg.contains("start"))) {
                    return true;
                }
            }
            // MediaCodec 相关的 MediaCodecVideoDecoderException
            String className = cause.getClass().getSimpleName();
            if (className.contains("MediaCodec")
                    && (className.contains("Decoder") || className.contains("Renderer"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }

    @Override
    public void onCues(@NonNull CueGroup cueGroup) {
        Player.Listener.super.onCues(cueGroup);
        if (mTimedTextListener == null) return;
        List<Cue> cues = cueGroup.cues;
        String subtitle = "";
        if (!cues.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Cue cue : cues) {
                if (cue.text != null) sb.append(cue.text).append("\n");
            }
            subtitle = sb.toString().trim();
        }
        TimedText timedText = new TimedText(subtitle);
        mTimedTextListener.onTimedText(timedText);
    }

    // ==================== Track Info ====================

    /**
     * 获取音轨和字幕轨道信息
     */
    @Override
    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        if (mMediaPlayer == null || mTrackSelector == null) {
            return data;
        }
        MappingTrackSelector.MappedTrackInfo trackInfo = mTrackSelector.getCurrentMappedTrackInfo();
        if (trackInfo == null) {
            return data;
        }

        // 获取当前选中的轨道ID
        String currentAudioId = "";
        String currentSubtitleId = "";
        for (Tracks.Group group : mMediaPlayer.getCurrentTracks().getGroups()) {
            if (!group.isSelected()) continue;
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (!group.isTrackSelected(trackIndex)) continue;
                Format format = group.getTrackFormat(trackIndex);
                if (MimeTypes.isAudio(format.sampleMimeType)) {
                    currentAudioId = format.id;
                }
                if (MimeTypes.isText(format.sampleMimeType)) {
                    currentSubtitleId = format.id;
                }
            }
        }

        for (int groupArrayIndex = 0; groupArrayIndex < trackInfo.getRendererCount(); groupArrayIndex++) {
            TrackGroupArray groupArray = trackInfo.getTrackGroups(groupArrayIndex);
            for (int groupIndex = 0; groupIndex < groupArray.length; groupIndex++) {
                TrackGroup group = groupArray.get(groupIndex);
                for (int formatIndex = 0; formatIndex < group.length; formatIndex++) {
                    Format format = group.getFormat(formatIndex);
                    if (MimeTypes.isAudio(format.sampleMimeType)) {
                        if (trackNameProvider == null) {
                            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
                        }
                        // 原本类似English, Stereo, 0.11 Mbps[mp4a.40.2]，现在打算只保留语言
                        // String trackName = trackNameProvider.getTrackName(format)
                        //   + "[" + (TextUtils.isEmpty(format.codecs) ? format.sampleMimeType : format.codecs)+ "]";
                        String trackName = trackNameProvider.getTrackName(format);
                        TrackInfoBean t = new TrackInfoBean();
                        t.name = trackName.contains("Track ") ? trackName + (groupIndex + 1) : trackName;
                        t.language = "";
                        t.trackId = formatIndex;
                        t.selected = !TextUtils.isEmpty(currentAudioId) && currentAudioId.equals(format.id);
                        t.trackGroupId = groupIndex;
                        t.renderId = groupArrayIndex;
                        data.addAudio(t);
                    } else if (MimeTypes.isText(format.sampleMimeType)) {
                        if (trackNameProvider == null) {
                            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
                        }
                        String trackName = trackNameProvider.getTrackName(format);
                        TrackInfoBean t = new TrackInfoBean();
                        t.name = trackName;
                        t.language = "";
                        t.trackId = formatIndex;
                        t.selected = !TextUtils.isEmpty(currentSubtitleId) && currentSubtitleId.equals(format.id);
                        t.trackGroupId = groupIndex;
                        t.renderId = groupArrayIndex;
                        data.addSubtitle(t);
                    }
                }
            }
        }
        if (!data.getSubtitle().isEmpty()) {
            // 禁用字幕
            TrackInfoBean firstSubtitle = data.getSubtitle().get(0);
            TrackInfoBean disableBean = new TrackInfoBean();
            disableBean.name = "Disable";
            disableBean.trackId = -1;
            disableBean.trackGroupId = -1;
            disableBean.renderId = firstSubtitle.renderId;
            disableBean.selected = false;
            disableBean.language = "";
            data.addSubtitle(0, disableBean);
        }
        return data;
    }

    /**
     * 切换音轨或字幕轨道
     *
     * @param trackBean 轨道信息对象，为 null 时禁用字幕
     * @return true 表示切换成功，false 表示失败
     */
    @Override
    public boolean setTrack(TrackInfoBean trackBean) {
        if (mTrackSelector == null) {
            return false;
        }
        MappingTrackSelector.MappedTrackInfo trackInfo = mTrackSelector.getCurrentMappedTrackInfo();
        if (trackInfo == null) {
            return false;
        }

        try {
            if (trackBean == null || (trackBean.trackId == -1 && trackBean.trackGroupId == -1)) {
                // 禁用字幕渲染器
                for (int renderIndex = 0; renderIndex < trackInfo.getRendererCount(); renderIndex++) {
                    if (trackInfo.getRendererType(renderIndex) == C.TRACK_TYPE_TEXT) {
                        DefaultTrackSelector.Parameters.Builder parametersBuilder =
                                mTrackSelector.getParameters().buildUpon();
                        parametersBuilder.setRendererDisabled(renderIndex, true);
                        mTrackSelector.setParameters(parametersBuilder);
                        TimedText timedText = new TimedText("");
                        mTimedTextListener.onTimedText(timedText);
                        return true;
                    }
                }
                return false;
            } else {
                TrackGroupArray trackGroupArray = trackInfo.getTrackGroups(trackBean.renderId);
                DefaultTrackSelector.SelectionOverride override = new DefaultTrackSelector.SelectionOverride(
                        trackBean.trackGroupId, trackBean.trackId);
                DefaultTrackSelector.Parameters.Builder parametersBuilder = mTrackSelector.buildUponParameters();
                parametersBuilder.setRendererDisabled(trackBean.renderId, false);
                parametersBuilder.setSelectionOverride(trackBean.renderId, trackGroupArray, override);
                mTrackSelector.setParameters(parametersBuilder);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}