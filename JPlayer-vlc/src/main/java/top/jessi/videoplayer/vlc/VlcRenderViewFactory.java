package top.jessi.videoplayer.vlc;

import android.content.Context;

import top.jessi.videoplayer.render.RenderViewFactory;

public class VlcRenderViewFactory extends RenderViewFactory {

    private boolean mUseTextureView = false;

    public static VlcRenderViewFactory create() {
        return new VlcRenderViewFactory();
    }

    /**
     * 创建VLC渲染视图工厂，指定渲染模式
     * @param useTextureView true=TextureView, false=SurfaceView
     */
    public static VlcRenderViewFactory create(boolean useTextureView) {
        VlcRenderViewFactory factory = new VlcRenderViewFactory();
        factory.mUseTextureView = useTextureView;
        return factory;
    }

    @Override
    public VlcRenderView createRenderView(Context context) {
        VlcRenderView renderView = new VlcRenderView(context);
        renderView.setUseTextureView(mUseTextureView);
        return renderView;
    }
}
