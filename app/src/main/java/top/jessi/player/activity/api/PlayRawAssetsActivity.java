package top.jessi.player.activity.api;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.view.View;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;

import java.io.IOException;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.util.Utils;
import top.jessi.videocontroller.StandardVideoController;
import top.jessi.videoplayer.exo.ExoMediaPlayerFactory;
import top.jessi.videoplayer.player.VideoView;

/**
 * 播放raw/assets视频
 */

public class PlayRawAssetsActivity extends BaseActivity<VideoView> {

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_play_raw_assets;
    }

    @Override
    protected int getTitleResId() {
        return R.string.str_raw_or_assets;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.player);
        StandardVideoController controller = new StandardVideoController(this);
        controller.addDefaultControlComponent(getString(R.string.str_raw_or_assets), false);
        mVideoView.setVideoController(controller);
    }

    public void onButtonClick(View view) {
        mVideoView.release();
        Object playerFactory = Utils.getCurrentPlayerFactoryInVideoView(mVideoView);

        int id = view.getId();
        if (id == R.id.btn_raw) {
            if (playerFactory instanceof ExoMediaPlayerFactory) { //ExoPlayer
                DataSpec dataSpec = new DataSpec(RawResourceDataSource.buildRawResourceUri(R.raw.movie));
                RawResourceDataSource rawResourceDataSource = new RawResourceDataSource(this);
                try {
                    rawResourceDataSource.open(dataSpec);
                } catch (RawResourceDataSource.RawResourceDataSourceException e) {
                    e.printStackTrace();
                }
                String url = rawResourceDataSource.getUri().toString();
                mVideoView.setUrl(url);
            } else { //MediaPlayer,IjkPlayer
                String url = "android.resource://" + getPackageName() + "/" + R.raw.movie;
                mVideoView.setUrl(url);
            }
        } else if (id == R.id.btn_assets) {
            if (playerFactory instanceof ExoMediaPlayerFactory) { //ExoPlayer
                mVideoView.setUrl("file:///android_asset/" + "test.mp4");
            } else { //MediaPlayer,IjkPlayer
                AssetManager am = getResources().getAssets();
                AssetFileDescriptor afd = null;
                try {
                    afd = am.openFd("test.mp4");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mVideoView.setAssetFileDescriptor(afd);
            }
        }

        mVideoView.start();
    }
}
