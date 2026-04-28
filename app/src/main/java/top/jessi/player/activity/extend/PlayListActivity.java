package top.jessi.player.activity.extend;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.bean.VideoBean;
import top.jessi.player.util.DataUtil;
import top.jessi.player.widget.component.PlayerMonitor;
import top.jessi.videocontroller.StandardVideoController;
import top.jessi.videocontroller.component.CompleteView;
import top.jessi.videocontroller.component.ErrorView;
import top.jessi.videocontroller.component.GestureView;
import top.jessi.videocontroller.component.PrepareView;
import top.jessi.videocontroller.component.TitleView;
import top.jessi.videocontroller.component.VodControlView;
import top.jessi.videoplayer.player.VideoView;

import java.util.List;

/**
 * 连续播放一个列表
 * Created by Doikki on 2017/4/7.
 */

public class PlayListActivity extends BaseActivity {

    private List<VideoBean> data = DataUtil.getVideoList();

    private StandardVideoController mController;
    private TitleView mTitleView;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_layout_common;
    }

    @Override
    protected int getTitleResId() {
        return R.string.str_play_list;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.video_view);
        mController = new StandardVideoController(this);
        addControlComponents();
        mController.addControlComponent(new PlayerMonitor());

        //加载第一条数据
        VideoBean videoBean = data.get(0);
        mVideoView.setUrl(videoBean.getUrl());
        mTitleView.setTitle(videoBean.getTitle());
        mVideoView.setVideoController(mController);

        //监听播放结束
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            private int mCurrentVideoPosition;
            @Override
            public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    if (data != null) {
                        mCurrentVideoPosition++;
                        if (mCurrentVideoPosition >= data.size()) return;
                        mVideoView.release();
                        //重新设置数据
                        VideoBean videoBean = data.get(mCurrentVideoPosition);
                        mVideoView.setUrl(videoBean.getUrl());
                        mTitleView.setTitle(videoBean.getTitle());
                        mVideoView.setVideoController(mController);
                        //开始播放
                        mVideoView.start();
                    }
                }
            }
        });

        mVideoView.start();
    }

    private void addControlComponents() {
        CompleteView completeView = new CompleteView(this);
        ErrorView errorView = new ErrorView(this);
        PrepareView prepareView = new PrepareView(this);
        prepareView.setClickStart();
        mTitleView = new TitleView(this);
        VodControlView vodControlView = new VodControlView(this);
        GestureView gestureView = new GestureView(this);
        mController.addControlComponent(completeView, errorView, prepareView, mTitleView, vodControlView, gestureView);
    }
}
