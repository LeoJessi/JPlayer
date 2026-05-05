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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;

import java.util.Map;

import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.player.TrackInfo;
import top.jessi.videoplayer.player.TrackInfoBean;

@OptIn(markerClass = UnstableApi.class)
public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

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

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mRenderersFactory == null) {
            mRenderersFactory = new DefaultRenderersFactory(mAppContext);
            // 硬解失败时自动回退到列表中下一个解码器
            mRenderersFactory.setEnableDecoderFallback(true);
            mRenderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
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
            mMediaPlayer.setVideoSurface(null);
            mIsPreparing = false;
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
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.removeListener(this);
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
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
        //使用getUidRxBytes方法获取该进程总接收量
        long total = TrafficStats.getTotalRxBytes();
        //记录当前的时间
        long time = System.currentTimeMillis();
        //数据接收量除以数据接收的时间，就计算网速了。
        long diff = total - lastTotalRxBytes;
        long speed = diff / Math.max(time - lastTimeStamp, 1);
        //当前时间存到上次时间这个变量，供下次计算用
        lastTimeStamp = time;
        //当前总接收量存到上次接收总量这个变量，供下次计算用
        lastTotalRxBytes = total;

        return speed * 1024;
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
        Log.e("tag--", "onPlayerError: " + error.errorCode, error);
        // 解码器错误（硬解不支持该格式）不应重试，直接上报
        if (isDecoderError(error)) {
            Log.e("tag--", "Decoder error, will not retry");
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
            return;
        }
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
                        String trackName = trackNameProvider.getTrackName(format)
                                + "[" + (TextUtils.isEmpty(format.codecs) ? format.sampleMimeType : format.codecs) + "]";
                        TrackInfoBean t = new TrackInfoBean();
                        t.name = trackName;
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
            if (trackBean == null) {
                // 禁用字幕渲染器
                for (int renderIndex = 0; renderIndex < trackInfo.getRendererCount(); renderIndex++) {
                    if (trackInfo.getRendererType(renderIndex) == C.TRACK_TYPE_TEXT) {
                        DefaultTrackSelector.Parameters.Builder parametersBuilder = mTrackSelector.getParameters().buildUpon();
                        parametersBuilder.setRendererDisabled(renderIndex, true);
                        mTrackSelector.setParameters(parametersBuilder);
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