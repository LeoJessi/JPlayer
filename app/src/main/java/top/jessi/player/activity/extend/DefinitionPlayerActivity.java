package top.jessi.player.activity.extend;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.widget.component.DefinitionControlView;
import top.jessi.videocontroller.StandardVideoController;
import top.jessi.videocontroller.component.CompleteView;
import top.jessi.videocontroller.component.ErrorView;
import top.jessi.videocontroller.component.GestureView;
import top.jessi.videocontroller.component.PrepareView;
import top.jessi.videocontroller.component.TitleView;
import top.jessi.videoplayer.player.VideoView;

import java.util.LinkedHashMap;

/**
 * 清晰度切换
 * Created by Doikki on 2017/4/7.
 */

public class DefinitionPlayerActivity extends BaseActivity<VideoView> implements DefinitionControlView.OnRateSwitchListener {

    private StandardVideoController mController;
    private DefinitionControlView mDefinitionControlView;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_layout_common;
    }

    @Override
    protected int getTitleResId() {
        return R.string.str_definition;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.video_view);

        mController = new StandardVideoController(this);
        addControlComponents();

        LinkedHashMap<String, String> videos = new LinkedHashMap<>();
        videos.put("标清", "http://34.92.158.191:8080/test-sd.mp4");
        videos.put("高清", "http://34.92.158.191:8080/test-hd.mp4");
        mDefinitionControlView.setData(videos);
        mVideoView.setVideoController(mController);
        mVideoView.setUrl(videos.get("标清"));//默认播放标清
        mVideoView.start();
    }

    private void addControlComponents() {
        CompleteView completeView = new CompleteView(this);
        ErrorView errorView = new ErrorView(this);
        PrepareView prepareView = new PrepareView(this);
        prepareView.setClickStart();
        TitleView titleView = new TitleView(this);
        mDefinitionControlView = new DefinitionControlView(this);
        mDefinitionControlView.setOnRateSwitchListener(this);
        GestureView gestureView = new GestureView(this);
        mController.addControlComponent(completeView, errorView, prepareView, titleView, mDefinitionControlView, gestureView);
    }

    @Override
    public void onRateChange(String url) {
        mVideoView.setUrl(url);
        mVideoView.replay(false);
    }
}
