package top.jessi.player.activity.extend;

import com.danikula.videocache.HttpProxyCacheServer;
import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.util.DataUtil;
import top.jessi.player.util.cache.ProxyVideoCacheManager;
import top.jessi.videocontroller.StandardVideoController;
import top.jessi.videoplayer.player.VideoView;

public class CacheActivity extends BaseActivity<VideoView> {

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_layout_common;
    }

    @Override
    protected int getTitleResId() {
        return R.string.str_cache;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.video_view);
        HttpProxyCacheServer cacheServer = ProxyVideoCacheManager.getProxy(this);
        String proxyUrl = cacheServer.getProxyUrl(DataUtil.SAMPLE_URL);
        mVideoView.setUrl(proxyUrl);
        StandardVideoController controller = new StandardVideoController(this);
        controller.addDefaultControlComponent(getString(R.string.str_cache), false);
        mVideoView.setVideoController(controller);
        mVideoView.start();

        //删除url对应默认缓存文件
//        ProxyVideoCacheManager.clearDefaultCache(this, URL);
        //清除缓存文件中的所有缓存
//        ProxyVideoCacheManager.clearAllCache(this);
    }
}
