package top.jessi.videoplayer.vlc;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.TrafficStats;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.player.BaseVideoView;
import top.jessi.videoplayer.player.TrackInfo;
import top.jessi.videoplayer.player.TrackInfoBean;
import top.jessi.videoplayer.render.RenderViewFactory;

/**
 * VLC播放器实现
 * <p>
 * 流程：
 * 1. initPlayer() - 创建LibVLC和MediaPlayer
 * 2. setDataSource() - 设置媒体源
 * 3. prepareAsync() - 附加视频输出并播放
 * <p>
 * SurfaceView 模式说明：
 * 不使用 VLC SDK 的 MediaPlayer.attachViews()，因为该方法内部创建的 VideoHelper
 * 会将 SurfaceView LayoutParams 改为精确像素值，导致 SurfaceView 原生窗口变小，
 * 视频只在中间小块区域显示（黑边问题）。
 * <p>
 * 改为完全手动管理：
 * 1. 手动 inflate surface_stub ViewStub 获取 SurfaceView
 * 2. 通过 IVLCVout.setVideoView(surfaceView) + attachViews() 绑定（无 listener）
 * 3. SurfaceView 始终 MATCH_PARENT
 * 4. 缩放通过 native setAspectRatio/setScale 处理
 * 5. 通过 IVLCVout.setWindowSize() 通知 native 层显示区域大小
 */
public class VlcPlayer extends AbstractPlayer implements MediaPlayer.EventListener {

    /**
     * VLC 解码模式
     */
    public enum DecodeMode {
        /**
         * 软件解码（FFmpeg）
         * 不添加任何解码参数，由 VLC 使用 FFmpeg 软解
         * 兼容性最好，硬解兼容性差的机型或手机推荐使用
         */
        SOFTWARE,
        /**
         * 硬件解码（MediaCodec）+ 软解 fallback
         * 强制使用硬件解码降低 CPU 占用，不支持的格式自动回退软解
         * 适合 TV 盒子等 CPU 弱的设备
         * <p>
         * 添加参数：--codec=mediacodec,all --avcodec-hw=mediacodec
         * --avcodec-threads=1 --drop-late-frames --skip-frames
         */
        HARDWARE
    }

    private static final String TAG = "JPlayer—VlcPlayer";
    protected Context mAppContext;
    protected LibVLC mLibVLC;
    protected MediaPlayer mMediaPlayer;
    protected Media mMedia;
    private boolean mIsPreparing = false;
    private boolean mViewsAttached = false;
    private int mBufferedPercent = 0;
    private float mSpeed = 1.0f;
    private boolean mIsLooping = false;
    private int mVideoWidth = 0;
    private int mVideoHeight = 0;
    private VlcRenderView mVlcRenderView;
    // 启用VLC内建字幕渲染（包括内置字幕轨道和外部字幕文件）
    protected boolean mEnableVlcSubtitles = true;

    /**
     * 已注入到 VLC 内核的字幕 Uri 集合，用于去重
     */
    private final List<Uri> mAddedSubtitles = new ArrayList<>();

    /**
     * 解码模式，默认 SOFTWARE（软解）
     */
    private static DecodeMode sDefaultDecodeMode = DecodeMode.SOFTWARE;
    private DecodeMode mDecodeMode = sDefaultDecodeMode;

    /**
     * 全局额外的 VLC 启动参数，由外部调用 {@link #addOption(String)} 添加
     * 这些参数会在 initPlayer() 时追加到默认参数之后
     */
    private static final ArrayList<String> sExtraOptions = new ArrayList<>();

    /**
     * 当前缩放类型（用于 SurfaceView 模式的 native 层同步）
     */
    private MediaPlayer.ScaleType mCurrentScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT;

    /**
     * 创建一个VLC播放器
     *
     * @param context 上下文
     */
    public VlcPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    /**
     * 设置全局默认解码模式（影响后续创建的所有 VlcPlayer 实例）
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
     */
    public VlcPlayer setDecodeMode(DecodeMode mode) {
        mDecodeMode = mode;
        return this;
    }

    // 重新实现父类方法，提供 VLC 专用的 RenderViewFactory
    @Override
    public RenderViewFactory getRenderViewFactory(boolean isTextureView) {
        return VlcRenderViewFactory.create(isTextureView);
    }

    /**
     * 获取当前播放器的解码模式
     */
    public DecodeMode getDecodeMode() {
        return mDecodeMode;
    }

