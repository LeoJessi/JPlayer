package top.jessi.player.activity.list.tiktok;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import top.jessi.player.R;
import top.jessi.player.activity.BaseActivity;
import top.jessi.player.adapter.Tiktok2Adapter;
import top.jessi.player.bean.TiktokBean;
import top.jessi.player.util.DataUtil;
import top.jessi.player.util.Utils;
import top.jessi.videoplayer.cache.preload.PreloadManager;
import top.jessi.videoplayer.cache.preload.PreloadSession;
import top.jessi.player.widget.VerticalViewPager;
import top.jessi.player.widget.controller.TikTokController;
import top.jessi.player.widget.render.TikTokRenderViewFactory;
import top.jessi.videoplayer.player.BaseVideoView;
import top.jessi.videoplayer.player.PlayState;
import top.jessi.videoplayer.player.VideoView;
import top.jessi.videoplayer.util.L;


/**
 * 模仿抖音短视频，使用VerticalViewPager实现，实现了预加载功能
 * Created by Doikki on 2019/10/13.
 */

public class TikTok2Activity extends BaseActivity<VideoView> {

    /**
     * 当前播放位置
     */
    private int mCurPos;
    private List<TiktokBean> mVideoList = new ArrayList<>();
    private Tiktok2Adapter mTiktok2Adapter;
    private VerticalViewPager mViewPager;

    private PreloadManager mPreloadManager;

    /**
     * 每页一个的预加载会话，自动跟随页面生命周期暂停/恢复/释放
     */
    private PreloadSession mPreloadSession;

    private TikTokController mController;

    private static final String KEY_INDEX = "index";

    public static void start(Context context, int index) {
        Intent i = new Intent(context, TikTok2Activity.class);
        i.putExtra(KEY_INDEX, index);
        context.startActivity(i);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_tiktok2;
    }

    @Override
    protected int getTitleResId() {
        return R.string.str_tiktok_2;
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
        mViewPager.setCurrentItem(index);

        mViewPager.post(new Runnable() {
            @Override
            public void run() {
                startPlay(index);
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
    }

    private void initViewPager() {
        mViewPager = findViewById(R.id.vvp);
        mViewPager.setOffscreenPageLimit(4);
        mTiktok2Adapter = new Tiktok2Adapter(mVideoList);
        mViewPager.setAdapter(mTiktok2Adapter);
        mViewPager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == mCurPos) return;
                startPlay(position);
            }
        });
    }

    private void startPlay(int position) {
        int count = mViewPager.getChildCount();
        for (int i = 0; i < count; i ++) {
            View itemView = mViewPager.getChildAt(i);
            Tiktok2Adapter.ViewHolder viewHolder = (Tiktok2Adapter.ViewHolder) itemView.getTag();
            if (viewHolder.mPosition == position) {
                mVideoView.release();
                Utils.removeViewFormParent(mVideoView);

                TiktokBean tiktokBean = mVideoList.get(position);
                mCurPos = position;
                // 先更新预加载窗口，避免 URL 交接时调度器仍按上一页窗口处理当前任务。
                mPreloadSession.setCurrentIndex(position);
                String playUrl = mPreloadManager.getPlayUrl(tiktokBean.videoDownloadUrl);
                L.i("startPlay: " + "position: " + position + "  url: " + playUrl);
                mVideoView.setUrl(playUrl);
                //请点进去看isDissociate的解释
                mController.addControlComponent(viewHolder.mTikTokView, true);
                viewHolder.mPlayerContainer.addView(mVideoView, 0);
                mVideoView.start();
                break;
            }
        }
    }

    public void addData(View view) {
        mVideoList.addAll(DataUtil.getTiktokDataFromAssets(this));
        mTiktok2Adapter.notifyDataSetChanged();
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
