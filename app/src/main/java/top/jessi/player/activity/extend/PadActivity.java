package top.jessi.player.activity.extend;

import android.view.View;
import android.widget.Toast;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.util.DataUtil;
import top.jessi.videocontroller.StandardVideoController;

public class PadActivity extends BaseActivity {

    private StandardVideoController mController;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_pad;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.video_view);

        mVideoView.setUrl(DataUtil.SAMPLE_URL);

        mController = new StandardVideoController(this);
        mController.addDefaultControlComponent("pad", false);

        mController.findViewById(R.id.fullscreen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mVideoView.isFullScreen()) {
                    mVideoView.stopFullScreen();
                } else {
                    mVideoView.startFullScreen();
                }
            }
        });

        mController.findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mVideoView.stopFullScreen();
            }
        });

        mVideoView.setVideoController(mController);

        mVideoView.start();
    }


    @Override
    public void onBackPressed() {
        if (mController.isLocked()) {
            mController.show();
            Toast.makeText(this, top.jessi.videocontroller.R.string.dkplayer_lock_tip, Toast.LENGTH_SHORT).show();
            return;
        }
        if (mVideoView.isFullScreen()) {
            mVideoView.stopFullScreen();
            return;
        }
        finish();
    }
}
