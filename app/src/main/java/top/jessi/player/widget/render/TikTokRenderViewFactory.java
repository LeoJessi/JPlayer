package top.jessi.player.widget.render;

import android.content.Context;

import top.jessi.videoplayer.render.IRenderView;
import top.jessi.videoplayer.render.RenderViewFactory;
import top.jessi.videoplayer.render.TextureRenderView;

public class TikTokRenderViewFactory extends RenderViewFactory {

    public static TikTokRenderViewFactory create() {
        return new TikTokRenderViewFactory();
    }

    @Override
    public IRenderView createRenderView(Context context) {
        return new TikTokRenderView(new TextureRenderView(context));
    }
}
