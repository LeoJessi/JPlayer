package top.jessi.videoplayer.media;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

    private static final String TAG = "JPlayer—SystemPlayer";
    /**
     * prepareAsync 超时时间（毫秒）
     * 系统 MediaPlayer 在网络异常（如 404、连接拒绝等）时可能长时间阻塞，
     * 此处设置超时主动触发 onError，避免 UI 一直停留在加载状态。
     * 15 秒是一个平衡值：既要给正常链接足够的缓冲准备时间，
     * 也要避免异常链接让用户等待过久。
     */
    private static final long PREPARE_TIMEOUT_MS = 15000L;

    protected MediaPlayer mMediaPlayer;
    private int mBufferedPercent;
    private Context mAppContext;
    private boolean mIsPreparing;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mPrepareTimeoutRunnable;
    private AssetFileDescriptor mAssetFd;

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
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.setDataSource(mAppContext, Uri.parse(path), headers);
        } catch (Exception e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        if (mMediaPlayer == null) return;
        // 关闭之前传入的fd，避免资源泄漏
        closeAssetFd();
        mAssetFd = fd;
        try {
            mMediaPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        } catch (Exception e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
            // 设置失败时关闭fd，避免无效持有
            closeAssetFd();
        }
    }

    /**
     * 关闭AssetFileDescriptor，释放资源
     */
    private void closeAssetFd() {
        if (mAssetFd != null) {
            try {
                mAssetFd.close();
            } catch (Exception e) {
                Log.w(TAG, "close AssetFileDescriptor failed: " + e.getMessage());
            } finally {
                mAssetFd = null;
            }
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
        } catch (NullPointerException e) {
            // 部分厂商 ROM（华为、小米等）在 MediaPlayer.start() 内部调用 getAppName() 时
            // 因 mContext 未就绪导致 NPE，此时播放器实际已准备就绪，重试一次即可
            Log.w(TAG, "start() NPE from vendor ROM, retrying: " + e.getMessage());
            mHandler.post(() -> {
                try {
                    mMediaPlayer.start();
                } catch (Exception retry) {
                    Log.w(TAG, "start() retry failed: " + retry.getMessage(), retry);
                    mPlayerEventListener.onError();
                }
            });
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
            mIsPreparing = true;
            mMediaPlayer.prepareAsync();
            // 启动超时检测：如果超过 PREPARE_TIMEOUT_MS 仍未收到 onPrepared 或 onError，
            // 则主动回调 onError，避免 UI 无限等待
            startPrepareTimeout();
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    /**
     * 启动 prepareAsync 超时检测
     */
    private void startPrepareTimeout() {
        cancelPrepareTimeout();
        mPrepareTimeoutRunnable = () -> {
            Log.w(TAG, "prepareAsync 超时（" + PREPARE_TIMEOUT_MS + "ms），重置播放器并触发 onError");
            // 停止底层异步准备，释放网络连接和后台线程
            if (mMediaPlayer != null) {
                try {
                    mMediaPlayer.reset();
                } catch (Exception e) {
                    Log.w(TAG, "prepareAsync 超时后 reset 失败: " + e.getMessage());
                }
            }
            closeAssetFd();
            mIsPreparing = false;
            mPlayerEventListener.onError();
        };
        mHandler.postDelayed(mPrepareTimeoutRunnable, PREPARE_TIMEOUT_MS);
    }

    /**
     * 取消 prepareAsync 超时检测
     */
    private void cancelPrepareTimeout() {
        if (mPrepareTimeoutRunnable != null) {
            mHandler.removeCallbacks(mPrepareTimeoutRunnable);
            mPrepareTimeoutRunnable = null;
        }
    }

    @Override
    public void reset() {
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        cancelPrepareTimeout();
        mIsPreparing = false;
        closeAssetFd();
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.stop();
        } catch (Exception e) {
            // ignore
        }
        mMediaPlayer.reset();
        // mMediaPlayer.setSurface(null);
        // mMediaPlayer.setDisplay(null);
        mMediaPlayer.setVolume(1, 1);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //使用这个api seekTo定位更加准确 支持android 8.0以上的设备 https://developer.android.com/reference/android/media/MediaPlayer#SEEK_CLOSEST
                mMediaPlayer.seekTo(time, MediaPlayer.SEEK_CLOSEST);
            } else {
                mMediaPlayer.seekTo((int) time);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void release() {
        if (mMediaPlayer == null) return;
        cancelPrepareTimeout();
        closeAssetFd();
        mMediaPlayer.setOnErrorListener(null);
        mMediaPlayer.setOnCompletionListener(null);
        mMediaPlayer.setOnInfoListener(null);
        mMediaPlayer.setOnBufferingUpdateListener(null);
        mMediaPlayer.setOnPreparedListener(null);
        mMediaPlayer.setOnVideoSizeChangedListener(null);
        stop();
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        mMediaPlayer.setSurface(null);
        mMediaPlayer.setDisplay(null);
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
        try {
            mMediaPlayer.setSurface(surface);
        } catch (Exception e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.setDisplay(holder);
        } catch (Exception e) {
            Log.w(TAG, "onError: " + e.getMessage(), e);
            mPlayerEventListener.onError();
        }
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
    public void setOptions() {
    }

    @Override
    public void setSpeed(float speed) {
        // only support above Android M
        if (mMediaPlayer == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mMediaPlayer.setPlaybackParams(mMediaPlayer.getPlaybackParams().setSpeed(speed));
            } catch (Exception e) {
                Log.w(TAG, "onError: " + e.getMessage(), e);
                mPlayerEventListener.onError();
            }
        }
    }

    @Override
    public float getSpeed() {
        if (mMediaPlayer == null) return 1f;
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
        Log.w(TAG, "onError: what=" + what + ", extra=" + extra);
        cancelPrepareTimeout();
        mIsPreparing = false;
        closeAssetFd();
        mPlayerEventListener.onError();
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        closeAssetFd();
        mPlayerEventListener.onCompletion();
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
            //解决MEDIA_INFO_VIDEO_RENDERING_START多次回调问题
            if (mIsPreparing) {
                mPlayerEventListener.onInfo(what, extra);
                mIsPreparing = false;
            }
            // 底层已发送 BUFFERING_START 但未发送 BUFFERING_END，
            // 此时视频已开始渲染，说明缓冲已完成，补发 BUFFERING_END
            mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, 0);
            return true;
        }
        mPlayerEventListener.onInfo(what, extra);
        return true;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        mBufferedPercent = percent;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        cancelPrepareTimeout();
        mPlayerEventListener.onPrepared();
        start();
        // 修复播放纯音频时状态出错问题（纯音频不会回调 MEDIA_INFO_VIDEO_RENDERING_START）
        if (!isVideo()) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
            mIsPreparing = false;
        }
        // 对于视频，mIsPreparing 保持 true，等待 MEDIA_INFO_VIDEO_RENDERING_START 回调时再置 false
    }

    private boolean isVideo() {
        if (mMediaPlayer == null) return false;
        try {
            MediaPlayer.TrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
            for (MediaPlayer.TrackInfo info : trackInfo) {
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
