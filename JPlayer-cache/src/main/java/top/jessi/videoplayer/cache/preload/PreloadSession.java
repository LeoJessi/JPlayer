package top.jessi.videoplayer.cache.preload;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

/**
 * 每页一个的预加载会话：持有 url 列表与当前下标，并负责计算预加载跨度。
 * <p>
 * 跨度为 {@code [当前下标 - backward, 当前下标 + forward]}（由
 * {@link PreloadManager#setPreloadSpan(int, int)} 配置，默认前 2 / 后 2），
 * <b>不包含当前项</b>——当前项由播放器自己缓存。
 * 只有落在跨度内的 url 才会被预加载，滑出跨度的任务会被取消。
 * <p>
 * 连续快速滑动时跨度会临时收缩到"只预加载紧邻的下一条"：快速滑动中途不会停留，
 * 预加载中间那些集纯属浪费。滑动停止 {@link #RAPID_SCROLL_INTERVAL_MS} 后自动恢复。
 * <p>
 * 自动绑定页面生命周期：
 * <ul>
 *   <li>ON_PAUSE：暂停预加载（该页任务被取消）</li>
 *   <li>ON_RESUME：恢复预加载</li>
 *   <li>ON_DESTROY：释放资源并从引擎注销</li>
 * </ul>
 * <p>
 * 注意：预加载跨度基于<b>列表下标</b>而非稳定 id，因此调用 {@link #removeUrl(String)} 之后
 * 下标会发生偏移，调用方需要在增删数据后重新 {@link #setCurrentIndex(int)}。
 */
public class PreloadSession implements DefaultLifecycleObserver {

    private final PreloadManager mManager;

    /**
     * url 列表。用 volatile 引用 + 不可变快照：主线程写、调度线程读，无需加锁。
     */
    private volatile List<String> mUrls = Collections.emptyList();

    /**
     * 当前播放下标
     */
    private volatile int mCurIndex;

    /**
     * 是否处于前台（由生命周期驱动）
     */
    private volatile boolean mResumed;

    /**
     * 会话是否有效（销毁后置 false）
     */
    private volatile boolean mActive = true;

    /**
     * 小于该间隔的连续切页视为快速滑动；
     * 同时作为"滑动停止"的判定延迟：该间隔内没有新切页就认为滑动结束
     */
    private static final long RAPID_SCROLL_INTERVAL_MS = 300L;

    /**
     * 上一次切页的时间，用于判断是否处于快速滑动。
     * <p>volatile：主线程写、调度线程读。
     */
    private volatile long mLastIndexChangeAt;

    /**
     * 连续快速滑动的次数，{@code 0} 表示当前不是快速滑动。
     * <p>volatile：主线程写、调度线程读。
     */
    private volatile int mRapidCount;