    // ==================== 外部自定义参数 ====================

    /**
     * 添加一个自定义 VLC 启动参数（全局生效，影响所有后续创建的 VlcPlayer）
     * <p>
     * 在 initPlayer() 时追加到默认参数之后，可用于启用 VLC 默认未开启的功能。
     * 例如：
     * <pre>
     *   VlcPlayer.addOption("--verbose=2");          // 开启详细日志
     *   VlcPlayer.addOption("--sub-autodetect-all"); // 自动加载所有字幕
     *   VlcPlayer.addOption("--audio-resampler=soxr");// 高质量音频重采样
     * </pre>
     *
     * @param option VLC 命令行参数，如 "--verbose=2"
     */
    public static void addOption(String option) {
        if (option != null && !option.isEmpty()) {
            sExtraOptions.add(option);
        }
    }

    /**
     * 批量添加自定义 VLC 启动参数（全局生效）
     *
     * @param options VLC 命令行参数列表
     */
    public static void addOptions(Collection<String> options) {
        if (options != null) {
            for (String option : options) {
                addOption(option);
            }
        }
    }

    /**
     * 移除一个之前添加的自定义参数
     *
     * @param option 要移除的参数
     * @return true 表示移除成功
     */
    public static boolean removeOption(String option) {
        return sExtraOptions.remove(option);
    }

    /**
     * 清除所有自定义参数（恢复默认）
     */
    public static void clearOptions() {
        sExtraOptions.clear();
    }

    /**
     * 获取当前所有自定义参数的副本（只读查看）
     */
    public static List<String> getExtraOptions() {
        return new ArrayList<>(sExtraOptions);
    }

    /**
     * 初始化播放器，创建LibVLC和MediaPlayer实例
     */
    @Override
    public void initPlayer() {
        ArrayList<String> options = buildDefaultOptions();

        // 追加外部自定义参数
        if (!sExtraOptions.isEmpty()) {
            options.addAll(sExtraOptions);
        }

        mLibVLC = new LibVLC(mAppContext, options);
        mMediaPlayer = new MediaPlayer(mLibVLC);
        mMediaPlayer.setEventListener(this);
    }

    /**
     * 构建默认的 VLC 启动参数
     */
    private ArrayList<String> buildDefaultOptions() {
        ArrayList<String> options = new ArrayList<>();
        // === 解码模式 ===
        applyDecodeOptions(options);
        // === 音频输出 ===
        options.add("--aout=audiotrack");
        // === 网络 ===
        options.add("--network-caching=1500");
        options.add("--rtsp-tcp");
        options.add("--http-reconnect");
        // === 音视频同步 ===
        options.add("--clock-jitter=50");
        options.add("--clock-synchro=0");
        // === TS流优化 ===
        options.add("--ts-seek-percent");
        return options;
    }

    /**
     * 根据解码模式添加对应的 VLC 参数
     */
    private void applyDecodeOptions(ArrayList<String> options) {
        if (mDecodeMode == DecodeMode.HARDWARE) {
            // 强制硬件解码（MediaCodec），软解兜底
            options.add("--codec=mediacodec,all");
            options.add("--avcodec-hw=mediacodec");
            // 软解线程设为1，TV 盒子弱 CPU 避免多线程开销
            options.add("--avcodec-threads=1");
            // 弱 CPU 下丢弃延迟帧 + 跳帧，防止画面积压卡顿
            options.add("--drop-late-frames");
            options.add("--skip-frames");
        } else {
            // SOFTWARE 模式
            options.add("--avcodec-hw=none");
        }

    }

