package top.jessi.videoplayer.cache.preload;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;

/**
 * 预加载引擎：持有线程池、任务表与缓存状态，<b>不持有任何页面状态</b>。
 * <p>
 * 页面相关的状态（url 列表、当前下标、预加载跨度）由每页一个的 {@link PreloadSession} 持有，
 * 通过 {@link #createSession(LifecycleOwner)} 创建。引擎只负责调度与执行。
 * <p>
 * 原理：主动请求 {@link HttpProxyCacheServer} 生成的代理地址，触发其缓存机制，缓存到
 * {@link #mPreloadLength} 的数据后停止请求。播放器随后请求同一代理地址时会直接命中缓存，
 * 从而提升起播速度。
 * <p>
 * 典型用法（列表场景）：
 * <pre><code>
 * PreloadManager engine = PreloadManager.getInstance(context);
 * PreloadSession session = engine.createSession(this);   // 绑定页面生命周期
 * session.setUrls(urls);                                 // 数据加载后调用一次
 * session.setCurrentIndex(position);                     // 列表滚动时调用
 * String playUrl = engine.getPlayUrl(url);                // 点击播放时调用
 * </code></pre>
 */
public class PreloadManager {

    private static volatile PreloadManager sInstance;

    /*
     * 默认配置一律包级可见，不作为公开 API：外部引用它们只会写出
     * "把默认值设回默认值"的空操作。DEFAULT_PRELOAD_LENGTH 需要被同包的
     * PreloadTask 读取，因此无法再收窄为 private。
     */

    /**
     * 默认预加载大小：2MB
     * <p>2MB 足够播放器获取元数据和首帧，配合元数据缓存优化可实现秒播。
     */
    static final int DEFAULT_PRELOAD_LENGTH = 1024 * 1024 * 2;

    /**
     * 默认向后（未播方向）预加载条数：5。
     * <p>短视频/短剧流里"下一个"几乎必然会被播放，向后的收益远高于向前。
     * <p>跨度越小，单次滑动消耗的带宽越少。
     */
    static final int DEFAULT_PRELOAD_FORWARD = 6;

    /**
     * 默认向前（已播方向）预加载条数：2。
     * <p>回滑后往往能命中播放器写下的缓存，但留少量余量可以让回滑起播更快。
     */
    static final int DEFAULT_PRELOAD_BACKWARD = 1;

    /**
     * 默认执行线程数：3。
     * <p>多一个线程就多一份与播放器抢带宽的力气，按网络条件调整。
     * <p><b>上界受代理侧限制</b>：{@code HttpProxyCacheServer} 的 socket 线程池固定 8 个线程，
     * 每路预加载连接会独占一个线程直到读完 {@code mPreloadLength}。因此线程数 + 1（播放器）
     * 必须小于 8，否则播放器的 socket 会排到预加载队列后面，反而把起播拖卡。
     * <p>实测 3 个线程可以让预加载窗口覆盖更快，且不会明显拖慢单任务速度。
     */
    static final int DEFAULT_WORKER_COUNT = 3;

    /**
     * 执行线程数的硬上界：代理侧 socket 线程池固定 8 个，为播放器留一个。
     */
    static final int MAX_WORKER_COUNT = 7;

    /**
     * 执行队列容量。超出后丢弃新任务（窗口会重新计算，丢弃是安全的），
     * 避免滚动时任务无限堆积。
     * <p>预加载跨度最多 {@code DEFAULT_PRELOAD_BACKWARD + DEFAULT_PRELOAD_FORWARD} 条（当前 7 条），
     * 执行池为 {@code DEFAULT_WORKER_COUNT}（2）个线程，队列 4 刚好装下跨度内剩余的任务。
     * <p><b>改大跨度或 worker 数时必须同步调整本值</b>，否则超出的任务会被静默丢弃
     * （表现为"某些集一直预加载不上"，日志里能看到"预加载任务被丢弃"）。
     */
    private static final int WORK_QUEUE_CAPACITY = 4;

    /**
     * 已缓存文件被认为"已就绪"的最小字节数
     */
    private static final long MIN_CACHED_BYTES = 1024L;

