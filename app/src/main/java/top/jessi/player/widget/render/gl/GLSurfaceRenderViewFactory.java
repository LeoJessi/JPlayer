package top.jessi.player.widget.render.gl;

import android.content.Context;

import top.jessi.videoplayer.render.IRenderView;
import top.jessi.videoplayer.render.RenderViewFactory;

public class GLSurfaceRenderViewFactory extends RenderViewFactory {

    public static GLSurfaceRenderViewFactory create() {
        return new GLSurfaceRenderViewFactory();
    }

    @Override
    public IRenderView createRenderView(Context context) {
        return new GLSurfaceRenderView(context);
    }
}