    /**
     * 主线程 Handler，用于实现"滑动停止后恢复跨度"。
     * <p>必须用主线程：只有主线程消息队列能保证"新的切页取消上一次延迟任务"的时序，
     * 用后台线程的定时器会导致旧任务在新切页之后才触发。
     */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 滑动停止回调：连续切页结束 {@link #RAPID_SCROLL_INTERVAL_MS} 后触发一次重算，
     * 把被临时收缩的向后条数恢复。
     * <p>没有这个回调时 {@link #mRapidCount} 会一直停在非 0，用户停下之后跨度仍只覆盖
     * "紧邻的下一条"，直到下一次切页才恢复——而停下恰恰是最该补预加载的时候。
     */
    private final Runnable mIdleRunnable = new Runnable() {
        @Override
        public void run() {
            mRapidCount = 0;
            mManager.scheduleCompute();
        }
    };

    PreloadSession(PreloadManager manager, LifecycleOwner owner) {
        mManager = manager;
        Lifecycle lifecycle = owner.getLifecycle();
        // 创建时同步一次前后台状态（构造可能在 onCreate 中发生，此时还未 RESUMED）
        mResumed = lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        lifecycle.addObserver(this);
    }

    /**
     * 设置 url 列表，数据加载完成后调用一次。
     * <p>会清空上一次列表的预加载状态并重新计算窗口。
     */
    public void setUrls(List<String> urls) {
        mUrls = urls == null || urls.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(urls));
        mManager.scheduleCompute();
    }

    /**
     * 追加一个 url（列表加载更多时调用）
     */
    public void addUrl(String url) {
        if (url == null || url.isEmpty()) return;
        List<String> current = mUrls;
        List<String> next = new ArrayList<>(current.size() + 1);
        next.addAll(current);
        next.add(url);
        mUrls = Collections.unmodifiableList(next);
        mManager.scheduleCompute();
    }

    /**
     * 移除一个 url（注意：移除后其后所有下标会前移）
     */
    public void removeUrl(String url) {
        if (url == null) return;
        List<String> current = mUrls;
        if (!current.contains(url)) return;
        List<String> next = new ArrayList<>(current);
        next.remove(url);
        mUrls = Collections.unmodifiableList(next);
        mManager.scheduleCompute();
    }

    /**
     * 设置当前下标，列表滚动或切页时调用，触发窗口重算。
     * <p><b>必须在主线程调用</b>：滑动停止的检测依赖主线程消息队列
     * "后一个延迟任务取消前一个"的时序。
     */
    public void setCurrentIndex(int index) {
        if (index == mCurIndex) return;
        mCurIndex = index;
        // 统计连续快速滑动：每次切页都重置计时，间隔小于阈值就累加
        long now = SystemClock.elapsedRealtime();
        mRapidCount = (now - mLastIndexChangeAt < RAPID_SCROLL_INTERVAL_MS)
                ? Math.min(mRapidCount + 1, 3) : 0;
        mLastIndexChangeAt = now;
        // 安排一次"滑动停止"检测：若该间隔内没有新的切页，就把收缩的跨度恢复。
        // removeCallbacks 必须在主线程做，否则旧的延迟任务可能在新切页之后才触发，
        // 把刚恢复的跨度又收缩回去。
        mMainHandler.removeCallbacks(mIdleRunnable);
        mMainHandler.postDelayed(mIdleRunnable, RAPID_SCROLL_INTERVAL_MS);
        mManager.scheduleCompute();
    }

    /**
     * 当前下标
     */
    public int getCurrentIndex() {
        return mCurIndex;
    }

    /**
     * 收集当前会话的预加载跨度到 {@code out}，在调度线程上调用。
     *
     * @param out url -&gt; 与当前下标的距离。同一 url 被多个会话命中时取最小距离，
     *            调度方据此按"离当前下标越近越优先"的顺序提交任务
     */
    void collectPreloadSpan(Map<String, Integer> out) {
        if (!mActive || !mResumed) return;
        List<String> urls = mUrls;
        if (urls.isEmpty()) return;
        int last = urls.size() - 1;
        int from = Math.max(0, mCurIndex - mManager.getPreloadBackward());
        // 快速滑动时用户不会在中间停留，收缩到"只预加载紧邻的下一条"
        int forward = mRapidCount > 0
                ? Math.min(mManager.getPreloadForward(), 1)
                : mManager.getPreloadForward();
        int to = Math.min(last, mCurIndex + forward);
        for (int i = from; i <= to; i++) {
            String url = urls.get(i);
            if (url == null || url.isEmpty()) continue;
            // 下标只用于日志与埋点，当前项也要记录
            mManager.putPosition(url, i);
            // 当前项由播放器自己缓存，不重复预加载：
            // 它与播放器共享同一个 ProxyCache，读的是已缓存数据，
            // 但会多占一路 socket / 一个代理线程，并在 FileCache 的锁上与播放器竞争。
            if (i == mCurIndex) continue;
            int distance = Math.abs(i - mCurIndex);
            Integer old = out.get(url);
            if (old == null || distance < old) {
                out.put(url, distance);
            }
        }
    }

    @Override
    public void onResume(LifecycleOwner owner) {
        mResumed = true;
        mManager.scheduleCompute();
    }

    @Override
    public void onPause(LifecycleOwner owner) {
        mResumed = false;
        // 置为后台后，本会话的 url 不再进入预加载跨度，computePreloadSpan 会自动取消其任务
        mManager.scheduleCompute();
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
        mActive = false;
        mMainHandler.removeCallbacks(mIdleRunnable);
        owner.getLifecycle().removeObserver(this);
        mManager.unregisterSession(this);
    }
}
