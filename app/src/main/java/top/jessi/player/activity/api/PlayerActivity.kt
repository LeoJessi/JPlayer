package top.jessi.player.activity.api

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import com.bumptech.glide.Glide
import top.jessi.player.R
import top.jessi.player.activity.BaseActivity
import top.jessi.player.util.IntentKeys
import top.jessi.player.util.Utils
import top.jessi.player.widget.component.DebugInfoView
import top.jessi.player.widget.component.PlayerMonitor
import top.jessi.player.widget.render.gl2.GLSurfaceRenderView2
import top.jessi.player.widget.render.gl2.filter.GlFilterGroup
import top.jessi.player.widget.render.gl2.filter.GlSepiaFilter
import top.jessi.player.widget.render.gl2.filter.GlSharpenFilter
import top.jessi.player.widget.render.gl2.filter.GlWatermarkFilter
import top.jessi.videocontroller.StandardVideoController
import top.jessi.videocontroller.component.*
import top.jessi.videoplayer.player.BaseVideoView
import top.jessi.videoplayer.player.VideoView
import top.jessi.videoplayer.render.IRenderView
import top.jessi.videoplayer.render.RenderViewFactory
import top.jessi.videoplayer.util.L

/**
 * 播放器演示
 * Created by Doikki on 2017/4/7.
 */
class PlayerActivity : BaseActivity<VideoView>() {

    private val renderView by lazy {
        GLSurfaceRenderView2(this)
    }

    override fun getLayoutResId() = R.layout.activity_player

    override fun initView() {
        super.initView()
        mVideoView = findViewById(R.id.player)
        intent?.let {
            val controller = StandardVideoController(this)
            //根据屏幕方向自动进入/退出全屏
            controller.setEnableOrientation(true)
            val prepareView = PrepareView(this) //准备播放界面
            prepareView.setClickStart()
            val thumb = prepareView.findViewById<ImageView>(top.jessi.videocontroller.R.id.thumb) //封面图
            Glide.with(this).load(THUMB).into(thumb)
            controller.addControlComponent(prepareView)
            controller.addControlComponent(CompleteView(this)) //自动完成播放界面
            controller.addControlComponent(ErrorView(this)) //错误界面
            val titleView = TitleView(this) //标题栏
            controller.addControlComponent(titleView)

            //根据是否为直播设置不同的底部控制条
            val isLive = it.getBooleanExtra(IntentKeys.IS_LIVE, false)
            if (isLive) {
                controller.addControlComponent(LiveControlView(this)) //直播控制条
            } else {
                val vodControlView = VodControlView(this) //点播控制条
                //是否显示底部进度条。默认显示
//                vodControlView.showBottomProgress(false);
                controller.addControlComponent(vodControlView)
            }
            val gestureControlView = GestureView(this) //滑动控制视图
            controller.addControlComponent(gestureControlView)
            //根据是否为直播决定是否需要滑动调节进度
            controller.setCanChangePosition(!isLive)

            //设置标题
            val title = it.getStringExtra(IntentKeys.TITLE)
            titleView.setTitle(title)

            //注意：以上组件如果你想单独定制，我推荐你把源码复制一份出来，然后改成你想要的样子。
            //改完之后再通过addControlComponent添加上去
            //你也可以通过addControlComponent添加一些你自己的组件，具体实现方式参考现有组件的实现。
            //这个组件不一定是View，请发挥你的想象力😃

            //如果你不需要单独配置各个组件，可以直接调用此方法快速添加以上组件
//            controller.addDefaultControlComponent(title, isLive)

            //竖屏也开启手势操作，默认关闭
//            controller.setEnableInNormal(true)
            //滑动调节亮度，音量，进度，默认开启
//            controller.setGestureEnabled(false)
            //适配刘海屏，默认开启
//            controller.setAdaptCutout(false)
            //双击播放暂停，默认开启
//            controller.setDoubleTapTogglePlayEnabled(false)

            //在控制器上显示调试信息
            controller.addControlComponent(DebugInfoView(this))
            //在LogCat显示调试信息
            controller.addControlComponent(PlayerMonitor())

            //如果你不想要UI，不要设置控制器即可
            mVideoView.setVideoController(controller)
            var url = it.getStringExtra(IntentKeys.URL)

            //点击文件管理器中的视频，选择DKPlayer打开，将会走以下代码
            if (TextUtils.isEmpty(url)
                && Intent.ACTION_VIEW == it.action
            ) {
                //获取intent中的视频地址
                url = Utils.getFileFromContentUri(this, it.data)
            }
//            val header = hashMapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.131 Safari/537.36")
            mVideoView.setUrl(url)

            //保存播放进度
//            mVideoView.setProgressManager(ProgressManagerImpl())
            //播放状态监听
            mVideoView.addOnStateChangeListener(mOnStateChangeListener)

            // 临时切换RenderView, 如需全局请通过VideoConfig配置，详见MyApplication
            if (intent.getBooleanExtra(IntentKeys.CUSTOM_RENDER, false)) {
//                mVideoView.setRenderViewFactory(GLSurfaceRenderViewFactory.create())
                mVideoView.setRenderViewFactory(object : RenderViewFactory() {
                    override fun createRenderView(context: Context?): IRenderView {
                        return renderView
                    }
                })
                // 设置滤镜
                renderView.setGlFilter(GlFilterGroup(
                    // 水印
                    GlWatermarkFilter(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)),
                    GlSepiaFilter(),
                    GlSharpenFilter()
                ))
            }
            //临时切换播放核心，如需全局请通过VideoConfig配置，详见MyApplication
            //使用IjkPlayer解码
//            mVideoView.setPlayerFactory(IjkPlayerFactory.create())
            //使用ExoPlayer解码
//            mVideoView.setPlayerFactory(ExoMediaPlayerFactory.create())
            //使用MediaPlayer解码
//            mVideoView.setPlayerFactory(AndroidMediaPlayerFactory.create())

            //设置静音播放
//            mVideoView.setMute(true)

            //从设置的position开始播放
//            mVideoView.skipPositionWhenPlay(10000)
            mVideoView.start()
        }

