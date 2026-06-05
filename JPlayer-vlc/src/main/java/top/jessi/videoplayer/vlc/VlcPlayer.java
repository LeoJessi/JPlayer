package top.jessi.videoplayer.vlc;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
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
import org.videolan.libvlc.util.VLCUtil;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.player.BaseVideoView;
import top.jessi.videoplayer.player.TrackInfo;
import top.jessi.videoplayer.player.TrackInfoBean;
import top.jessi.videoplayer.render.RenderViewFactory;
import top.jessi.videoplayer.util.HlsProxy;
import top.jessi.videoplayer.util.L;

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
     * VLC 硬件加速模式
     * <p>
     * 参考 VLC 官方 VLCOptions.java 中的 HW_ACCELERATION_* 级别设计。
     * <p>
     * 硬件加速涉及两个维度：<b>解码</b>和<b>渲染</b>：
     * <ul>
     *   <li><b>解码（Decoding）</b>：使用 MediaCodec 硬解 vs FFmpeg 软解</li>
     *   <li><b>渲染（Rendering）</b>：Direct Rendering（解码帧直送 Surface，零拷贝）
     *       vs GPU 纹理渲染（可后处理但多一次拷贝）</li>
     * </ul>
     */
    public enum HWAccel {
        /**
         * 纯软件解码 + GPU 纹理渲染
         * <p>
         * setHWDecoderEnabled(false, false)
         * 兼容性最强，支持所有格式，CPU 占用高
         */
        DISABLED,
        /**
         * 硬件解码 + GPU 纹理渲染
         * <p>
         * setHWDecoderEnabled(true, false)
         * 解码由 MediaCodec 完成，渲染走 GPU 纹理。
         * 降低 CPU 占用，同时支持截图、滤镜等后处理。
         * 适合需要后处理能力的场景
         */
        DECODING,
        /**
         * 全链路硬件加速（硬件解码 + Direct Rendering）
         * <p>
         * setHWDecoderEnabled(true, true)
         * 解码帧直接输出到 Surface，零拷贝、最低延迟、最低功耗。
         * 不支持截图和后处理。
         * 适合 TV 盒子等追求极致性能的场景
         */
        FULL,
        /**
         * 自动选择（VLC 默认行为）
         * <p>
         * 不设置任何参数，由 VLC 根据设备能力和媒体格式自动决策
         */
        AUTOMATIC
    }

    private static final String TAG = "JPlayer—VlcPlayer";

    /**
     * HLS 代理开关，默认启用。可通过 {@link #setHlsProxyEnabled(boolean)} 手动控制
     */
    private static boolean sHlsProxyEnabled = true;

    /**
     * HLS 本地代理实例（每个 VlcPlayer 实例持有一个）
     */
    private HlsProxy mHlsProxy;

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
    private final int TRACK_GROUD_AUDIO = 0;
    private final int TRACK_GROUD_SUBTITLE = 1;

    /**
     * 已注入到 VLC 内核的字幕 Uri 集合，用于去重
     */
    private final List<Uri> mAddedSubtitles = new ArrayList<>();

    /**
     * 硬件加速模式，默认 AUTOMATIC（由 VLC 自动决策）
     */
    private static HWAccel sDefaultHWAccel = HWAccel.AUTOMATIC;
    private HWAccel mHWAccel = sDefaultHWAccel;

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
     * 创建 VLC 播放器
     *
     * @param context 上下文
     */
    public VlcPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    /**
     * 设置全局默认硬件加速模式（影响后续创建的所有 VlcPlayer 实例）
     * <p>
     * 建议在 Application.onCreate() 中调用一次
     *
     * @param accel 硬件加速模式
     */
    public static void setDefaultHWAccel(HWAccel accel) {
        sDefaultHWAccel = accel;
    }

    /**
     * 设置当前播放器的硬件加速模式（需在 initPlayer() 之前调用才生效）
     * <p>
     * 优先级高于全局默认值
     *
     * @param accel 硬件加速模式
     */
    public VlcPlayer setHWAccel(HWAccel accel) {
        mHWAccel = accel;
        return this;
    }

    // 重新实现父类方法，提供 VLC 专用的 RenderViewFactory
    @Override
    public RenderViewFactory getRenderViewFactory(boolean isTextureView) {
        return VlcRenderViewFactory.create(isTextureView);
    }

    /**
     * 获取当前播放器的硬件加速模式
     */
    public HWAccel getHWAccel() {
        return mHWAccel;
    }

    // ==================== HLS 代理控制 ====================

    /**
     * 启用/禁用 HLS 本地代理（全局，默认启用）
     */
    public static void setHlsProxyEnabled(boolean enabled) {
        sHlsProxyEnabled = enabled;
    }

    /**
     * 获取 HLS 代理启用状态
     */
    public static boolean isHlsProxyEnabled() {
        return sHlsProxyEnabled;
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

        // 参考官方 PlayerController.newMediaPlayer() 的初始化配置：
        // 1. 禁用 VLC 内建 OSD 标题显示（避免视频画面上叠加标题文字）
        mMediaPlayer.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0);

        mMediaPlayer.setEventListener(this);
    }

    /**
     * 构建默认的 VLC 启动参数
     * <p>
     * 参考 VLC 官方 VLCOptions.getLibOptions() 的参数配置策略，
     * 针对 Android 移动端做了适配和精简。
     */
    private ArrayList<String> buildDefaultOptions() {
        ArrayList<String> options = new ArrayList<>(32);

        // === 解码优化 ===
        // 根据 CPU 架构自动选择 deblocking 级别（参考官方 VLCOptions.getDeblocking）
        int deblocking = getAutoDeblockingLevel();
        options.add("--avcodec-skiploopfilter");
        options.add("" + deblocking);
        // 开启可加速解码，但有可能牺牲画质 -- 高性能设备默认不跳帧，保持画面完整性
        options.add("--avcodec-skip-frame");
        options.add(deblocking >= 3 ? "2" : "0");
        options.add("--avcodec-skip-idct");
        options.add(deblocking >= 3 ? "2" : "0");

        // === 音频输出 ===
        // 参考官方 VLCOptions.getAout()：蓝牙场景自动切换，否则默认 audiotrack
        String aout = getAutoAudioOutput();
        if (aout != null) {
            options.add(aout);
        }

        // === 音频时间拉伸 ===
        // 参考官方 VLCOptions.getLibOptions()：倍速播放时保持音频音调不变
        // 开启后变速不变调，但会增加少量 CPU 开销
        options.add("--audio-time-stretch");

        // === 音频重采样 ===
        // 参考官方 VLCOptions.getResampler()：多核用 soxr（高质量），少核用 ugly（低开销）
        options.add("--audio-resampler");
        options.add(getAutoResampler());

        // === 网络 ===
        options.add("--rtsp-tcp");
        options.add("--http-reconnect");

        // === 音视频同步 ===
        options.add("--clock-jitter=50");
        options.add("--clock-synchro=0");

        // === TS流优化 ===
        options.add("--ts-seek-percent");

        // === 统计信息 ===
        // 官方默认开启 --stats，用于获取缓冲等状态
        options.add("--stats");

        return options;
    }

    /**
     * 根据 CPU 能力自动选择 deblocking 级别
     * <p>
     * 直接复用 VLC 官方的 {@link VLCUtil#getMachineSpecs()} 进行精确 CPU 检测，
     * 逻辑与官方 VLCOptions.getDeblocking() 完全一致：
     * <ul>
     *   <li>ARMv6 或 MIPS → 跳过所有环路滤波（级别4），降低 CPU 开销</li>
     *   <li>ARMv7+ 且频率 >= 1200MHz 且核心数 > 2 → 跳过非参考帧（级别1），平衡质量与性能</li>
     *   <li>ARMv7+ 且 bogoMIPS >= 1200 且核心数 > 2 → 跳过非参考帧（级别1），频率信息缺失时的备选方案</li>
     *   <li>其他 → 跳过非关键帧（级别3），保守策略</li>
     * </ul>
     *
     * @return deblocking 级别 (0-4)
     */
    private int getAutoDeblockingLevel() {
        VLCUtil.MachineSpecs m = VLCUtil.getMachineSpecs();
        if (m == null) return 3; // 无法获取 CPU 信息时走保守策略

        // ARMv6 或 MIPS 设备（老旧/低端），skip all
        if ((m.hasArmV6 && !m.hasArmV7) || m.hasMips) {
            return 4;
        }

        // 高频多核 ARMv7+，skip non-ref
        if (m.frequency >= 1200 && m.processors > 2) {
            return 1;
        }

        // 频率信息缺失时用 bogoMIPS 替代判断
        if (m.bogoMIPS >= 1200 && m.processors > 2) {
            return 1;
        }

        // 默认保守策略，skip non-key
        return 3;
    }

    /**
     * 自动选择音频输出
     * <p>
     * 参考官方 VLCOptions.getAout()：
     * - 蓝牙 A2dp 连接时：使用 audiotrack（兼容性更好）
     * - 其他情况：返回 null，让 VLC 自动选择（通常是 AAudio 或 OpenSL ES）
     *
     * @return 音频输出参数，null 表示让 VLC 自动选择
     */
    private String getAutoAudioOutput() {
        try {
            AudioManager audioManager = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                // 蓝牙 A2dp 场景下强制使用 audiotrack
                if (audioManager.isBluetoothA2dpOn()) {
                    return "--aout=audiotrack";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error detecting audio output", e);
        }
        // 返回 null 让 VLC 自动选择最优输出
        return null;
    }

    /**
     * 自动选择音频重采样器
     * <p>
     * 直接复用 VLC 官方的 {@link VLCUtil#getMachineSpecs()} 进行 CPU 核心数检测，
     * 逻辑与官方 VLCOptions.getResampler() 完全一致：
     * - 多核设备（> 2核）：使用 soxr（高质量重采样）
     * - 少核设备：使用 ugly（低开销，质量可接受）
     *
     * @return 重采样器名称
     */
    private String getAutoResampler() {
        VLCUtil.MachineSpecs m = VLCUtil.getMachineSpecs();
        return (m == null || m.processors > 2) ? "soxr" : "ugly";
    }

    /**
     * 在 Media 级别设置硬件解码控制
     * <p>
     * 参考官方 VLCOptions.setMediaOptions() 的实现。
     * <p>
     * {@code setHWDecoderEnabled(enabled, video)} 的两个参数：
     * <ul>
     *   <li>{@code enabled}：是否启用硬件解码（MediaCodec）。true=硬解，false=软解</li>
     *   <li>{@code video}：是否启用 Direct Rendering（直接渲染）。true=解码帧直接输出到 Surface（零拷贝），
     *       false=解码帧回读到 GPU 纹理后再渲染（可后处理但多一次拷贝）</li>
     * </ul>
     * <p>
     * 各模式说明：
     * <ul>
     *   <li><b>DISABLED</b>：纯软解。{@code setHWDecoderEnabled(false, false)}，不启用任何硬件加速</li>
     *   <li><b>DECODING</b>：仅解码走硬件，渲染走 GPU。{@code setHWDecoderEnabled(true, false)}，
     *       禁用 Direct Rendering，解码后的帧通过 GPU 纹理渲染，支持截图/滤镜等后处理</li>
     *   <li><b>FULL</b>：全链路硬件加速。{@code setHWDecoderEnabled(true, true)}，
     *       开启 Direct Rendering，解码帧直接输出到 Surface，零拷贝、最低延迟、最低功耗，
     *       但不支持截图和后处理</li>
     *   <li><b>AUTOMATIC</b>：不设置，由 VLC 根据设备能力和格式自动决策</li>
     * </ul>
     * <p>
     * 注意：必须在 mMedia 创建之后、mMediaPlayer.setMedia() 之前调用
     */
    private void applyMediaHWAccel() {
        if (mMedia == null) return;
        switch (mHWAccel) {
            case DISABLED:
                // 纯软解：不启用硬件解码
                mMedia.setHWDecoderEnabled(false, false);
                break;
            case DECODING:
                // 仅硬件解码，关闭 Direct Rendering
                // 解码由 MediaCodec 完成，但渲染走 GPU 纹理（支持截图/滤镜后处理）
                mMedia.setHWDecoderEnabled(true, true);
                // DECODING 模式额外禁用 direct rendering，确保渲染不走硬件直连
                mMedia.addOption(":no-mediacodec-dr");
                mMedia.addOption(":no-omxil-dr");
                break;
            case FULL:
                // 全链路硬件加速：硬件解码 + Direct Rendering
                // 解码帧直接输出到 Surface，零拷贝、最低延迟
                mMedia.setHWDecoderEnabled(true, true);
                break;
            case AUTOMATIC:
            default:
                // 不设置，由 VLC 根据设备能力和格式自动决策
                break;
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

        Media newMedia = null;
        try {
            // 先断开 MediaPlayer 对旧 Media 的引用，再释放旧 Media
            if (mMediaPlayer != null) {
                mMediaPlayer.setMedia(null);
            }
            if (mMedia != null) {
                mMedia.release();
                mMedia = null;
            }

            // HLS 流：启动本地代理，修正 Content-Type 并强制 avformat 解复用器
            String playUrl = applyHlsProxyIfNeeded(path);

            newMedia = new Media(mLibVLC, Uri.parse(playUrl));
            mMedia = newMedia;

            // 在 Media 级别设置硬件解码控制（参考官方 VLCOptions.setMediaOptions）
            applyMediaHWAccel();

            // Media 级别：增大网络缓存，确保有足够数据开始解码
            mMedia.addOption(":network-caching=5000");
            mMedia.addOption(":file-caching=3000");

            // HLS 代理激活时，强制使用 avformat 解复用器，
            // 绕过 VLC 3.7.0 原生 adaptive 解复用器的 FakeESOut PCR bug
            if (mHlsProxy != null && sHlsProxyEnabled && playUrl.contains("127.0.0.1")) {
                mMedia.addOption(":demux=avformat");
            }

            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    mMedia.addOption(":" + entry.getKey() + "=" + entry.getValue());
                }
            }

            if (mIsLooping) {
                mMedia.addOption(":input-repeat=65535");
            }

            mMediaPlayer.setMedia(mMedia);

            // setMedia() 后 VLC native 层已拷贝 Media，Java 层引用可以立即释放
            // 后续需要查询轨道信息时从 MediaPlayer.getMedia() 获取
            mMedia.release();
            mMedia = null;

        } catch (Exception e) {
            Log.w(TAG, "Error setting data source", e);
            // 确保 Media 对象在异常时也能被正确释放
            if (newMedia != null && mMedia == newMedia) {
                try {
                    newMedia.release();
                } catch (Exception releaseException) {
                    Log.w(TAG, "Error releasing media after exception", releaseException);
                }
                mMedia = null;
            }
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 对 HLS 流（.m3u8）启动本地代理并返回代理 URL，非 HLS 流直接返回原始 URL
     */
    private String applyHlsProxyIfNeeded(String originalUrl) {
        if (!sHlsProxyEnabled) {
            return originalUrl;
        }

        // 仅对 .m3u8 地址启用代理
        String lowerUrl = originalUrl.toLowerCase(Locale.US);
        if (!lowerUrl.contains(".m3u8") && !lowerUrl.contains(".m3u")) {
            return originalUrl;
        }

        // 启动代理并获取代理 URL
        try {
            if (mHlsProxy == null) {
                mHlsProxy = new HlsProxy();
            }
            String proxyUrl = mHlsProxy.getProxyUrl(originalUrl);
            if (proxyUrl != null && !proxyUrl.equals(originalUrl)) {
                L.d("HLS proxy active, proxy URL: " + proxyUrl);
                return proxyUrl;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to start HLS proxy, using original URL", e);
        }

        return originalUrl;
    }

    /**
     * 设置播放数据源（assets文件）
     *
     * @param afd AssetFileDescriptor
     */
    @Override
    public void setDataSource(AssetFileDescriptor afd) {
        Media newMedia = null;
        try {
            // 先断开 MediaPlayer 对旧 Media 的引用，再释放旧 Media
            if (mMediaPlayer != null) {
                mMediaPlayer.setMedia(null);
            }
            if (mMedia != null) {
                mMedia.release();
                mMedia = null;
            }
            newMedia = new Media(mLibVLC, afd);
            mMedia = newMedia;

            // 在 Media 级别设置硬件解码控制
            applyMediaHWAccel();

            mMediaPlayer.setMedia(mMedia);

            // setMedia() 后 VLC native 层已拷贝 Media，Java 层引用可以立即释放
            mMedia.release();
            mMedia = null;
        } catch (Exception e) {
            Log.w(TAG, "Error setting asset data source", e);
            // 确保 Media 对象在异常时也能被正确释放
            if (newMedia != null && mMedia == newMedia) {
                try {
                    newMedia.release();
                } catch (Exception releaseException) {
                    Log.w(TAG, "Error releasing media after exception", releaseException);
                }
                mMedia = null;
            }
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 开始播放
     * <p>
     * 如果之前通过 stop() detach 了视图，恢复播放前需重新 attachViews
     * 确保视频输出正确绑定到 Surface。
     */
    @Override
    public void start() {
        if (mMediaPlayer != null) {
            try {
                if (!mViewsAttached) {
                    attachVlcViews();
                }
                applyScaleTypeToNative();
                mMediaPlayer.play();
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
     * <p>
     * 与 stop() 不同，pause() 仅暂停播放引擎，不 detachViews。
     * 画面会冻结在最后一帧，音频焦点由上层 BaseVideoView 管理。
     * <p>
     * 注意：如果需要在页面切换前确保 Surface 不被 native 层继续操作，
     * 应由调用方在适当时机（如 onStop/onDestroy）调用 stop() 或 release()。
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
     * <p>
     * 参考官方 PlaybackService.stop() 的实现：
     * stop 前先 detachViews 解除视频输出绑定，避免 native 层在 stop 后继续操作已释放的 surface。
     */
    @Override
    public void stop() {
        if (mMediaPlayer != null) {
            try {
                if (mViewsAttached) {
                    mMediaPlayer.detachViews();
                    mViewsAttached = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error detaching views during stop", e);
            }
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
        if (mMediaPlayer == null) {
            Log.w(TAG, "Cannot prepare: MediaPlayer is null");
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
            return;
        }

        // 检查 Media 是否已设置，注意 getMedia() 返回的引用需要释放
        IMedia media = null;
        try {
            media = mMediaPlayer.getMedia();
            if (media == null) {
                Log.w(TAG, "Cannot prepare: Media not set, call setDataSource() first");
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
                return;
            }
        } finally {
            // 释放 getMedia() 返回的引用
            if (media != null) {
                try {
                    media.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing media reference in prepareAsync", e);
                }
            }
        }

        mIsPreparing = true;
        try {
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
     * 重置播放器到初始状态
     * <p>
     * 与 release() 不同，reset() 不释放 LibVLC 和 MediaPlayer 实例，
     * 仅清理当前媒体资源，使播放器可以重新 setDataSource + prepareAsync。
     * <p>
     * 参考官方 PlayerController.restart() 的实现：
     * restart 会创建新的 MediaPlayer 实例并释放旧的，
     * 而 reset 仅清理当前状态保留实例。
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

            // 断开 MediaPlayer 对 Media 的持有，确保 Media 的 native 引用计数正确归零
            mMediaPlayer.setMedia(null);

            // 重置播放状态
            mIsPreparing = false;
            mBufferedPercent = 0;
            mVideoWidth = 0;
            mVideoHeight = 0;
        }
        // 释放 Media，此时 MediaPlayer 已不再持有引用
        if (mMedia != null) {
            mMedia.release();
            mMedia = null;
        }
        // 重置网速统计
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        lastSpeedBytes = 0;
        // 清理字幕列表（reset 后需要重新添加字幕）
        synchronized (mSubtitleUris) {
            mSubtitleUris.clear();
        }
        synchronized (mAddedSubtitles) {
            mAddedSubtitles.clear();
        }
        // 停止 HLS 代理（reset 后代理不再需要，下次 setDataSource 会重新创建）
        if (mHlsProxy != null) {
            mHlsProxy.stop();
            mHlsProxy = null;
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
     * <p>
     * HLS 流 seek 策略：
     * 1. 先检查播放器是否处于可 seek 状态（直播流可能不可 seek）
     * 2. 使用 fast seek 减少延迟，适合用户拖动进度条
     * 3. seek 前确保播放器正在播放，避免某些 HLS 流在暂停状态下 seek 异常
     *
     * @param time 目标位置（毫秒）
     */
    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null) return;
        try {
            // 检查是否可 seek（直播流可能不可 seek）
            if (!mMediaPlayer.isSeekable()) {
                Log.w(TAG, "Media is not seekable, ignoring seek to " + time);
                return;
            }
            // 边界保护：seek 到负数位置或超过时长的位置可能导致异常
            long duration = mMediaPlayer.getLength();
            if (duration > 0 && time > duration) {
                time = duration;
            }
            if (time < 0) {
                time = 0;
            }
            // 参考官方 PlayerController.setTime()：
            // 使用带 fast 参数的 seek，减少 seek 延迟
            // fast=true 表示快速 seek（可能不精确但响应快），适合用户拖动进度条
            mMediaPlayer.setTime(time, true);
        } catch (Exception e) {
            Log.w(TAG, "Error seeking to " + time, e);
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    /**
     * 释放播放器资源
     * <p>
     * 释放顺序至关重要，必须遵循：
     * 1. 移除布局监听器 — 避免回调到已释放的对象
     * 2. detachViews — 解除视频输出绑定
     * 3. setEventListener(null) — 解除事件监听
     * 4. MediaPlayer.setMedia(null) — 断开 MediaPlayer 对 Media 的引用
     * 5. Media.release() — 释放 Media（此时 MediaPlayer 已不再持有引用）
     * 6. MediaPlayer.release() — 释放 MediaPlayer
     * 7. LibVLC.release() — 最后释放 LibVLC
     * <p>
     * 参考官方 PlayerController.release() 的实现：
     * - 在 IO 线程执行 player.release() 避免阻塞主线程
     * - 增加超时检测防止 release 永久阻塞
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
            // 先解除事件监听，避免 release 过程中触发回调
            mMediaPlayer.setEventListener(null);
            // 断开 MediaPlayer 对 Media 的引用
            mMediaPlayer.setMedia(null);
        }

        if (mMedia != null) {
            mMedia.release();
            mMedia = null;
        }

        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        if (mLibVLC != null) {
            mLibVLC.release();
            mLibVLC = null;
        }

        // 停止 HLS 代理
        if (mHlsProxy != null) {
            mHlsProxy.stop();
            mHlsProxy = null;
        }

        // 清理所有引用和状态
        mVlcRenderView = null;
        mIsPreparing = false;
        mBufferedPercent = 0;
        mVideoWidth = 0;
        mVideoHeight = 0;
        mSpeed = 1.0f;
        mIsLooping = false;

        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        lastSpeedBytes = 0;
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
     * <p>
     * 注意：某些 HLS 流在 seek 后或分段切换时，getTime() 可能返回负值或异常值，
     * 这里做了边界保护。
     *
     * @return 当前播放位置（毫秒），异常时返回 0
     */
    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null) return 0;
        try {
            long time = mMediaPlayer.getTime();
            return Math.max(0, time);
        } catch (Exception e) {
            Log.w(TAG, "Error getting current position", e);
            return 0;
        }
    }

    /**
     * 获取视频总时长
     * <p>
     * 注意：HLS 直播流（没有 END 标记的 m3u8）的 duration 通常为 0，
     * 这是 VLC 的正常行为——直播流没有固定的总时长。
     * 上层应判断 duration==0 时按直播流 UI 处理（隐藏总时长、禁用进度条拖动）。
     * <p>
     * VOD 类型的 HLS 流在解析完成后会返回正确的 duration，
     * 但某些分段间时间戳不连续的流可能返回不准确的值。
     *
     * @return 视频总时长（毫秒），直播流返回 0
     */
    @Override
    public long getDuration() {
        if (mMediaPlayer == null) return 0;
        try {
            return mMediaPlayer.getLength();
        } catch (Exception e) {
            Log.w(TAG, "Error getting duration", e);
            return 0;
        }
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
                int volume = (int) ((leftVolume + rightVolume) / 2.0f * 100.0f);
                mMediaPlayer.setVolume(volume);
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
     * <p>
     * 注意：循环播放需要在每次 setDataSource 时通过 Media 选项生效（:input-repeat）。
     * 如果已在播放中调用，需要 reset 后重新 setDataSource + prepareAsync 才能生效。
     *
     * @param isLooping true 表示循环播放
     */
    @Override
    public void setLooping(boolean isLooping) {
        mIsLooping = isLooping;
    }

    /**
     * 获取当前循环播放状态
     *
     * @return true 表示循环播放已开启
     */
    public boolean isLooping() {
        return mIsLooping;
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

            // 参考官方 changeMediaPlayerLayout 中的 orientation 判断
            // Orientation: 0=Normal, 1=90°, 2=180°, 3=270°
            //              4=Mirror, 5=Mirror+90°, 6=Mirror+180°, 7=Mirror+270°
            // 当 orientation 为 1/3/5/7 时需要交换宽高（90°或270°旋转）
            boolean needSwap = (videoTrack.orientation == 1
                    || videoTrack.orientation == 3
                    || videoTrack.orientation == 5
                    || videoTrack.orientation == 7);
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
                // 参考官方 PlayerController.setRate()：
                // 在已释放的 MediaPlayer 上调用 setRate 会抛异常
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
     * 设置播放速率（参考官方 PlayerController.setRate）
     * <p>
     * 与 setSpeed 的区别：此方法参考官方实现，增加了对播放器释放状态的检查，
     * 避免在已释放的 MediaPlayer 上调用导致崩溃。
     *
     * @param rate 播放速率（1.0 为正常速度）
     */
    public void setRate(float rate) {
        if (mMediaPlayer == null) return;
        try {
            mSpeed = rate;
            mMediaPlayer.setRate(rate);
        } catch (Exception e) {
            Log.w(TAG, "Error setting rate", e);
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

    // 缓存 unsupported 检测结果，避免每次查询
    private Boolean mUnsupported = null;

    /**
     * 检查流量统计是否不支持
     */
    private boolean unsupported() {
        if (mUnsupported != null) return mUnsupported;
        if (mAppContext == null) {
            mUnsupported = true;
            return true;
        }
        mUnsupported = TrafficStats.getUidRxBytes(mAppContext.getApplicationInfo().uid) == TrafficStats.UNSUPPORTED;
        return mUnsupported;
    }

    /**
     * 获取当前下载速度
     * <p>
     * 使用 TrafficStats.getTotalRxBytes() 获取设备总接收字节数，
     * 通过两次调用之间的差值计算瞬时速度。
     * 注意：这反映的是整个设备的网络流量，不仅仅是当前播放器的流量。
     * 对于精确到单个流的测速，需要使用 VLC 自带的统计信息（--stats）。
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

    private long lastSpeedBytes = 0;

    /**
     * VLC播放器事件回调
     * <p>
     * 参考官方 VideoPlayerActivity.onMediaPlayerEvent() 的事件处理逻辑，
     * 补充了轨道变化（ESAdded/ESDeleted/ESSelected）、可跳转/可暂停状态变化、
     * 时长变化等事件的处理。
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
                // 官方在 Stopped 时重置播放状态，这里也清除 preparing 标志
                mIsPreparing = false;
                break;

            case MediaPlayer.Event.EndReached:
                mPlayerEventListener.onCompletion();
                break;

            case MediaPlayer.Event.EncounteredError:
                Log.w(TAG, "VLC Event: Error");
                mIsPreparing = false;
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
                // Vout 事件表示视频输出已创建，此时可以获取准确的视频尺寸
                notifyVideoSize();
                // 在视频输出准备好后注入字幕（确保渲染管线已就绪）
                addAllSubtitlesOnVout();
                // 重新应用缩放（视频尺寸可能已更新）
                if (mVlcRenderView != null && mEnableVlcSubtitles) {
                    applyScaleTypeToNative();
                }
                break;

            // === 以下事件为新增处理，参考官方实现 ===

            case MediaPlayer.Event.LengthChanged:
                // 时长变化时刷新视频尺寸（某些格式在解析完成前无法获取准确尺寸）
                notifyVideoSize();
                // HLS VOD 流在解析过程中时长可能从 0 变为正确值，通知上层更新 UI
                if (mMediaPlayer != null) {
                    long duration = mMediaPlayer.getLength();
                    mPlayerEventListener.onInfo(MEDIA_INFO_DURATION_CHANGED, (int) duration);
                }
                break;

            case MediaPlayer.Event.SeekableChanged:
                // 可跳转状态变化（如直播流从不可 seek 变为可 seek）
                // 上层可根据此事件更新 UI（如启用/禁用进度条）
                if (mMediaPlayer != null) {
                    boolean seekable = mMediaPlayer.isSeekable();
                    // 当变为可 seek 时，刷新一次视频尺寸和时长
                    if (seekable) {
                        notifyVideoSize();
                    }
                }
                break;

            case MediaPlayer.Event.PausableChanged:
                // 可暂停状态变化
                break;

            case MediaPlayer.Event.ESAdded:
                // 新轨道添加（音频/视频/字幕）
                // 如果是视频轨道添加，刷新视频尺寸
                if (event.getEsChangedType() == IMedia.Track.Type.Video) {
                    notifyVideoSize();
                    // 新增视频轨道后重新应用缩放
                    applyScaleTypeToNative();
                }
                break;

            case MediaPlayer.Event.ESDeleted:
                // 轨道删除
                break;

            case MediaPlayer.Event.ESSelected:
                // 轨道选中变化
                // 视频轨道变化时重新计算缩放布局
                if (event.getEsChangedType() == IMedia.Track.Type.Video) {
                    applyScaleTypeToNative();
                }
                break;

            default:
                break;
        }
    }

    /**
     * 通知视频尺寸变化
     * <p>
     * 优先从 MediaPlayer.getMedia() 获取轨道信息（因为 setDataSource 后 mMedia 已被释放），
     * 如果MediaPlayer 也没有 Media，则尝试从 mMedia 获取（兼容未释放的场景）。
     * 如果都获取不到有效尺寸，则使用默认值 1280x720 避免上层收到 0x0 导致布局异常。
     * <p>
     * 重要：MediaPlayer.getMedia() 返回的引用会增加 native 引用计数，使用完必须 release()
     */
    private void notifyVideoSize() {
        if (mPlayerEventListener == null) return;
        IMedia media = null;
        try {
            int width = 0;
            int height = 0;

            // 优先从 MediaPlayer 获取（setDataSource 后 mMedia 已释放，但 MediaPlayer 内部持有引用）
            if (mMediaPlayer != null) {
                try {
                    media = mMediaPlayer.getMedia();
                    if (media != null) {
                        int trackCount = media.getTrackCount();
                        for (int i = 0; i < trackCount; i++) {
                            IMedia.Track track = media.getTrack(i);
                            if (track != null && track.type == IMedia.Track.Type.Video) {
                                IMedia.VideoTrack videoTrack = (IMedia.VideoTrack) track;
                                width = videoTrack.width;
                                height = videoTrack.height;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting video size from MediaPlayer", e);
                } finally {
                    // 关键：getMedia() 返回的引用必须释放，否则会泄漏 native 资源
                    if (media != null) {
                        try {
                            media.release();
                        } catch (Exception e) {
                            Log.w(TAG, "Error releasing media reference", e);
                        }
                    }
                }
            }

            // 后备：从 mMedia 获取（如果尚未释放）
            if ((width <= 0 || height <= 0) && mMedia != null) {
                try {
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
                } catch (Exception e) {
                    Log.w(TAG, "Error getting video size from mMedia", e);
                }
            }

            // 使用默认值兜底，避免上层收到 0x0
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
                    // 为字幕和音轨添加一个分组ID作为区别，避免音轨和字幕的禁用轨道ID都为-1，造成设置时混乱
                    // {"language":"","name":"Disable","renderId":0,"selected":false,"trackGroupId":0,"trackId":-1}
                    bean.trackGroupId = TRACK_GROUD_AUDIO;
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
                    bean.trackGroupId = TRACK_GROUD_SUBTITLE;
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
        if (trackBean == null || mMediaPlayer == null) return false;
        if (trackBean.trackGroupId == TRACK_GROUD_AUDIO) {
            return setAudioTrack(trackBean.trackId);
        } else if (trackBean.trackGroupId == TRACK_GROUD_SUBTITLE) {
            return setSubtitleTrack(trackBean.trackId);
        }
        return false;
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

    // ==================== 音频/字幕延迟调节 ====================
    // 参考官方 PlaybackService.setAudioDelay / setSpuDelay

    /**
     * 设置音频延迟（毫秒）
     * <p>
     * 正值表示音频延后，负值表示音频提前。
     * 用于音画同步调节。
     *
     * @param delayMs 音频延迟（毫秒）
     */
    public void setAudioDelay(long delayMs) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setAudioDelay(delayMs);
            } catch (Exception e) {
                Log.w(TAG, "Error setting audio delay", e);
            }
        }
    }

    /**
     * 获取当前音频延迟（毫秒）
     *
     * @return 音频延迟，0 表示未设置
     */
    public long getAudioDelay() {
        if (mMediaPlayer != null) {
            try {
                return mMediaPlayer.getAudioDelay();
            } catch (Exception e) {
                Log.w(TAG, "Error getting audio delay", e);
            }
        }
        return 0;
    }

    /**
     * 设置字幕延迟（毫秒）
     * <p>
     * 正值表示字幕延后，负值表示字幕提前。
     *
     * @param delayMs 字幕延迟（毫秒）
     */
    public void setSpuDelay(long delayMs) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setSpuDelay(delayMs);
            } catch (Exception e) {
                Log.w(TAG, "Error setting spu delay", e);
            }
        }
    }

    /**
     * 获取当前字幕延迟（毫秒）
     *
     * @return 字幕延迟，0 表示未设置
     */
    public long getSpuDelay() {
        if (mMediaPlayer != null) {
            try {
                return mMediaPlayer.getSpuDelay();
            } catch (Exception e) {
                Log.w(TAG, "Error getting spu delay", e);
            }
        }
        return 0;
    }

    // ==================== 音频数字输出（Passthrough） ====================
    // 参考官方 VLCOptions.isAudioDigitalOutputEnabled / PlayerOptionsDelegate.togglePassthrough

    /**
     * 设置是否开启音频数字输出（Passthrough）
     * <p>
     * 开启后，AC3/DTS 等编码的音频流将不经解码直接输出到外接解码器（如音响/功放），
     * 需要硬件和连接设备支持。
     *
     * @param enabled true 开启数字输出
     * @return true 表示设置成功
     */
    public boolean setAudioDigitalOutputEnabled(boolean enabled) {
        if (mMediaPlayer != null) {
            try {
                return mMediaPlayer.setAudioDigitalOutputEnabled(enabled);
            } catch (Exception e) {
                Log.w(TAG, "Error setting audio digital output", e);
            }
        }
        return false;
    }

    /**
     * 检查当前是否支持音频数字输出（Passthrough）
     *
     * @return true 表示当前媒体支持 Passthrough
     */
    public boolean canDoPassthrough() {
        if (mMediaPlayer != null) {
            try {
                // 参考官方 PlayerController.canDoPassthrough()
                return mMediaPlayer.hasMedia() && mMediaPlayer.canDoPassthrough();
            } catch (Exception e) {
                Log.w(TAG, "Error checking passthrough capability", e);
            }
        }
        return false;
    }

    // ==================== 视频轨道控制 ====================
    // 参考官方 PlaybackService.setVideoTrackEnabled

    /**
     * 设置视频轨道是否启用
     * <p>
     * 禁用视频轨道后，仅播放音频（类似音频播放器模式）。
     * 重新启用后视频恢复显示。
     *
     * @param enabled true 启用视频轨道
     */
    public void setVideoTrackEnabled(boolean enabled) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setVideoTrackEnabled(enabled);
            } catch (Exception e) {
                Log.w(TAG, "Error setting video track enabled", e);
            }
        }
    }

    // ==================== 章节/标题导航 ====================
    // 参考官方 PlaybackService.setChapterIdx / setTitleIdx

    /**
     * 获取指定标题的章节列表
     *
     * @param title 标题索引（-1 表示当前标题）
     * @return 章节数组，无章节时返回空数组
     */
    public MediaPlayer.Chapter[] getChapters(int title) {
        if (mMediaPlayer != null) {
            try {
                return mMediaPlayer.getChapters(title);
            } catch (Exception e) {
                Log.w(TAG, "Error getting chapters", e);
            }
        }
        return new MediaPlayer.Chapter[0];
    }

    /**
     * 获取当前标题索引
     *
     * @return 标题索引，-1 表示无标题
     */
    public int getTitleIdx() {
        if (mMediaPlayer != null) {
            try {
                return mMediaPlayer.getTitle();
            } catch (Exception e) {
                Log.w(TAG, "Error getting title index", e);
            }
        }
        return -1;
    }

    /**
     * 设置标题索引
     *
     * @param title 标题索引
     */
    public void setTitleIdx(int title) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setTitle(title);
            } catch (Exception e) {
                Log.w(TAG, "Error setting title index", e);
            }
        }
    }

    /**
     * 获取当前章节索引
     *
     * @return 章节索引，-1 表示无章节
     */
    public int getChapterIdx() {
        if (mMediaPlayer != null) {
            try {
                return mMediaPlayer.getChapter();
            } catch (Exception e) {
                Log.w(TAG, "Error getting chapter index", e);
            }
        }
        return -1;
    }

    /**
     * 设置章节索引，跳转到指定章节
     *
     * @param chapter 章节索引
     */
    public void setChapterIdx(int chapter) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setChapter(chapter);
            } catch (Exception e) {
                Log.w(TAG, "Error setting chapter index", e);
            }
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