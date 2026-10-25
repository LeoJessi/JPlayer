package top.jessi.player.activity.list.tiktok;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.adapter.Tiktok3Adapter;
import top.jessi.player.bean.TiktokBean;
import top.jessi.player.util.DataUtil;
import top.jessi.player.util.Utils;
import top.jessi.videoplayer.cache.preload.PreloadManager;
import top.jessi.videoplayer.cache.preload.PreloadSession;
import top.jessi.player.widget.controller.TikTokController;
import top.jessi.player.widget.render.TikTokRenderViewFactory;
import top.jessi.videoplayer.player.BaseVideoView;
import top.jessi.videoplayer.player.PlayState;
import top.jessi.videoplayer.player.VideoView;
import top.jessi.videoplayer.util.L;


/**
 * 模仿抖音短视频，使用ViewPager2实现，(实验性)
 * Created by Doikki on 2019/12/04.
 */

public class TikTok3Activity extends BaseActivity<VideoView> {

    /**
     * 当前播放位置
     */
    private int mCurPos;
    private List<TiktokBean> mVideoList = new ArrayList<>();
    private Tiktok3Adapter mTiktok3Adapter;
    private ViewPager2 mViewPager;

    private PreloadManager mPreloadManager;

    /**
     * 每页一个的预加载会话，自动跟随页面生命周期暂停/恢复/释放
     */
    private PreloadSession mPreloadSession;

    private TikTokController mController;

    private static final String KEY_INDEX = "index";
    private RecyclerView mViewPagerImpl;

    public static void start(Context context, int index) {
        Intent i = new Intent(context, TikTok3Activity.class);
        i.putExtra(KEY_INDEX, index);
        context.startActivity(i);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_tiktok3;
    }

    @Override
    protected int getTitleResId() {
        return R.string.str_tiktok_3;
    }

    @Override
    protected void initView() {
        super.initView();
        setStatusBarTransparent();
        initViewPager();
        initVideoView();
        mPreloadManager = PreloadManager.getInstance(this);
        mPreloadSession = mPreloadManager.createSession(this);
        // 跨度用引擎默认值（向前 2、向后 5），短视频场景不需要改；
        // 需要调整时调用 mPreloadManager.setPreloadSpan(backward, forward)

        addData(null);
        Intent extras = getIntent();
        int index = extras.getIntExtra(KEY_INDEX, 0);

        mViewPager.post(new Runnable() {
            @Override
            public void run() {
                if (index == 0) {
                    startPlay(0);
                } else {
                    mViewPager.setCurrentItem(index, false);
                }
            }
        });
    }

    private void initVideoView() {
        mVideoView = new VideoView(this);
        mVideoView.setLooping(true);
        //以下只能二选一，看你的需求
        mVideoView.setRenderViewFactory(TikTokRenderViewFactory.create());
//        mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP);

        mController = new TikTokController(this);
        mVideoView.setVideoController(mController);

        // 当前视频开始缓冲时暂停预加载，把带宽让给播放器；缓冲结束自动恢复
        mVideoView.addOnStateChangeListener(new BaseVideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(PlayState playState) {
                mPreloadManager.setPlaybackBuffering(
                        playState.getState() == BaseVideoView.STATE_BUFFERING);
            }
        });
    }

    private void initViewPager() {
        mViewPager = findViewById(R.id.vp2);
        mViewPager.setOffscreenPageLimit(4);
        mTiktok3Adapter = new Tiktok3Adapter(mVideoList);
        mViewPager.setAdapter(mTiktok3Adapter);
        mViewPager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {

            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == mCurPos) return;
                mViewPager.post(new Runnable() {
                    @Override
                    public void run() {
                        startPlay(position);
                    }
                });
            }
        });

        //ViewPage2内部是通过RecyclerView去实现的，它位于ViewPager2的第0个位置
        mViewPagerImpl = (RecyclerView) mViewPager.getChildAt(0);
    }

    private void startPlay(int position) {
        int count = mViewPagerImpl.getChildCount();
        for (int i = 0; i < count; i++) {
            View itemView = mViewPagerImpl.getChildAt(i);
            Tiktok3Adapter.ViewHolder viewHolder = (Tiktok3Adapter.ViewHolder) itemView.getTag();
            if (viewHolder.mPosition == position) {
                mVideoView.release();
                Utils.removeViewFormParent(mVideoView);
                TiktokBean tiktokBean = mVideoList.get(position);
                String playUrl = mPreloadManager.getPlayUrl(tiktokBean.videoDownloadUrl);
                L.i("startPlay: " + "position: " + position + "  url: " + playUrl);
                mVideoView.setUrl(playUrl);
                //请点进去看isDissociate的解释
                mController.addControlComponent(viewHolder.mTikTokView, true);
                viewHolder.mPlayerContainer.addView(mVideoView, 0);
                mVideoView.start();
                mCurPos = position;
                // 预加载窗口跟随当前下标
                mPreloadSession.setCurrentIndex(position);
                break;
            }
        }
    }

    public void addData(View view) {
        int size = mVideoList.size();
        mVideoList.addAll(DataUtil.getTiktokDataFromAssets(this));
        //使用此方法添加数据，使用notifyDataSetChanged会导致正在播放的视频中断
        mTiktok3Adapter.notifyItemRangeChanged(size, mVideoList.size());
        // 数据加载完成后，把 url 列表交给预加载会话（重复调用是幂等的）
        mPreloadSession.setUrls(toUrls(mVideoList));
    }

    private List<String> toUrls(List<TiktokBean> beans) {
        List<String> urls = new ArrayList<>(beans.size());
        for (TiktokBean bean : beans) {
            urls.add(bean.videoDownloadUrl);
        }
        return urls;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 预加载会话已随页面生命周期自动释放，无需手动清理
        // 缓存文件保留，这样再次进入本页时可直接命中，才能验证预加载的实际效果
    }
}