        //播放其他视频
        val etOtherVideo = findViewById<EditText>(R.id.et_other_video)
        findViewById<View>(R.id.btn_start_play).setOnClickListener {
            mVideoView.release()
            mVideoView.setUrl(etOtherVideo.text.toString())
            mVideoView.start()
        }
    }

    private val mOnStateChangeListener: BaseVideoView.OnStateChangeListener =
        object : BaseVideoView.SimpleOnStateChangeListener() {
            override fun onPlayerStateChanged(playerState: Int) {
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                    }
                    VideoView.PLAYER_FULL_SCREEN -> {
                    }
                }
            }

            override fun onPlayStateChanged(playState: Int) {
                when (playState) {
                    VideoView.STATE_IDLE -> {
                    }
                    VideoView.STATE_PREPARING -> {
                    }
                    VideoView.STATE_PREPARED -> {
                    }
                    VideoView.STATE_PLAYING -> {
                        //需在此时获取视频宽高
                        val videoSize = mVideoView!!.videoSize
                        L.d("视频宽：" + videoSize[0])
                        L.d("视频高：" + videoSize[1])
                    }
                    VideoView.STATE_PAUSED -> {
                    }
                    VideoView.STATE_BUFFERING -> {
                    }
                    VideoView.STATE_BUFFERED -> {
                    }
                    VideoView.STATE_PLAYBACK_COMPLETED -> {
                    }
                    VideoView.STATE_ERROR -> {
                    }
                }
            }
        }
    private var i = 0
    fun onButtonClick(view: View) {
        when (view.id) {
            R.id.scale_default -> mVideoView!!.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            R.id.scale_169 -> mVideoView!!.setScreenScaleType(VideoView.SCREEN_SCALE_16_9)
            R.id.scale_43 -> mVideoView!!.setScreenScaleType(VideoView.SCREEN_SCALE_4_3)
            R.id.scale_original -> mVideoView!!.setScreenScaleType(VideoView.SCREEN_SCALE_ORIGINAL)
            R.id.scale_match_parent -> mVideoView!!.setScreenScaleType(VideoView.SCREEN_SCALE_MATCH_PARENT)
            R.id.scale_center_crop -> mVideoView!!.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP)
            R.id.speed_0_5 -> mVideoView!!.speed = 0.5f
            R.id.speed_0_75 -> mVideoView!!.speed = 0.75f
            R.id.speed_1_0 -> mVideoView!!.speed = 1.0f
            R.id.speed_1_5 -> mVideoView!!.speed = 1.5f
            R.id.speed_2_0 -> mVideoView!!.speed = 2.0f
            R.id.screen_shot -> {
                val imageView = findViewById<ImageView>(R.id.iv_screen_shot)
                val bitmap = mVideoView!!.doScreenShot()
                imageView.setImageBitmap(bitmap)
            }
            R.id.mirror_rotate -> {
                mVideoView!!.setMirrorRotation(i % 2 == 0)
                i++
            }
            R.id.btn_mute -> mVideoView!!.isMute = !mVideoView!!.isMute
        }
    }

    override fun onPause() {
        super.onPause()
        //如果视频还在准备就 activity 就进入了后台，建议直接将 VideoView release
        //防止进入后台后视频还在播放
        if (mVideoView!!.currentPlayState == VideoView.STATE_PREPARING) {
            mVideoView!!.release()
        }
    }

    companion object {
        private const val THUMB =
            "https://cms-bucket.nosdn.127.net/eb411c2810f04ffa8aaafc42052b233820180418095416.jpeg"

        @JvmStatic
        fun start(context: Context, url: String, title: String, isLive: Boolean, customRender: Boolean = false) {
            val intent = Intent(context, PlayerActivity::class.java)
            intent.putExtra(IntentKeys.URL, url)
            intent.putExtra(IntentKeys.IS_LIVE, isLive)
            intent.putExtra(IntentKeys.TITLE, title)
            intent.putExtra(IntentKeys.CUSTOM_RENDER, customRender)
            context.startActivity(intent)
        }
    }
}