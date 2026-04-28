package top.jessi.videoplayer.player;

import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import top.jessi.videoplayer.render.RenderViewFactory;

/**
 * 抽象的播放器，继承此接口扩展自己的播放器
 * Created by Doikki on 2017/12/21.
 */
public abstract class AbstractPlayer {

    /**
     * 视频/音频开始渲染
     */
    public static final int MEDIA_INFO_RENDERING_START = 3;

    /**
     * 缓冲开始
     */
    public static final int MEDIA_INFO_BUFFERING_START = 701;

    /**
     * 缓冲结束
     */
    public static final int MEDIA_INFO_BUFFERING_END = 702;

    /**
     * 视频旋转信息
     */
    public static final int MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001;

    /**
     * 播放器事件回调
     */
    protected PlayerEventListener mPlayerEventListener;

    /**
     * 外部字幕 Uri 列表，各播放器在适当时机（如 Vout 事件、onPrepared 等）将字幕注入到内核
     */
    protected final List<Uri> mSubtitleUris = new ArrayList<>();

    // ==================== 外部字幕管理 ====================

    /**
     * 添加一个外部字幕（Uri 形式）
     * <p>
     * 支持本地文件 Uri（如 file:///sdcard/sub.srt）和网络 Uri（如 http://example.com/sub.srt）
     * 各播放器需在播放准备阶段（如 Vout 回调、onPrepared 等时机）将字幕注入内核
     *
     * @param uri 字幕文件的 Uri
     */
    public void addSubtitle(Uri uri) {
        if (uri == null) return;
        synchronized (mSubtitleUris) {
            if (!mSubtitleUris.contains(uri)) {
                mSubtitleUris.add(uri);
            }
        }
    }

    /**
     * 添加一个外部字幕（File 形式）
     *
     * @param file 字幕文件
     * @return this 链式调用
     */
    public void addSubtitle(File file) {
        if (file == null || !file.exists()) return;
        addSubtitle(Uri.fromFile(file));
    }

    /**
     * 批量添加外部字幕（Uri 列表）
     *
     * @param uris 字幕 Uri 列表
     * @return this 链式调用
     */
    public void addSubtitles(List<Uri> uris) {
        if (uris == null) return;
        for (Uri uri : uris) {
            addSubtitle(uri);
        }
    }

    /**
     * 批量添加外部字幕（File 列表）
     *
     * @param files 字幕文件列表
     * @return this 链式调用
     */
    public void addSubtitlesByFile(List<File> files) {
        if (files == null) return;
        for (File file : files) {
            addSubtitle(file);
        }
    }

    protected RenderViewFactory mRenderViewFactory = null;

    // 抽象一个渲染器工厂方法，供外部调用获取渲染器工厂实例，如VLC播放器需要提供自己的渲染器工厂来创建SurfaceView或TextureView
    public RenderViewFactory getRenderViewFactory(boolean isTextureView) {
        return mRenderViewFactory;
    }

    /**
     * 初始化播放器实例
     */
    public abstract void initPlayer();

    /**
     * 设置播放地址
     *
     * @param path    播放地址
     * @param headers 播放地址请求头
     */
    public abstract void setDataSource(String path, Map<String, String> headers);

    /**
     * 用于播放raw和asset里面的视频文件
     */
    public abstract void setDataSource(AssetFileDescriptor fd);

    /**
     * 播放
     */
    public abstract void start();

    /**
     * 暂停
     */
    public abstract void pause();

    /**
     * 停止
     */
    public abstract void stop();

    /**
     * 准备开始播放（异步）
     */
    public abstract void prepareAsync();

    /**
     * 重置播放器
     */
    public abstract void reset();

    /**
     * 是否正在播放
     */
    public abstract boolean isPlaying();

    /**
     * 调整进度
     */
    public abstract void seekTo(long time);

    /**
     * 释放播放器
     */
    public abstract void release();

    /**
     * 获取当前播放的位置
     */
    public abstract long getCurrentPosition();

    /**
     * 获取视频总时长
     */
    public abstract long getDuration();

    /**
     * 获取缓冲百分比
     */
    public abstract int getBufferedPercentage();

    /**
     * 设置渲染视频的View,主要用于TextureView
     */
    public abstract void setSurface(Surface surface);

    /**
     * 设置渲染视频的View,主要用于SurfaceView
     */
    public abstract void setDisplay(SurfaceHolder holder);

    /**
     * 设置音量
     */
    public abstract void setVolume(float v1, float v2);

    /**
     * 设置是否循环播放
     */
    public abstract void setLooping(boolean isLooping);

    /**
     * 设置其他播放配置
     */
    public abstract void setOptions();

    /**
     * 设置播放速度
     */
    public abstract void setSpeed(float speed);

    /**
     * 获取播放速度
     */
    public abstract float getSpeed();

    /**
     * 获取当前缓冲的网速
     */
    public abstract long getTcpSpeed();

    /**
     * 获取音轨和字幕轨道信息
     *
     * @return 包含音轨和字幕轨道信息的 TrackInfo 对象
     */
    public abstract TrackInfo getTrackInfo();

    /**
     * 切换音轨或字幕轨道
     *
     * @param trackBean 轨道信息对象，为 null 时禁用字幕（各播放器自行处理）
     * @return true 表示切换成功，false 表示失败
     */
    public abstract boolean setTrack(TrackInfoBean trackBean);

    /**
     * 绑定VideoView
     */
    public void setPlayerEventListener(PlayerEventListener playerEventListener) {
        this.mPlayerEventListener = playerEventListener;
    }

    public interface PlayerEventListener {

        void onError();

        void onCompletion();

        void onInfo(int what, int extra);

        void onPrepared();

        void onVideoSizeChanged(int width, int height);

    }

}
