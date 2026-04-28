package top.jessi.player.activity.api;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.util.IntentKeys;
import top.jessi.player.util.Utils;
import top.jessi.player.widget.component.DebugInfoView;
import top.jessi.player.widget.component.PlayerMonitor;
import top.jessi.videocontroller.StandardVideoController;
import top.jessi.videocontroller.component.CompleteView;
import top.jessi.videocontroller.component.ErrorView;
import top.jessi.videocontroller.component.GestureView;
import top.jessi.videocontroller.component.LiveControlView;
import top.jessi.videocontroller.component.PrepareView;
import top.jessi.videocontroller.component.TitleView;
import top.jessi.videocontroller.component.VodControlView;
import top.jessi.videoplayer.player.VideoView;
import top.jessi.videoplayer.util.L;

/**
 * 播放器演示
 * Created by Doikki on 2017/4/7.
 */

public class PlayerActivityJava extends BaseActivity<VideoView> {

    private static final String THUMB = "https://cms-bucket.nosdn.127.net/eb411c2810f04ffa8aaafc42052b233820180418095416.jpeg";

    public static void start(Context context, String url, String title, boolean isLive) {
        Intent intent = new Intent(context, PlayerActivityJava.class);
        intent.putExtra(IntentKeys.URL, url);
        intent.putExtra(IntentKeys.IS_LIVE, isLive);
        intent.putExtra(IntentKeys.TITLE, title);
        context.startActivity(intent);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_player;
    }

