package top.jessi.videoplayer.cache.preload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

/**
 * 每页一个的预加载会话：持有 url 列表与当前下标，并负责计算预加载窗口。
 * <p>
 * 窗口为 {@code [当前下标 - N, 当前下标 + N]}（N 由
 * {@link PreloadManager#setWindowSize(int)} 配置，默认 {@link PreloadManager#DEFAULT_WINDOW_SIZE}）。
 * 只有落在窗口内的 url 才会被预加载，滑出窗口的任务会被取消。
 * <p>
 * 自动绑定页面生命周期：
 * <ul>
 *   <li>ON_PAUSE：暂停预加载（该页任务被取消）</li>
 *   <li>ON_RESUME：恢复预加载</li>
 *   <li>ON_DESTROY：释放资源并从引擎注销</li>
 * </ul>
 * <p>
 * 注意：窗口基于<b>列表下标</b>而非稳定 id，因此调用 {@link #removeUrl(String)} 之后
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
     */
    public void setCurrentIndex(int index) {
        if (index == mCurIndex) return;
        mCurIndex = index;
        mManager.scheduleCompute();
    }

    /**
     * 当前下标
     */
    public int getCurrentIndex() {
        return mCurIndex;
    }

    /**
     * 收集当前会话的预加载窗口到 {@code out}，在调度线程上调用。
     *
     * @param out url -&gt; 与当前下标的距离。同一 url 被多个会话命中时取最小距离，
     *            调度方据此按"离当前下标越近越优先"的顺序提交任务
     */
    void collectWindow(Map<String, Integer> out) {
        if (!mActive || !mResumed) return;
        List<String> urls = mUrls;
        if (urls.isEmpty()) return;
        int size = mManager.getWindowSize();
        int from = Math.max(0, mCurIndex - size);
        int to = Math.min(urls.size() - 1, mCurIndex + size);
        for (int i = from; i <= to; i++) {
            String url = urls.get(i);
            if (url == null || url.isEmpty()) continue;
            int distance = Math.abs(i - mCurIndex);
            Integer old = out.get(url);
            if (old == null || distance < old) {
                out.put(url, distance);
            }
            mManager.putPosition(url, i);
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
        // 置为后台后，本会话的 url 不再进入窗口，computeWindows 会自动取消其任务
        mManager.scheduleCompute();
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
        mActive = false;
        owner.getLifecycle().removeObserver(this);
        mManager.unregisterSession(this);
    }
}
