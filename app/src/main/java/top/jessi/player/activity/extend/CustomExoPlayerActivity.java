package top.jessi.player.activity.extend;

import android.view.View;

import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.widget.videoview.ExoVideoView;
import top.jessi.videocontroller.StandardVideoController;
import top.jessi.videoplayer.exo.ExoMediaPlayer;
import top.jessi.videoplayer.exo.ExoMediaSourceHelper;
import top.jessi.videoplayer.player.AbstractPlayer;

/**
 * 自定义MediaPlayer，有多种情形：
 * 第一：继承某个现成的MediaPlayer，对其功能进行扩展，此demo就演示了通过继承{@link ExoMediaPlayer}
 * 对其功能进行扩展。
 * 第二：通过继承{@link AbstractPlayer}扩展一些其他的播放器。
 */
public class CustomExoPlayerActivity extends BaseActivity<ExoVideoView> {

    private ExoMediaSourceHelper mHelper;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_custom_exo_player;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.vv);
        StandardVideoController controller = new StandardVideoController(this);
        controller.addDefaultControlComponent("custom exo", false);
        mVideoView.setVideoController(controller);
        mHelper = ExoMediaSourceHelper.getInstance(this);
    }

    public void onButtonClick(View view) {
        mVideoView.release();
        mVideoView.setCacheEnabled(false);
        int id = view.getId();
        if (id == R.id.btn_cache) {
            mVideoView.setCacheEnabled(true);
            mVideoView.setUrl("http://playertest.longtailvideo.com/adaptive/bipbop/gear4/prog_index.m3u8");
        } else if (id == R.id.btn_concat) {//将多个视频拼接在一起播放
            ConcatenatingMediaSource concatenatingMediaSource = new ConcatenatingMediaSource();
            MediaSource mediaSource1 = mHelper.getMediaSource("https://www.w3schools.com/html/mov_bbb.mp4");
            MediaSource mediaSource2 = mHelper.getMediaSource("http://vfx.mtime.cn/Video/2019/03/21/mp4/190321153853126488.mp4");
            MediaSource mediaSource3 = mHelper.getMediaSource("http://vfx.mtime.cn/Video/2019/03/19/mp4/190319222227698228.mp4");
            concatenatingMediaSource.addMediaSource(mediaSource1);
            concatenatingMediaSource.addMediaSource(mediaSource2);
            concatenatingMediaSource.addMediaSource(mediaSource3);
            mVideoView.setMediaSource(concatenatingMediaSource);
        } else if (id == R.id.btn_clip) {
            MediaSource mediaSource = mHelper.getMediaSource("https://www.w3schools.com/html/mov_bbb.mp4");
            //裁剪10-15秒的内容进行播放
            ClippingMediaSource clippingMediaSource = new ClippingMediaSource(mediaSource, 10_000_000, 15_000_000);
            mVideoView.setMediaSource(clippingMediaSource);
        } else if (id == R.id.btn_dash) {
            mVideoView.setUrl("http://www.bok.net/dash/tears_of_steel/cleartext/stream.mpd");
        } else if (id == R.id.btn_rtsp) {
            mVideoView.setUrl("rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mov");
        }

        mVideoView.start();
    }
}