    /**
     * 设置播放数据源
     *
     * @param path    视频地址
     * @param headers 请求头
     */
    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "Error: path is null or empty");
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
            return;
        }

        try {
            if (mMedia != null) {
                mMedia.release();
                mMedia = null;
            }

            mMedia = new Media(mLibVLC, Uri.parse(path));

            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    mMedia.addOption(":" + entry.getKey() + "=" + entry.getValue());
                }
            }

            if (mIsLooping) {
                mMedia.addOption(":input-repeat=65535");
            }

            mMedia.addOption(":network-caching=1500");

            mMediaPlayer.setMedia(mMedia);

        } catch (Exception e) {
            Log.w(TAG, "Error setting data source", e);
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 设置播放数据源（assets文件）
     *
     * @param fd AssetFileDescriptor
     */
    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            if (mMedia != null) {
                mMedia.release();
                mMedia = null;
            }
            mMedia = new Media(mLibVLC, fd.getFileDescriptor());
            mMediaPlayer.setMedia(mMedia);
        } catch (Exception e) {
            Log.w(TAG, "Error setting asset data source", e);
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 开始播放
     */
    @Override
    public void start() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.getMedia() == null && mMedia != null) {
                    mMediaPlayer.setMedia(mMedia);
                }
                if (mViewsAttached) {
                    mMediaPlayer.play();
                } else {
                    mMediaPlayer.play();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error starting player", e);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }
    }

    /**
     * 暂停播放
     */
    @Override
    public void pause() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.pause();
            } catch (Exception e) {
                Log.w(TAG, "Error pausing player", e);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }
    }

    /**
     * 停止播放
     */
    @Override
    public void stop() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping player", e);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }
    }

    /**
     * 异步准备播放，附加视频输出并开始播放
     */
    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null || mMedia == null) {
            Log.w(TAG, "Cannot prepare: MediaPlayer or Media is null");
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
            return;
        }

        mIsPreparing = true;
        try {
            if (mMediaPlayer.getMedia() == null) {
                mMediaPlayer.setMedia(mMedia);
            }

            attachVlcViews();
            applyScaleTypeToNative();

            mMediaPlayer.play();
        } catch (Exception e) {
            Log.w(TAG, "Error in prepareAsync", e);
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 附加视频输出
     * <p>
     * TextureView 模式：使用 VLC SDK 的 MediaPlayer.attachViews()（VideoHelper 正常工作）
     * SurfaceView 模式：完全手动管理，不使用 VideoHelper
     */
    private void attachVlcViews() {
        if (mViewsAttached) {
            return;
        }

        if (mVlcRenderView == null) {
            Log.w(TAG, "VlcRenderView is null, cannot attach views");
            return;
        }

        if (mMediaPlayer == null) {
            Log.w(TAG, "MediaPlayer is null, cannot attach views");
            return;
        }

        boolean useTextureView = mVlcRenderView.getUseTextureView();

        try {
            if (useTextureView) {
                // TextureView 模式：使用 SDK 的 attachViews，VideoHelper 正常工作
                mMediaPlayer.attachViews(
                        mVlcRenderView.getVlcVideoLayout(),
                        null,
                        mEnableVlcSubtitles,
                        true  // useTextureView
                );
            } else {
                // SurfaceView 模式：完全手动管理，绕过 VideoHelper
                attachSurfaceViewManually();
            }

            mViewsAttached = true;
        } catch (Exception e) {
            Log.w(TAG, "Error attaching VLC views", e);
        }
    }

    /**
     * SurfaceView 模式：手动绑定视频输出
     * <p>
     * 步骤：
     * 1. 手动 inflate surface_stub ViewStub 获取 SurfaceView
     * 2. 确保 SurfaceView LayoutParams 为 MATCH_PARENT
     * 3. 通过 IVLCVout.setVideoView + attachViews() 绑定（无 listener）
     * 4. 设置 native 窗口尺寸
     */
    private void attachSurfaceViewManually() {
        VLCVideoLayout vlcLayout = mVlcRenderView.getVlcVideoLayout();
        if (vlcLayout == null) {
            Log.w(TAG, "VLCVideoLayout is null");
            return;
        }

        IVLCVout vlcVout = mMediaPlayer.getVLCVout();
        if (vlcVout == null) {
            Log.w(TAG, "IVLCVout is null");
            return;
        }

        // Step 1: 手动 inflate surface_stub ViewStub
        SurfaceView surfaceView = inflateSurfaceView(vlcLayout);
        if (surfaceView == null) {
            Log.w(TAG, "Failed to inflate SurfaceView");
            return;
        }

        // Step 2: 确保 LayoutParams 为 MATCH_PARENT
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        // 清除 gravity，因为 VLC native 层自己处理缩放
        lp.gravity = 0;
        surfaceView.setLayoutParams(lp);

        // Step 3: 绑定到 IVLCVout（无 listener，避免 onNewVideoLayout 回调）
        vlcVout.setVideoView(surfaceView);
        vlcVout.attachViews();

        // Step 4: 设置 native 窗口尺寸为显示区域大小
        int displayW = vlcLayout.getWidth();
        int displayH = vlcLayout.getHeight();
        if (displayW <= 0 || displayH <= 0) {
            displayW = mVlcRenderView.getWidth();
            displayH = mVlcRenderView.getHeight();
        }
        if (displayW <= 0 || displayH <= 0) {
            Log.w(TAG, "Display size not ready, will set window size on layout");
        } else {
            vlcVout.setWindowSize(displayW, displayH);
        }

        // 添加布局监听器，在布局完成时设置窗口尺寸并应用缩放
        vlcLayout.addOnLayoutChangeListener(mSurfaceLayoutListener);
    }

    /**
     * 手动 inflate surface_stub ViewStub
     */
    private SurfaceView inflateSurfaceView(VLCVideoLayout vlcLayout) {
        FrameLayout surfaceFrame = vlcLayout.findViewById(org.videolan.R.id.player_surface_frame);
        if (surfaceFrame == null) {
            Log.w(TAG, "player_surface_frame not found");
            return null;
        }

        // 查找 surface_stub ViewStub
        ViewStub surfaceStub = surfaceFrame.findViewById(org.videolan.R.id.surface_stub);
        if (surfaceStub != null) {
            View inflated = surfaceStub.inflate();
            if (inflated instanceof SurfaceView) {
                return (SurfaceView) inflated;
            }
        }

        // ViewStub 已经被 inflate 过了，直接查找 surface_video
        SurfaceView sv = surfaceFrame.findViewById(org.videolan.R.id.surface_video);
        if (sv != null) {
            return sv;
        }

        // 最后尝试遍历查找
        return findSurfaceView(surfaceFrame);
    }

    /**
     * 在 ViewGroup 中递归查找 SurfaceView
     */
    private SurfaceView findSurfaceView(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof SurfaceView) {
                return (SurfaceView) child;
            }
            if (child instanceof ViewGroup) {
                SurfaceView sv = findSurfaceView((ViewGroup) child);
                if (sv != null) return sv;
            }
        }
        return null;
    }

    /**
     * VLCVideoLayout 布局变化监听器
     * 用于在布局完成时设置 native 窗口尺寸
     */
    private final View.OnLayoutChangeListener mSurfaceLayoutListener =
            new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) {
                        return;
                    }
                    int w = right - left;
                    int h = bottom - top;
                    if (w > 0 && h > 0 && mMediaPlayer != null) {
                        IVLCVout vlcVout = mMediaPlayer.getVLCVout();
                        if (vlcVout != null) {
                            vlcVout.setWindowSize(w, h);
                        }
                        applyScaleTypeToNative();
                    }
                }
            };

    /**
     * 重置播放器
     */
    @Override
    public void reset() {
        removeSurfaceListeners();
        if (mMediaPlayer != null) {
            try {
                if (mViewsAttached) {
                    mMediaPlayer.detachViews();
                    mViewsAttached = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error detaching views during reset", e);
            }

            try {
                mMediaPlayer.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping player during reset", e);
            }

            mIsPreparing = false;
            mBufferedPercent = 0;
            mVideoWidth = 0;
            mVideoHeight = 0;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        synchronized (mSubtitleUris) {
            mSubtitleUris.clear();
        }
        synchronized (mAddedSubtitles) {
            mAddedSubtitles.clear();
        }
    }

    /**
     * 是否正在播放
     *
     * @return true 表示正在播放
     */
    @Override
    public boolean isPlaying() {
        return mMediaPlayer != null && mMediaPlayer.isPlaying();
    }

    /**
     * 跳转到指定位置
     *
     * @param time 目标位置（毫秒）
     */
    @Override
    public void seekTo(long time) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setTime(time);
            } catch (Exception e) {
                Log.w(TAG, "Error seeking", e);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }
    }

    /**
     * 释放播放器资源
     */
    @Override
    public void release() {
        removeSurfaceListeners();
        if (mMediaPlayer != null) {
            try {
                if (mViewsAttached) {
                    mMediaPlayer.detachViews();
                    mViewsAttached = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error detaching views during release", e);
            }
            mMediaPlayer.setEventListener(null);
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        if (mMedia != null) {
            mMedia.release();
            mMedia = null;
        }

        if (mLibVLC != null) {
            mLibVLC.release();
            mLibVLC = null;
        }

        mVlcRenderView = null;
        mIsPreparing = false;
        mBufferedPercent = 0;
        mVideoWidth = 0;
        mVideoHeight = 0;

        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        synchronized (mSubtitleUris) {
            mSubtitleUris.clear();
        }
        synchronized (mAddedSubtitles) {
            mAddedSubtitles.clear();
        }
    }

    /**
     * 移除 SurfaceView 相关的监听器
     */
    private void removeSurfaceListeners() {
        if (mVlcRenderView != null) {
            VLCVideoLayout layout = mVlcRenderView.getVlcVideoLayout();
            if (layout != null && mSurfaceLayoutListener != null) {
                layout.removeOnLayoutChangeListener(mSurfaceLayoutListener);
            }
        }
    }

    /**
     * 获取当前播放位置
     *
     * @return 当前播放位置（毫秒）
     */
    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null) return 0;
        return mMediaPlayer.getTime();
    }

    /**
     * 获取视频总时长
     *
     * @return 视频总时长（毫秒）
     */
    @Override
    public long getDuration() {
        if (mMediaPlayer == null) return 0;
        return mMediaPlayer.getLength();
    }

    /**
     * 获取当前缓冲百分比
     *
     * @return 缓冲百分比（0-100）
     */
    @Override
    public int getBufferedPercentage() {
        return mBufferedPercent;
    }

    /**
     * 设置Surface（VLC模式下不使用）
     */
    @Override
    public void setSurface(Surface surface) {
        // VLC 模式下不使用
    }

    /**
     * 设置SurfaceHolder（VLC模式下不使用）
     */
    @Override
    public void setDisplay(SurfaceHolder holder) {
        // VLC 模式下不使用
    }

    /**
     * 设置VlcRenderView引用
     *
     * @param renderView VlcRenderView实例
     */
    public void setVlcRenderView(VlcRenderView renderView) {
        this.mVlcRenderView = renderView;
    }

    /**
     * 设置音量
     *
     * @param leftVolume  左声道音量（0.0-1.0）
     * @param rightVolume 右声道音量（0.0-1.0）
     */
    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mMediaPlayer != null) {
            try {
                int vlcVolume = (int) ((leftVolume + rightVolume) / 2 * 200);
                mMediaPlayer.setVolume(vlcVolume);
            } catch (Exception e) {
                Log.w(TAG, "Error setting volume", e);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }
    }

    /**
     * 设置是否循环播放
     *
     * @param isLooping true 表示循环播放
     */
    @Override
    public void setLooping(boolean isLooping) {
        mIsLooping = isLooping;
    }

    /**
     * 设置播放选项（预留接口）
     */
    @Override
    public void setOptions() {
    }

    /**
     * 更新视频缩放类型
     *
     * @param scaleType 缩放类型，参见 BaseVideoView.SCREEN_SCALE_*
     */
    public void updateVlcScaleType(int scaleType) {
        if (mMediaPlayer == null) return;

        MediaPlayer.ScaleType vlcScaleType;
        switch (scaleType) {
            case BaseVideoView.SCREEN_SCALE_16_9:
                vlcScaleType = MediaPlayer.ScaleType.SURFACE_16_9;
                break;
            case BaseVideoView.SCREEN_SCALE_4_3:
                vlcScaleType = MediaPlayer.ScaleType.SURFACE_4_3;
                break;
            case BaseVideoView.SCREEN_SCALE_MATCH_PARENT:
                vlcScaleType = MediaPlayer.ScaleType.SURFACE_FILL;
                break;
            case BaseVideoView.SCREEN_SCALE_ORIGINAL:
                vlcScaleType = MediaPlayer.ScaleType.SURFACE_ORIGINAL;
                break;
            case BaseVideoView.SCREEN_SCALE_CENTER_CROP:
                vlcScaleType = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN;
                break;
            case BaseVideoView.SCREEN_SCALE_DEFAULT:
            default:
                vlcScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT;
                break;
        }

        mCurrentScaleType = vlcScaleType;

        try {
            if (mVlcRenderView != null && !mVlcRenderView.getUseTextureView()) {
                // SurfaceView 模式：直接调用 native API
                applyScaleTypeToNative();
            } else {
                // TextureView 模式：通过 VideoHelper
                mMediaPlayer.setVideoScale(vlcScaleType);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error setting VLC video scale", e);
        }
    }

    /**
     * 通过 native API 设置缩放
     * SurfaceView 始终 MATCH_PARENT，缩放由 VLC native 层处理
     */
    private void applyScaleTypeToNative() {
        if (mMediaPlayer == null) return;

        try {
            MediaPlayer.ScaleType scaleType = mCurrentScaleType;
            if (scaleType == null) scaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT;

            int displayW = 0;
            int displayH = 0;
            if (mVlcRenderView != null) {
                View v = mVlcRenderView.getVlcVideoLayout();
                if (v != null) {
                    displayW = v.getWidth();
                    displayH = v.getHeight();
                }
            }
            if (displayW <= 0 || displayH <= 0) {
                displayW = 1920;
                displayH = 1080;
            }

            switch (scaleType) {
                case SURFACE_BEST_FIT:
                    // 保持视频宽高比，适应显示区域（可能有黑边）
                    mMediaPlayer.setAspectRatio(null);
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_FIT_SCREEN:
                    // 保持视频宽高比，填充显示区域（可能裁剪视频）
                    applyFitScreenScale(displayW, displayH);
                    break;
                case SURFACE_FILL:
                    // 填充屏幕，不保持宽高比
                    mMediaPlayer.setScale(0);
                    mMediaPlayer.setAspectRatio(displayW + ":" + displayH);
                    break;
                case SURFACE_ORIGINAL:
                    // 原始尺寸，不缩放
                    mMediaPlayer.setAspectRatio(null);
                    mMediaPlayer.setScale(1);
                    break;
                case SURFACE_16_9:
                    mMediaPlayer.setAspectRatio("16:9");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_4_3:
                    mMediaPlayer.setAspectRatio("4:3");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_16_10:
                    mMediaPlayer.setAspectRatio("16:10");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_2_1:
                    mMediaPlayer.setAspectRatio("2:1");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_221_1:
                    mMediaPlayer.setAspectRatio("2.21:1");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_235_1:
                    mMediaPlayer.setAspectRatio("2.35:1");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_239_1:
                    mMediaPlayer.setAspectRatio("2.39:1");
                    mMediaPlayer.setScale(0);
                    break;
                case SURFACE_5_4:
                    mMediaPlayer.setAspectRatio("5:4");
                    mMediaPlayer.setScale(0);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error applying native scale", e);
        }
    }

    /**
     * SURFACE_FIT_SCREEN：计算精确缩放因子，让视频保持宽高比并填满显示区域
     * <p>
     * 逻辑与 VLC SDK VideoHelper.changeMediaPlayerLayout 中的 SURFACE_FIT_SCREEN 分支一致：
     * 1. 获取视频轨道的实际宽高和方向
     * 2. 考虑 SAR（Sample Aspect Ratio）修正视频宽度
     * 3. 计算视频宽高比 videoRatio 和显示区域宽高比 displayRatio
     * 4. 比较两个比例，取较大的缩放因子
     * 5. 调用 setScale(scale) + setAspectRatio(null)
     */
    private void applyFitScreenScale(int displayW, int displayH) {
        if (mMediaPlayer == null || displayW <= 0 || displayH <= 0) return;

        try {
            IMedia.VideoTrack videoTrack = mMediaPlayer.getCurrentVideoTrack();
            if (videoTrack == null) {
                mMediaPlayer.setAspectRatio(null);
                mMediaPlayer.setScale(0);
                return;
            }

            int videoWidth = videoTrack.width;
            int videoHeight = videoTrack.height;
            if (videoWidth <= 0 || videoHeight <= 0) {
                mMediaPlayer.setAspectRatio(null);
                mMediaPlayer.setScale(0);
                return;
            }

            boolean needSwap = (videoTrack.orientation == 5 || videoTrack.orientation == 6);
            if (needSwap) {
                int tmp = videoWidth;
                videoWidth = videoHeight;
                videoHeight = tmp;
            }

            int sarNum = videoTrack.sarNum;
            int sarDen = videoTrack.sarDen;
            if (sarNum != sarDen) {
                videoWidth = videoWidth * sarNum / sarDen;
            }

            float videoRatio = (float) videoWidth / videoHeight;
            float displayRatio = (float) displayW / displayH;

            float scale;
            if (displayRatio >= videoRatio) {
                scale = (float) displayW / videoWidth;
            } else {
                scale = (float) displayH / videoHeight;
            }

            mMediaPlayer.setScale(scale);
            mMediaPlayer.setAspectRatio(null);
        } catch (Exception e) {
            Log.w(TAG, "Error applying FIT_SCREEN scale", e);
            mMediaPlayer.setAspectRatio(null);
            mMediaPlayer.setScale(0);
        }
    }

    /**
     * 设置播放速度
     *
     * @param speed 播放速度（1.0为正常速度）
     */
    @Override
    public void setSpeed(float speed) {
        if (mMediaPlayer != null) {
            try {
                mSpeed = speed;
                mMediaPlayer.setRate(speed);
            } catch (Exception e) {
                Log.w(TAG, "Error setting speed", e);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }
    }

    /**
     * 获取当前播放速度
     *
     * @return 播放速度
     */
    @Override
    public float getSpeed() {
        return mSpeed;
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

    /**
     * VLC播放器事件回调
     */
    @Override
    public void onEvent(MediaPlayer.Event event) {
        if (mPlayerEventListener == null) return;
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                break;
            case MediaPlayer.Event.Playing:
                if (mIsPreparing) {
                    mPlayerEventListener.onPrepared();
                    mIsPreparing = false;
                }
                notifyVideoSize();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                break;
            case MediaPlayer.Event.Paused:
                break;
            case MediaPlayer.Event.Stopped:
                break;
            case MediaPlayer.Event.EndReached:
                mPlayerEventListener.onCompletion();
                break;
            case MediaPlayer.Event.EncounteredError:
                Log.w(TAG, "VLC Event: Error");
                mPlayerEventListener.onError();
                break;
            case MediaPlayer.Event.Buffering:
                mBufferedPercent = (int) event.getBuffering();
                if (mBufferedPercent < 100) {
                    mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, mBufferedPercent);
                } else {
                    mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, mBufferedPercent);
                }
                break;
            case MediaPlayer.Event.Vout:
                notifyVideoSize();
                addAllSubtitlesOnVout();
                if (mVlcRenderView != null && mEnableVlcSubtitles) {
                    applyScaleTypeToNative();
                }
                break;
            default:
                break;
        }
    }

    /**
     * 通知视频尺寸变化
     */
    private void notifyVideoSize() {
        if (mMedia == null || mPlayerEventListener == null) return;
        try {
            int width = 0;
            int height = 0;

            if (mMediaPlayer != null) {
                try {
                    IMedia parsedMedia = mMediaPlayer.getMedia();
                    if (parsedMedia != null) {
                        int parsedTrackCount = parsedMedia.getTrackCount();
                        for (int i = 0; i < parsedTrackCount; i++) {
                            IMedia.Track track = parsedMedia.getTrack(i);
                            if (track != null && track.type == IMedia.Track.Type.Video) {
                                IMedia.VideoTrack videoTrack = (IMedia.VideoTrack) track;
                                width = videoTrack.width;
                                height = videoTrack.height;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting video size from parsed Media", e);
                }
            }

            if ((width <= 0 || height <= 0) && mMedia != null) {
                int trackCount = mMedia.getTrackCount();
                for (int i = 0; i < trackCount; i++) {
                    IMedia.Track track = mMedia.getTrack(i);
                    if (track != null && track.type == IMedia.Track.Type.Video) {
                        IMedia.VideoTrack videoTrack = (IMedia.VideoTrack) track;
                        width = videoTrack.width;
                        height = videoTrack.height;
                        break;
                    }
                }
            }

            if (width <= 0 || height <= 0) {
                width = 1280;
                height = 720;
            }

            if (width != mVideoWidth || height != mVideoHeight) {
                mVideoWidth = width;
                mVideoHeight = height;
                mPlayerEventListener.onVideoSizeChanged(mVideoWidth, mVideoHeight);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting video size", e);
        }
    }

    // ==================== Track Info ====================

    /**
     * 获取音轨和字幕轨道信息
     *
     * @return TrackInfo 包含所有音轨和字幕轨道
     */
    @Override
    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        if (mMediaPlayer == null) {
            return data;
        }
        int currentAudioTrack = mMediaPlayer.getAudioTrack();
        int currentSubtitleTrack = mMediaPlayer.getSpuTrack();

        int audioTrackCount = mMediaPlayer.getAudioTracksCount();
        if (audioTrackCount > 0) {
            MediaPlayer.TrackDescription[] audioTracks = mMediaPlayer.getAudioTracks();
            if (audioTracks != null) {
                for (MediaPlayer.TrackDescription audio : audioTracks) {
                    TrackInfoBean bean = new TrackInfoBean();
                    bean.name = audio.name;
                    bean.language = "";
                    bean.trackId = audio.id;
                    bean.selected = (audio.id == currentAudioTrack);
                    data.addAudio(bean);
                }
            }
        }

        int spuTrackCount = mMediaPlayer.getSpuTracksCount();
        if (spuTrackCount > 0) {
            MediaPlayer.TrackDescription[] subtitleTracks = mMediaPlayer.getSpuTracks();
            if (subtitleTracks != null) {
                for (MediaPlayer.TrackDescription subtitle : subtitleTracks) {
                    TrackInfoBean bean = new TrackInfoBean();
                    bean.name = subtitle.name;
                    bean.language = "";
                    bean.trackId = subtitle.id;
                    bean.selected = (subtitle.id == currentSubtitleTrack);
                    data.addSubtitle(bean);
                }
            }
        }

        return data;
    }

    /**
     * 设置选中的轨道
     *
     * @param trackBean 轨道信息
     * @return true 表示设置成功
     */
    @Override
    public boolean setTrack(TrackInfoBean trackBean) {
        if (trackBean == null) {
            return setSubtitleTrack(-1);
        }
        if (mMediaPlayer == null) {
            return false;
        }
        MediaPlayer.TrackDescription[] audioTracks = mMediaPlayer.getAudioTracks();
        if (audioTracks != null) {
            for (MediaPlayer.TrackDescription audio : audioTracks) {
                if (audio.id == trackBean.trackId) {
                    return setAudioTrack(trackBean.trackId);
                }
            }
        }
        return setSubtitleTrack(trackBean.trackId);
    }

    /**
     * 设置音轨
     *
     * @param trackId 音轨ID
     * @return true 表示设置成功
     */
    public boolean setAudioTrack(int trackId) {
        if (mMediaPlayer == null) {
            return false;
        }
        try {
            return mMediaPlayer.setAudioTrack(trackId);
        } catch (Exception e) {
            Log.w(TAG, "Error setting audio track", e);
            return false;
        }
    }

    /**
     * 设置字幕轨道
     *
     * @param trackId 字幕轨道ID，-1 表示禁用字幕
     * @return true 表示设置成功
     */
    public boolean setSubtitleTrack(int trackId) {
        if (mMediaPlayer == null) {
            return false;
        }
        try {
            return mMediaPlayer.setSpuTrack(trackId);
        } catch (Exception e) {
            Log.w(TAG, "Error setting subtitle track", e);
            return false;
        }
    }

    // ==================== 外部字幕管理 ====================
    // 统一通过 MediaPlayer.addSlave() 添加：
    // - 播放中调用 addSubtitle() 直接注入
    // - 播放前调用 addSubtitle() 记录列表，Vout 事件后批量注入

    /**
     * 添加外部字幕（重写父类方法）
     * <p>
     * 播放中：立即通过 MediaPlayer.addSlave() 注入内核。
     * 播放前：记录到列表，Vout 事件后自动批量注入。
     *
     * @param uri 字幕文件的 Uri
     */
    @Override
    public void addSubtitle(Uri uri) {
        if (uri == null) return;
        synchronized (mSubtitleUris) {
            if (!mSubtitleUris.contains(uri)) {
                mSubtitleUris.add(uri);
                if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                    doAddSlave(uri);
                }
            }
        }
    }

    /**
     * Vout 事件回调中调用，将所有已注册的字幕通过 MediaPlayer.addSlave() 添加
     */
    private void addAllSubtitlesOnVout() {
        List<Uri> uris;
        synchronized (mSubtitleUris) {
            if (mSubtitleUris.isEmpty()) return;
            uris = new ArrayList<>(mSubtitleUris);
        }
        for (Uri uri : uris) {
            doAddSlave(uri);
        }
    }

    /**
     * 通过 MediaPlayer.addSlave() 添加单个字幕（VLC 特有）
     * <p>
     * 已注入过的字幕不会重复添加。
     */
    private boolean doAddSlave(Uri uri) {
        synchronized (mAddedSubtitles) {
            if (mAddedSubtitles.contains(uri)) {
                return true;
            }
        }
        try {
            if (mMediaPlayer == null) return false;
            IVLCVout vlcVout = mMediaPlayer.getVLCVout();
            if (vlcVout == null) {
                return false;
            }
            boolean success = mMediaPlayer.addSlave(Media.Slave.Type.Subtitle, uri, false);
            if (success) {
                synchronized (mAddedSubtitles) {
                    mAddedSubtitles.add(uri);
                }
            }
            return success;
        } catch (Exception e) {
            Log.w(TAG, "Error adding slave ", e);
            return false;
        }
    }
}