package top.jessi.player.widget.render;

import android.content.Context;

import top.jessi.videoplayer.render.IRenderView;
import top.jessi.videoplayer.render.RenderViewFactory;

public class SurfaceRenderViewFactory extends RenderViewFactory {

    public static SurfaceRenderViewFactory create() {
        return new SurfaceRenderViewFactory();
    }

    @Override
    public IRenderView createRenderView(Context context) {
        return new SurfaceRenderView(context);
    }
}