    @Override
    protected void initView() {
        super.initView();
        mVideoView = findViewById(R.id.player);

        Intent intent = getIntent();
        if (intent != null) {
            StandardVideoController controller = new StandardVideoController(this);
            //根据屏幕方向自动进入/退出全屏
            controller.setEnableOrientation(true);

            PrepareView prepareView = new PrepareView(this);//准备播放界面
            prepareView.setClickStart();
            ImageView thumb = prepareView.findViewById(top.jessi.videocontroller.R.id.thumb);//封面图
            Glide.with(this).load(THUMB).into(thumb);
            controller.addControlComponent(prepareView);

            controller.addControlComponent(new CompleteView(this));//自动完成播放界面

            controller.addControlComponent(new ErrorView(this));//错误界面

            TitleView titleView = new TitleView(this);//标题栏
            controller.addControlComponent(titleView);

            //根据是否为直播设置不同的底部控制条
            boolean isLive = intent.getBooleanExtra(IntentKeys.IS_LIVE, false);
            if (isLive) {
                controller.addControlComponent(new LiveControlView(this));//直播控制条
            } else {
                VodControlView vodControlView = new VodControlView(this);//点播控制条
                //是否显示底部进度条。默认显示
//                vodControlView.showBottomProgress(false);
                controller.addControlComponent(vodControlView);
            }

            GestureView gestureControlView = new GestureView(this);//滑动控制视图
            controller.addControlComponent(gestureControlView);
            //根据是否为直播决定是否需要滑动调节进度
            controller.setCanChangePosition(!isLive);

            //设置标题
            String title = intent.getStringExtra(IntentKeys.TITLE);
            titleView.setTitle(title);

            //注意：以上组件如果你想单独定制，我推荐你把源码复制一份出来，然后改成你想要的样子。
            //改完之后再通过addControlComponent添加上去
            //你也可以通过addControlComponent添加一些你自己的组件，具体实现方式参考现有组件的实现。
            //这个组件不一定是View，请发挥你的想象力😃

            //如果你不需要单独配置各个组件，可以直接调用此方法快速添加以上组件
//            controller.addDefaultControlComponent(title, isLive);

            //竖屏也开启手势操作，默认关闭
//            controller.setEnableInNormal(true);
            //滑动调节亮度，音量，进度，默认开启
//            controller.setGestureEnabled(false);
            //适配刘海屏，默认开启
//            controller.setAdaptCutout(false);
            //双击播放暂停，默认开启
//            controller.setDoubleTapTogglePlayEnabled(false);

            //在控制器上显示调试信息
            controller.addControlComponent(new DebugInfoView(this));
            //在LogCat显示调试信息
            controller.addControlComponent(new PlayerMonitor());

            //如果你不想要UI，不要设置控制器即可
            mVideoView.setVideoController(controller);

            String url = intent.getStringExtra(IntentKeys.URL);

            //点击文件管理器中的视频，选择DKPlayer打开，将会走以下代码
            if (TextUtils.isEmpty(url)
                    && Intent.ACTION_VIEW.equals(intent.getAction())) {
                //获取intent中的视频地址
                url = Utils.getFileFromContentUri(this, intent.getData());
            }
            mVideoView.setUrl(url);

            //保存播放进度
//            mVideoView.setProgressManager(new ProgressManagerImpl());
            //播放状态监听
            mVideoView.addOnStateChangeListener(mOnStateChangeListener);

            //临时切换播放核心，如需全局请通过VideoConfig配置，详见MyApplication
            //使用IjkPlayer解码
//            mVideoView.setPlayerFactory(IjkPlayerFactory.create());
            //使用ExoPlayer解码
//            mVideoView.setPlayerFactory(ExoMediaPlayerFactory.create());
            //使用MediaPlayer解码
//            mVideoView.setPlayerFactory(AndroidMediaPlayerFactory.create());

            //设置静音播放
//            mVideoView.setMute(true);

            //从设置的position开始播放
//            mVideoView.skipPositionWhenPlay(10000);

            mVideoView.start();
        }

        //播放其他视频
        EditText etOtherVideo = findViewById(R.id.et_other_video);
        findViewById(R.id.btn_start_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mVideoView.release();
                mVideoView.setUrl(etOtherVideo.getText().toString());
                mVideoView.start();
            }
        });
    }

    private VideoView.OnStateChangeListener mOnStateChangeListener = new VideoView.SimpleOnStateChangeListener() {
        @Override
        public void onPlayerStateChanged(int playerState) {
            switch (playerState) {
                case VideoView.PLAYER_NORMAL://小屏
                    break;
                case VideoView.PLAYER_FULL_SCREEN://全屏
                    break;
            }
        }

        @Override
        public void onPlayStateChanged(int playState) {
            switch (playState) {
                case VideoView.STATE_IDLE:
                    break;
                case VideoView.STATE_PREPARING:
                    break;
                case VideoView.STATE_PREPARED:
                    break;
                case VideoView.STATE_PLAYING:
                    //需在此时获取视频宽高
                    int[] videoSize = mVideoView.getVideoSize();
                    L.d("视频宽：" + videoSize[0]);
                    L.d("视频高：" + videoSize[1]);
                    break;
                case VideoView.STATE_PAUSED:
                    break;
                case VideoView.STATE_BUFFERING:
                    break;
                case VideoView.STATE_BUFFERED:
                    break;
                case VideoView.STATE_PLAYBACK_COMPLETED:
                    break;
                case VideoView.STATE_ERROR:
                    break;
            }
        }
    };

    private int i = 0;

    public void onButtonClick(View view) {
        int id = view.getId();
        if (id == R.id.scale_default) {
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT);
        } else if (id == R.id.scale_169) {
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_16_9);
        } else if (id == R.id.scale_43) {
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_4_3);
        } else if (id == R.id.scale_original) {
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_ORIGINAL);
        } else if (id == R.id.scale_match_parent) {
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_MATCH_PARENT);
        } else if (id == R.id.scale_center_crop) {
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP);
        } else if (id == R.id.speed_0_5) {
            mVideoView.setSpeed(0.5f);
        } else if (id == R.id.speed_0_75) {
            mVideoView.setSpeed(0.75f);
        } else if (id == R.id.speed_1_0) {
            mVideoView.setSpeed(1.0f);
        } else if (id == R.id.speed_1_5) {
            mVideoView.setSpeed(1.5f);
        } else if (id == R.id.speed_2_0) {
            mVideoView.setSpeed(2.0f);
        } else if (id == R.id.screen_shot) {
            ImageView imageView = findViewById(R.id.iv_screen_shot);
            Bitmap bitmap = mVideoView.doScreenShot();
            imageView.setImageBitmap(bitmap);
        } else if (id == R.id.mirror_rotate) {
            mVideoView.setMirrorRotation(i % 2 == 0);
            i++;
        } else if (id == R.id.btn_mute) {
            mVideoView.setMute(!mVideoView.isMute());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        //如果视频还在准备就 activity 就进入了后台，建议直接将 VideoView release
        //防止进入后台后视频还在播放
        if (mVideoView.getCurrentPlayState() == VideoView.STATE_PREPARING) {
            mVideoView.release();
        }
    }
}