    /**
     * 内存中缓存状态记录的条目上限。
     * <p>预加载状态记录（已预加载 / 已查盘 / 下标）必须是有界的：短视频流是无限列表，
     * 若只增不减，长时间滑动后这三个集合会一直增长，造成内存泄漏。
     * <p>超出上限按 LRU 淘汰，被淘汰的 url 最多重做一次磁盘检查，不影响正确性。
     */
    private static final int STATE_CACHE_SIZE = 500;

    /**
     * 没有任何缓存
     */
    private static final int CACHE_STATE_NONE = 0;

    /**
     * 有未预加载完的部分缓存（临时文件）
     */
    private static final int CACHE_STATE_PARTIAL = 1;

    /**
     * 已预加载完成（达到 {@link #mPreloadLength} 或已存在完整缓存文件）
     */
    private static final int CACHE_STATE_COMPLETE = 2;

    private final HttpProxyCacheServer mHttpProxyCacheServer;

    /**
     * 调度线程：单线程串行，处理预加载跨度重算与任务提交，避免并发竞态
     */
    private final ExecutorService mScheduler;

    /**
     * 执行池：固定线程数 + 有界队列 + 丢弃策略
     */
    private final ThreadPoolExecutor mWorkerPool;

    /**
     * 尾部（moov）预加载执行器：单线程串行，优先级最低。
     * <p>尾部请求只在头部就绪后发一次。串行执行可以避免多个尾部请求并发时互相竞争，
     * 确保每个尾部请求都能顺利完成。
     */
    private final ExecutorService mTailExecutor;

    /**
     * 所有页面会话
     */
    private final Set<PreloadSession> mSessions = newConcurrentSet();

    /**
     * 全局任务表：url -&gt; task
     */
    private final Map<String, PreloadTask> mTasks = new ConcurrentHashMap<>();

    /**
     * 已提交/执行中的 url，避免重复提交
     */
    private final Set<String> mInFlight = newConcurrentSet();

    /**
     * url -&gt; 列表下标，仅用于日志
     */
    private final Map<String, Integer> mPositions = newBoundedMap(STATE_CACHE_SIZE);

    /**
     * 已预加载完成的 url（内存记录，避免重复磁盘 IO）
     */
    private final Set<String> mPreloadedUrls = newBoundedSet(STATE_CACHE_SIZE);

    /**
     * 已做过磁盘检查的 url（避免重复磁盘 IO）
     */
    private final Set<String> mCheckedUrls = newBoundedSet(STATE_CACHE_SIZE);

    private volatile int mPreloadLength = DEFAULT_PRELOAD_LENGTH;
    private volatile int mPreloadBackward = DEFAULT_PRELOAD_BACKWARD;
    private volatile int mPreloadForward = DEFAULT_PRELOAD_FORWARD;

    /**
     * 执行线程数。volatile：{@link #setWorkerCount(int)} 可能在任意线程调用，
     * 而构造线程池的线程与读取它的线程不同。
     */
    private volatile int mWorkerCount = DEFAULT_WORKER_COUNT;

    /**
     * 预加载事件回调，可为 {@code null}
     */
    private volatile PreloadListener mPreloadListener;

    /** 默认埋点每隔多少次提交打印一行汇总 */
    private static final int REPORT_INTERVAL = 10;

    /**
     * 引擎是否已关闭
     */
    private volatile boolean mShutdown;

    /**
     * 卡顿暂停的防抖间隔：弱网下缓冲/恢复可能反复交替，只取消一次，
     * 避免"取消 → 重提交 → 再取消"的抖动把带宽白白耗在重建连接上。
     */
    private static final long BUFFERING_DEBOUNCE_MS = 1000L;

    /**
     * 播放器是否正在缓冲。为 {@code true} 时暂停一切预加载，把带宽让给当前视频。
     */
    private volatile boolean mPlaybackBuffering;

    /**
     * 上一次因卡顿取消任务的时间，用于防抖
     */
    private long mLastPauseAt;

