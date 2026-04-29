package top.jessi.videoplayer.sys;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;

import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.player.TrackInfo;
import top.jessi.videoplayer.player.TrackInfoBean;

/**
 * 封装系统的MediaPlayer，不推荐，系统的MediaPlayer兼容性较差，建议使用IjkPlayer或者ExoPlayer
 */
public class SystemPlayer extends AbstractPlayer implements MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnInfoListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnVideoSizeChangedListener {

    protected MediaPlayer mMediaPlayer;
    private int mBufferedPercent;
    private Context mAppContext;
    private boolean mIsPreparing;

    public SystemPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        mMediaPlayer = new MediaPlayer();
        setOptions();
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnInfoListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnVideoSizeChangedListener(this);
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            mMediaPlayer.setDataSource(mAppContext, Uri.parse(path), headers);
        } catch (Exception e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            mMediaPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        } catch (Exception e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void start() {
        try {
            mMediaPlayer.start();
        } catch (IllegalStateException e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void pause() {
        try {
            mMediaPlayer.pause();
        } catch (IllegalStateException e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void stop() {
        try {
            mMediaPlayer.stop();
        } catch (IllegalStateException e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void prepareAsync() {
        try {
            mIsPreparing = true;
            mMediaPlayer.prepareAsync();
        } catch (IllegalStateException e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void reset() {
        stop();
        mMediaPlayer.reset();
        mMediaPlayer.setSurface(null);
        mMediaPlayer.setDisplay(null);
        mMediaPlayer.setVolume(1, 1);
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
    }

    @Override
    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    @Override
    public void seekTo(long time) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //使用这个api seekTo定位更加准确 支持android 8.0以上的设备 https://developer.android.com/reference/android/media/MediaPlayer#SEEK_CLOSEST
                mMediaPlayer.seekTo(time, MediaPlayer.SEEK_CLOSEST);
            } else {
                mMediaPlayer.seekTo((int) time);
            }
        } catch (IllegalStateException e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void release() {
        mMediaPlayer.setOnErrorListener(null);
        mMediaPlayer.setOnCompletionListener(null);
        mMediaPlayer.setOnInfoListener(null);
        mMediaPlayer.setOnBufferingUpdateListener(null);
        mMediaPlayer.setOnPreparedListener(null);
        mMediaPlayer.setOnVideoSizeChangedListener(null);
        stop();
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        final MediaPlayer mediaPlayer = mMediaPlayer;
        mMediaPlayer = null;
        new Thread() {
            @Override
            public void run() {
                try {
                    mediaPlayer.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    @Override
    public long getCurrentPosition() {
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return mMediaPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mBufferedPercent;
    }

    @Override
    public void setSurface(Surface surface) {
        try {
            mMediaPlayer.setSurface(surface);
        } catch (Exception e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        try {
            mMediaPlayer.setDisplay(holder);
        } catch (Exception e) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setVolume(float v1, float v2) {
        mMediaPlayer.setVolume(v1, v2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        mMediaPlayer.setLooping(isLooping);
    }

    @Override
    public void setOptions() {
    }

    @Override
    public void setSpeed(float speed) {
        // only support above Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mMediaPlayer.setPlaybackParams(mMediaPlayer.getPlaybackParams().setSpeed(speed));
            } catch (Exception e) {
                mPlayerEventListener.onError();
            }
        }
    }

    @Override
    public float getSpeed() {
        // only support above Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                float speed = mMediaPlayer.getPlaybackParams().getSpeed();
                if (speed == 0f) speed = 1f;
                return speed;
            } catch (Exception e) {
                return 1f;
            }
        }
        return 1f;
    }

    private long lastTotalRxBytes = 0;
    private long lastTimeStamp = 0;

    /**
     * 检查流量统计是否不支持
     */
    private boolean unsupported() {
        if (mAppContext == null) {
            return true;
        }
        return TrafficStats.getUidRxBytes(mAppContext.getApplicationInfo().uid) == TrafficStats.UNSUPPORTED;
    }

    /**
     * 获取当前下载速度
     *
     * @return 下载速度（字节/秒）
     */
    @Override
    public long getTcpSpeed() {
        if (mAppContext == null || unsupported()) {
            return 0;
        }
        long total = TrafficStats.getTotalRxBytes();
        long time = System.currentTimeMillis();
        long diff = total - lastTotalRxBytes;
        long speed = diff / Math.max(time - lastTimeStamp, 1);
        lastTimeStamp = time;
        lastTotalRxBytes = total;
        return speed * 1024;
    }

    // ==================== Track Info ====================

    /**
     * 获取音轨和字幕轨道信息
     * <p>
     * Android 系统 MediaPlayer 音轨支持有限，返回空 TrackInfo。
     * 如需完整音轨支持，请使用 IjkPlayer 或 ExoPlayer。
     */
    @Override
    public TrackInfo getTrackInfo() {
        return new TrackInfo();
    }

    /**
     * 切换音轨或字幕轨道
     * <p>
     * Android 系统 MediaPlayer 音轨支持有限，始终返回 false。
     *
     * @param trackBean 轨道信息对象
     * @return 始终返回 false
     */
    @Override
    public boolean setTrack(TrackInfoBean trackBean) {
        return false;
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        mPlayerEventListener.onError();
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        mPlayerEventListener.onCompletion();
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        //解决MEDIA_INFO_VIDEO_RENDERING_START多次回调问题
        if (what == AbstractPlayer.MEDIA_INFO_RENDERING_START) {
            if (mIsPreparing) {
                mPlayerEventListener.onInfo(what, extra);
                mIsPreparing = false;
            }
        } else {
            mPlayerEventListener.onInfo(what, extra);
        }
        return true;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        mBufferedPercent = percent;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mPlayerEventListener.onPrepared();
        start();
        // 修复播放纯音频时状态出错问题
        if (!isVideo()) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
        }
    }

    private boolean isVideo() {
        try {
            MediaPlayer.TrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
            for (MediaPlayer.TrackInfo info :
                    trackInfo) {
                if (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        int videoWidth = mp.getVideoWidth();
        int videoHeight = mp.getVideoHeight();
        if (videoWidth != 0 && videoHeight != 0) {
            mPlayerEventListener.onVideoSizeChanged(videoWidth, videoHeight);
        }
    }
}
