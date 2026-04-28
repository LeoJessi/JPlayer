package top.jessi.player.fragment.list;

import top.jessi.player.util.Utils;
import top.jessi.player.widget.controller.PortraitWhenFullScreenController;
import top.jessi.videocontroller.component.CompleteView;
import top.jessi.videocontroller.component.ErrorView;
import top.jessi.videocontroller.component.GestureView;
import top.jessi.videocontroller.component.TitleView;
import top.jessi.videoplayer.player.VideoView;

/**
 * 全屏后手动横屏，并不完美，仅做参考
 */
public class RecyclerViewPortraitFragment extends RecyclerViewAutoPlayFragment {

    @Override
    protected void initVideoView() {
        mVideoView = new VideoView(getActivity());
        mVideoView.setOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_IDLE) {
                    Utils.removeViewFormParent(mVideoView);
                    mLastPos = mCurPos;
                    mCurPos = -1;
                }
            }
        });
        mController = new PortraitWhenFullScreenController(getActivity());
        mErrorView = new ErrorView(getActivity());
        mController.addControlComponent(mErrorView);
        mCompleteView = new CompleteView(getActivity());
        mController.addControlComponent(mCompleteView);
        mTitleView = new TitleView(getActivity());
        mController.addControlComponent(mTitleView);
        mController.addControlComponent(new GestureView(getActivity()));
        mController.setEnableOrientation(true);
        mVideoView.setVideoController(mController);
    }

    @Override
    public void onItemChildClick(int position) {
        mVideoView.startFullScreen();
        super.onItemChildClick(position);
    }
}