    private PreloadManager(Context context) {
        mHttpProxyCacheServer = HttpProxyCacheServer.getInstance(context);
        mScheduler = Executors.newSingleThreadExecutor(r -> new Thread(r, "preload-scheduler"));
        mTailExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "preload-tail");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        mWorkerPool = new ThreadPoolExecutor(
                mWorkerCount,
                mWorkerCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(WORK_QUEUE_CAPACITY),
                new ThreadFactory() {
                    private final AtomicInteger mCount = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "preload-worker-" + mCount.getAndIncrement());
                    }
                },
                // 队列已满时丢弃新任务：窗口会重新计算，稍后重试
                (r, executor) -> {
                    if (r instanceof PreloadTask) {
                        mInFlight.remove(((PreloadTask) r).mRawUrl);
                    }
                    Logger.info("预加载任务被丢弃（执行队列已满）");
                });
        // 默认埋点：只打日志，业务方调用 setPreloadListener 后可接管
        mPreloadListener = new LogPreloadListener();
    }

    public static PreloadManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PreloadManager.class) {
                if (sInstance == null) {
                    sInstance = new PreloadManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /**
     * 创建一个线程安全的 Set。
     * <p>不使用 {@link ConcurrentHashMap#newKeySet()}，因为它是 API 24+ 才有的，
     * 而本模块 minSdk 为 21。
     */
    private static <T> Set<T> newConcurrentSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
    }

    /**
     * 创建一个线程安全、且有 LRU 上限的 Set。
     * <p>用于缓存"已预加载""已查盘"这类只增不减的状态，避免长列表下内存无限增长。
     * <p>{@code accessOrder} 为 {@code true}：读操作（{@code contains}）也算访问，
     * 窗口内正在使用的 url 不会被淘汰。
     */
    static <T> Set<T> newBoundedSet(final int maxSize) {
        return Collections.synchronizedSet(Collections.newSetFromMap(newBoundedMap(maxSize)));
    }

    /**
     * 创建一个线程安全、且有 LRU 上限的 Map。
     */
    static <K, V> Map<K, V> newBoundedMap(final int maxSize) {
        return Collections.synchronizedMap(
                new LinkedHashMap<K, V>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                        return size() > maxSize;
                    }
                });
    }

    // ---------- 配置 ----------

    /**
     * 设置预加载大小（字节），默认 {@link #DEFAULT_PRELOAD_LENGTH}。
     * <p>调用后会触发一次跨度重算。注意：<b>只影响之后创建的任务</b>——
     * 已提交的任务持有创建时的快照，要等它们出窗后重建才会用上新值。
     * <p>调大时，内存中已有"已预加载"记录的 url 不会重新检查；如需重新对齐，
     * 调用 {@link #clearPreloadState()}。
     */
    public PreloadManager setPreloadLength(int preloadLength) {
        if (preloadLength <= 0) return this;
        if (preloadLength == mPreloadLength) return this;
        mPreloadLength = preloadLength;
        scheduleCompute();
        return this;
    }

    /**
     * 设置执行线程数，默认 {@link #DEFAULT_WORKER_COUNT}。
     * <p>调大只能让<b>多个视频同时下载</b>，对单个视频的下载速度没有帮助
     * ——缓存是连续前缀模型，单视频无法并行下载（见 {@link #MAX_WORKER_COUNT}）。
     * <p>线程数会被限制在 {@link #MAX_WORKER_COUNT} 以内。超出代理侧 socket 线程池
     * （固定 8 个）时，播放器的 socket 会排到预加载队列后面，反而把起播拖卡。
     *
     * @param workerCount 线程数，{@code <= 0} 时忽略
     */
    public PreloadManager setWorkerCount(int workerCount) {
        if (workerCount <= 0) return this;
        workerCount = Math.min(workerCount, MAX_WORKER_COUNT);
        if (workerCount == mWorkerCount) return this;
        mWorkerCount = workerCount;
        // corePoolSize == maximumPoolSize，且 setCorePoolSize 要求 max >= core，
        // 因此调大必须先改 max，调小必须先改 core
        if (workerCount > mWorkerPool.getCorePoolSize()) {
            mWorkerPool.setMaximumPoolSize(workerCount);
            mWorkerPool.setCorePoolSize(workerCount);
        } else {
            mWorkerPool.setCorePoolSize(workerCount);
            mWorkerPool.setMaximumPoolSize(workerCount);
        }
        scheduleCompute();
        return this;
    }

    /**
     * 执行线程数，默认 {@link #DEFAULT_WORKER_COUNT}，上界 {@link #MAX_WORKER_COUNT}
     */
    public int getWorkerCount() {
        return mWorkerCount;
    }

    /**
     * 设置预加载跨度：以当前下标为中心，向前、向后各预加载几条。
     * <p>跨度为 {@code [当前下标 - backward, 当前下标 + forward]}，<b>不包含当前项</b>
     * （当前项由播放器自己缓存）。默认前 2 / 后 5。
     * <p>改小跨度是省流量最直接的手段：跨度从 前 2 后 5（7 条）收到 前 2 后 2（4 条），
     * 单次滑动消耗从 35MB 降到 20MB 左右（按默认 5MB/条估算）。
     * <p>某一边填 {@code 0} 表示该方向不预加载：
     * <pre><code>
     * // 只向后预加载 5 条，向前不预加载
     * manager.setPreloadSpan(0, 5);
     * </code></pre>
     *
     * @param backward 向前（已播方向）预加载条数，{@code 0} 表示不预加载
     * @param forward  向后（未播方向）预加载条数，{@code 0} 表示不预加载
     */
    public PreloadManager setPreloadSpan(int backward, int forward) {
        if (backward < 0 || forward < 0) return this;
        if (backward == mPreloadBackward && forward == mPreloadForward) return this;
        mPreloadBackward = backward;
        mPreloadForward = forward;
        scheduleCompute();
        return this;
    }

    /**
     * 预加载大小（字节），默认 {@link #DEFAULT_PRELOAD_LENGTH}
     */
    public int getPreloadLength() {
        return mPreloadLength;
    }

    /**
     * 向前（已播方向）预加载条数，默认 {@link #DEFAULT_PRELOAD_BACKWARD}
     */
    public int getPreloadBackward() {
        return mPreloadBackward;
    }

    /**
     * 向后（未播方向）预加载条数，默认 {@link #DEFAULT_PRELOAD_FORWARD}
     */
    public int getPreloadForward() {
        return mPreloadForward;
    }

    /**
     * 设置预加载事件回调，用于统计命中率。
     * <p>不设置时引擎自带一份日志实现，可在 logcat 里直接看到命中率。
     */
    public PreloadManager setPreloadListener(PreloadListener listener) {
        mPreloadListener = listener;
        return this;
    }

    /**
     * 记录 url 的列表下标（仅用于日志）。
     * <p>只在调度线程调用，直接 {@code put} 即可；不使用 {@code putIfAbsent}，
     * 因为它在低版本 Android 上存在 API 可用性问题。
     */
    void putPosition(String url, int position) {
        mPositions.put(url, position);
    }

    // ---------- 会话 ----------

    /**
     * 为指定页面创建预加载会话，自动绑定其生命周期。
     * <p>页面销毁时会自动释放，无需手动调用。
     */
    public PreloadSession createSession(LifecycleOwner owner) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        // 页面已销毁时创建的会话永远不会收到回调，直接不注册，避免残留在会话集合里
        if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            throw new IllegalStateException("Cannot create preload session for a destroyed owner");
        }
        PreloadSession session = new PreloadSession(this, owner);
        mSessions.add(session);
        return session;
    }

    void unregisterSession(PreloadSession session) {
        mSessions.remove(session);
        scheduleCompute();
    }

    /**
     * 在调度线程上重算预加载跨度。
     * <p>调度线程已关闭时静默忽略：页面销毁等回调可能晚于 {@link #shutdown()} 到达，
     * 直接 {@code execute} 会抛 {@link RejectedExecutionException}。
     */
    void scheduleCompute() {
        if (mShutdown) return;
        try {
            mScheduler.execute(this::computePreloadSpan);
        } catch (RejectedExecutionException e) {
            // 调度线程已关闭，忽略
        }
    }

    private void computePreloadSpan() {
        // url -&gt; 与当前下标的距离。同一 url 被多个会话命中时取最小距离。
        Map<String, Integer> desired = new HashMap<>();
        for (PreloadSession session : mSessions) {
            session.collectPreloadSpan(desired);
        }

        // 取消并移除已出窗的任务
        Iterator<Map.Entry<String, PreloadTask>> iterator = mTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PreloadTask> entry = iterator.next();
            if (!desired.containsKey(entry.getKey())) {
                entry.getValue().cancel();
                iterator.remove();
                mInFlight.remove(entry.getKey());
                mPositions.remove(entry.getKey());
            }
        }

        // 当前视频正在缓冲时不提交新任务：带宽要让给播放器
        if (mPlaybackBuffering || desired.isEmpty()) return;

        // 按"离当前下标的距离"从近到远提交。
        // 执行池只有 DEFAULT_WORKER_COUNT 个线程，队列满时会丢弃队尾任务，
        // 因此必须保证最该预加载的（紧邻当前下标的）先拿到资源，
        // 而不是听任 HashMap 的随机顺序。
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(desired.entrySet());
        Collections.sort(sorted, (a, b) -> {
            int cmp = a.getValue() - b.getValue();
            // 距离相同时按 url 固定顺序，保证多次重算结果稳定，便于对比日志
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });

        // 提交入窗且未预加载的任务
        for (Map.Entry<String, Integer> entry : sorted) {
            String url = entry.getKey();
            if (isPreloaded(url) || mInFlight.contains(url) || PreloadTask.isBlackListed(url)) continue;
            PreloadTask task = mTasks.get(url);
            if (task == null) {
                task = newTask(url);
                mTasks.put(url, task);
            }
            Integer position = mPositions.get(url);
            task.mPosition = position != null ? position : -1;
            mInFlight.add(url);
            PreloadListener listener = mPreloadListener;
            if (listener != null) {
                listener.onPreloadSubmit(url, task.mPosition);
            }
            task.executeOn(mWorkerPool);
        }
    }

    private PreloadTask newTask(String url) {
        PreloadTask task = new PreloadTask();
        task.mRawUrl = url;
        task.mCacheServer = mHttpProxyCacheServer;
        task.mPreloadLength = mPreloadLength;
        task.mTailExecutor = mTailExecutor;
        task.mListener = (u, preloaded, bytes, costMs) -> {
            PreloadListener listener = mPreloadListener;
            if (preloaded) {
                mPreloadedUrls.add(u);
                if (listener != null) {
                    listener.onPreloadDone(u, task.mPosition, bytes, costMs);
                }
            } else {
                // 未读满，允许下次重新磁盘检查（缓存可能已被播放器写满）
                mCheckedUrls.remove(u);
                // 被取消（滑出窗口 / 卡顿暂停）不算失败，但值得统计浪费了多少字节
                if (listener != null && task.isCanceled()) {
                    listener.onPreloadCancel(u, task.mPosition, bytes);
                }
            }
            // 仅当该任务仍是当前任务时才清理在飞标记。
            // 否则旧任务（已出窗并被替换）的回调会误清新任务的标记，
            // 导致同一 url 被重复提交、并发下载。
            if (mTasks.get(u) == task) {
                mInFlight.remove(u);
            }
        };
        return task;
    }

    /**
     * 通知引擎当前视频是否正在缓冲。
     * <p>缓冲时暂停一切预加载，把带宽让给当前视频——这是"预加载把当前视频搞卡"
     * 最直接的解法。可在任意线程调用（内部只做一次 volatile 写 + 投递到调度线程）。
     * <p>页面侧在播放器的状态回调里调用即可，无需改动播放器：
     * <pre><code>
     * videoView.addOnStateChangeListener(new BaseVideoView.SimpleOnStateChangeListener() {
     *     &#64;Override public void onPlayStateChanged(PlayState playState) {
     *         preloadManager.setPlaybackBuffering(
     *                 playState.getState() == BaseVideoView.STATE_BUFFERING);
     *     }
     * });
     * </code></pre>
     *
     * @param buffering {@code true} 表示正在缓冲，应暂停预加载
     */
    public void setPlaybackBuffering(boolean buffering) {
        if (mPlaybackBuffering == buffering) return;
        mPlaybackBuffering = buffering;
        if (buffering) {
            long now = SystemClock.elapsedRealtime();
            if (now - mLastPauseAt >= BUFFERING_DEBOUNCE_MS) {
                mLastPauseAt = now;
                // 投递到调度线程执行：cancel() 会调 HttpURLConnection.disconnect()，
                // 而本方法通常在主线程被调用，不能在那里做可能阻塞的 IO。
                scheduleCancelAll();
            }
        }
        scheduleCompute();
    }

    /**
     * 在调度线程上取消所有任务。
     * <p>不主动触发窗口重算，因此不会立刻重新开始下载；等下次窗口变化时才会按需重试。
     * <p>取消后 {@code mPreloadedUrls} 保留，恢复时只会重试未读满的那部分；
     * 代理的 {@code .download} 临时文件仍在，重试会从断点继续，不会重下。
     */
    private void scheduleCancelAll() {
        if (mShutdown) return;
        try {
            mScheduler.execute(this::cancelAllTasks);
        } catch (RejectedExecutionException e) {
            // 调度线程已关闭，忽略
        }
    }

    // ---------- 缓存状态 ----------

    /**
     * 判断该播放地址是否已经预加载。
     * <p>优先查内存记录；未命中且未做过磁盘检查时才访问磁盘。
     */
    public boolean isPreloaded(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return false;
        if (mPreloadedUrls.contains(rawUrl)) return true;
        if (mCheckedUrls.contains(rawUrl)) return false;
        if (cacheState(rawUrl) == CACHE_STATE_COMPLETE) {
            mPreloadedUrls.add(rawUrl);
            return true;
        }
        // 只有"查过盘但没缓存"才值得记住，避免每次窗口重算都重复查盘
        mCheckedUrls.add(rawUrl);
        return false;
    }

    /**
     * 查询磁盘上的缓存状态。
     */
    private int cacheState(String rawUrl) {
        try {
            File cacheFile = mHttpProxyCacheServer.getCacheFile(rawUrl);
            if (cacheFile.exists() && cacheFile.length() >= MIN_CACHED_BYTES) {
                return CACHE_STATE_COMPLETE;
            }
            File tempCacheFile = mHttpProxyCacheServer.getTempCacheFile(rawUrl);
            if (tempCacheFile.exists()) {
                long length = tempCacheFile.length();
                if (length >= mPreloadLength) return CACHE_STATE_COMPLETE;
                if (length > 0) return CACHE_STATE_PARTIAL;
            }
        } catch (Exception e) {
            // 忽略：视为无缓存
        }
        return CACHE_STATE_NONE;
    }

    // ---------- 业务接口 ----------

    /**
     * 删除指定 url 的缓存文件
     *
     * @param rawUrl 原始视频地址
     * @return 是否删除成功
     */
    public boolean deleteCacheFile(String rawUrl) {
        mPreloadedUrls.remove(rawUrl);
        mCheckedUrls.remove(rawUrl);
        PreloadTask task = mTasks.remove(rawUrl);
        if (task != null) {
            task.cancel();
            mInFlight.remove(rawUrl);
            mPositions.remove(rawUrl);
        }
        return mHttpProxyCacheServer.clearCacheFile(rawUrl);
    }

    /**
     * 清空所有缓存文件，并取消所有在飞的预加载任务。
     * <p>文件删除由 {@link HttpProxyCacheServer#clearAllCache()} 负责；这里额外调用
     * {@link #cancelAllTasks()} 与 {@link #clearPreloadState()}，否则仍在执行的任务会继续
     * 把数据写进刚被删掉的 {@code .download} 文件，缓存清完立刻又被填回来，"清除缓存"等于没做。
     * <p>不主动触发窗口重算，等下次窗口变化（滑动、前后台切换）时才会按需重新预加载。
     *
     * @return 是否全部删除成功
     */
    public boolean clearAllCache() {
        boolean deleted = mHttpProxyCacheServer.clearAllCache();
        cancelAllTasks();
        clearPreloadState();
        return deleted;
    }

    /**
     * 清空预加载状态记录。
     * <p>在删除缓存文件后必须调用，否则内存中"已预加载"的记录会与磁盘不一致，
     * 导致后续不再对该 url 发起预加载。
     */
    public void clearPreloadState() {
        mPreloadedUrls.clear();
        mCheckedUrls.clear();
    }

    /**
     * 取消指定 url 的预加载任务
     */
    public void cancelTask(String rawUrl) {
        PreloadTask task = mTasks.get(rawUrl);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 取消所有预加载任务，但<b>不</b>关闭引擎，会话与 url 列表保持不变。
     * <p>在删除缓存文件后必须调用：否则仍在执行的任务会继续把数据写进刚被删掉的
     * {@code .download} 文件，缓存清完立刻又被填回来，"清除缓存"等于没做。
     * <p>不主动触发窗口重算，因此不会立刻重新开始下载；等下次窗口变化（滑动、
     * 前后台切换）时才会按需重新预加载。
     */
    public void cancelAllTasks() {
        for (PreloadTask task : mTasks.values()) {
            task.cancel();
        }
        mTasks.clear();
        mInFlight.clear();
        mPositions.clear();
    }

    /**
     * 获取播放地址，统一走 {@link HttpProxyCacheServer#getProxyUrl(String)}：
     * <ul>
     *   <li>缓存已完整 → 返回 {@code file://} 指向缓存文件，直接播放；</li>
     *   <li>缓存不完整（预加载进行中/边播边存）→ 返回代理地址，由代理从断点续传；</li>
     *   <li>完全没有缓存 → 返回代理地址，由代理<b>边播边缓存</b>。</li>
     * </ul>
     * 前两种是预加载成果的复用；第三种让<b>用户真正看过的视频</b>也进入缓存，
     * 二次进入/重播可秒开——否则会出现"看过的视频永远不缓存，没看过的预加载却缓存了"的倒挂。
     * <p>代理未存活时 {@code getProxyUrl} 内部会退回原始地址直连。
     * <p>同时取消对应的预加载任务，避免与播放器抢带宽。
     */
    public String getPlayUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return rawUrl;
        cancelTask(rawUrl);
        reportHitOrMiss(rawUrl);
        return mHttpProxyCacheServer.getProxyUrl(rawUrl);
    }

    /**
     * 统计预加载命中情况。<b>只读内存记录，不查盘</b>：本方法在主线程被调用，
     * 不能在这里做磁盘 IO。
     * <p>只统计"尝试过预加载"的 url：既没命中也没未命中的（例如被网络策略拦掉、
     * 在黑名单里）说明压根没预加载，不该计入分母。
     */
    private void reportHitOrMiss(String rawUrl) {
        PreloadListener listener = mPreloadListener;
        if (listener == null) return;
        Integer position = mPositions.get(rawUrl);
        int pos = position != null ? position : -1;
        if (mPreloadedUrls.contains(rawUrl)) {
            listener.onPreloadHit(rawUrl, pos);
        } else if (mCheckedUrls.contains(rawUrl)) {
            listener.onPreloadMiss(rawUrl, pos);
        }
    }

    /**
     * 默认埋点实现：累计计数并定期打印一行汇总，供调参时对照。
     * <p>业务方调用 {@link #setPreloadListener(PreloadListener)} 后本实现即被替换。
     */
    private static class LogPreloadListener implements PreloadListener {

        private final AtomicInteger mSubmit = new AtomicInteger();
        private final AtomicInteger mDone = new AtomicInteger();
        private final AtomicInteger mCancel = new AtomicInteger();
        private final AtomicLong mCancelBytes = new AtomicLong();
        private final AtomicInteger mHit = new AtomicInteger();
        private final AtomicInteger mMiss = new AtomicInteger();

        @Override
        public void onPreloadSubmit(String url, int position) {
            report();
        }

        @Override
        public void onPreloadDone(String url, int position, long bytes, long costMs) {
            mDone.incrementAndGet();
        }

        @Override
        public void onPreloadCancel(String url, int position, long bytes) {
            mCancel.incrementAndGet();
            mCancelBytes.addAndGet(bytes);
        }

        @Override
        public void onPreloadHit(String url, int position) {
            mHit.incrementAndGet();
        }

        @Override
        public void onPreloadMiss(String url, int position) {
            mMiss.incrementAndGet();
        }

        private void report() {
            int submit = mSubmit.incrementAndGet();
            if (submit % REPORT_INTERVAL != 0) return;
            int hit = mHit.get();
            int total = hit + mMiss.get();
            Logger.info("预加载统计：提交 " + submit
                    + " 完成 " + mDone.get()
                    + " 取消 " + mCancel.get() + "（浪费 " + (mCancelBytes.get() / 1024) + "KB）"
                    + " 命中 " + hit + "/" + total
                    + (total > 0 ? "（" + (hit * 100 / total) + "%）" : ""));
        }
    }

    /**
     * 释放全部预加载资源（一般在应用退出时调用）
     */
    public void shutdown() {
        mShutdown = true;
        for (PreloadTask task : mTasks.values()) {
            task.cancel();
        }
        mTasks.clear();
        mInFlight.clear();
        mPositions.clear();
        mSessions.clear();
        mPreloadedUrls.clear();
        mCheckedUrls.clear();
        // 业务方可能传入持有 Activity 的匿名内部类，不清掉会泄漏
        mPreloadListener = null;
        mWorkerPool.shutdownNow();
        mScheduler.shutdownNow();
        mTailExecutor.shutdownNow();
    }
}
