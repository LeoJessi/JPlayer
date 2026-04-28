package top.jessi.videoplayer.vlc;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.videolan.libvlc.util.VLCVideoLayout;

import top.jessi.videoplayer.player.AbstractPlayer;
import top.jessi.videoplayer.render.IRenderView;

/**
 * 专为VLC播放器设计的渲染视图
 * <p>
 * 渲染模式由VlcRenderView内部决定：
 * - 默认使用SurfaceView（useTextureView=false）
 * - 可通过 setUseTextureView(true) 切换为TextureView
 * - VlcPlayer通过getUseTextureView()查询当前模式
 */
public class VlcRenderView extends FrameLayout implements IRenderView {

    private static final String TAG = "JPlayer—VlcRenderView";

    @Nullable
    private AbstractPlayer mMediaPlayer;
    private VLCVideoLayout mVlcVideoLayout;

    /**
     * 渲染模式标志：true=TextureView, false=SurfaceView
     * 默认为SurfaceView
     */
    private boolean mUseTextureView = false;

    public VlcRenderView(Context context) {
        super(context);
        init();
    }

    public VlcRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VlcRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mVlcVideoLayout = new VLCVideoLayout(getContext());
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        addView(mVlcVideoLayout, params);
    }

    public VLCVideoLayout getVlcVideoLayout() {
        return mVlcVideoLayout;
    }

    public boolean getUseTextureView() {
        return mUseTextureView;
    }

    public void setUseTextureView(boolean useTextureView) {
        this.mUseTextureView = useTextureView;
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        if (player instanceof VlcPlayer) {
            ((VlcPlayer) player).setVlcRenderView(this);
        }
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
    }

    @Override
    public void setVideoRotation(int degree) {
    }

    @Override
    public void setScaleType(int scaleType) {
        if (mMediaPlayer instanceof VlcPlayer) {
            ((VlcPlayer) mMediaPlayer).updateVlcScaleType(scaleType);
        }
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public Bitmap doScreenShot() {
        if (mVlcVideoLayout == null) return null;
        if (mUseTextureView) {
            TextureView textureView = mVlcVideoLayout.findViewById(org.videolan.R.id.texture_video);
            if (textureView != null && textureView.isAvailable()) {
                try {
                    return textureView.getBitmap();
                } catch (Exception e) {
                    Log.w(TAG, "Error getting bitmap from TextureView", e);
                }
            }
        }
        return null;
    }

    @Override
    public void release() {
        mMediaPlayer = null;
        mUseTextureView = false;
    }
}
