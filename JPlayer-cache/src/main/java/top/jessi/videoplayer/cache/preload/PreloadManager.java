package top.jessi.videoplayer.cache.preload;

import android.content.Context;

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

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import top.jessi.videoplayer.cache.HttpProxyCacheServer;
import top.jessi.videoplayer.cache.internal.Logger;

/**
 * 预加载引擎：持有线程池、任务表与缓存状态，<b>不持有任何页面状态</b>。
 * <p>
 * 页面相关的状态（url 列表、当前下标、预加载窗口）由每页一个的 {@link PreloadSession} 持有，
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

    /**
     * 默认预加载大小：2MB
     */
    public static final int DEFAULT_PRELOAD_LENGTH = 1024 * 1024 * 2;

    /**
     * 默认窗口大小：当前下标 ± 4
     */
    public static final int DEFAULT_WINDOW_SIZE = 4;

    /**
     * 默认执行线程数
     */
    public static final int DEFAULT_WORKER_COUNT = 3;

    /**
     * 执行队列容量。超出后丢弃新任务（窗口会重新计算，丢弃是安全的），
     * 避免滚动时任务无限堆积。
     */
    private static final int WORK_QUEUE_CAPACITY = 8;

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
     * 调度线程：单线程串行，处理窗口重算与任务提交，避免并发竞态
     */
    private final ExecutorService mScheduler;

    /**
     * 执行池：固定线程数 + 有界队列 + 丢弃策略
     */
    private final ThreadPoolExecutor mWorkerPool;

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
    private volatile int mWindowSize = DEFAULT_WINDOW_SIZE;
    /**
     * 默认网络策略（基于 ConnectivityManager），网络变化时触发窗口重算
     */
    private final ConnectivityNetworkPolicy mDefaultNetworkPolicy;

    private volatile NetworkPolicy mNetworkPolicy;

    /**
     * 引擎是否已关闭
     */
    private volatile boolean mShutdown;

    private PreloadManager(Context context) {
        mHttpProxyCacheServer = ProxyVideoCacheManager.getProxy(context);
        // 默认策略：任何网络下都允许预加载（包含移动网络），但无网络时不发起请求
        mDefaultNetworkPolicy = new ConnectivityNetworkPolicy(context);
        mDefaultNetworkPolicy.setOnNetworkChangedListener(this::scheduleCompute);
        mNetworkPolicy = mDefaultNetworkPolicy;
        mScheduler = Executors.newSingleThreadExecutor(r -> new Thread(r, "preload-scheduler"));
        mWorkerPool = new ThreadPoolExecutor(
                DEFAULT_WORKER_COUNT,
                DEFAULT_WORKER_COUNT,
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
     * 设置预加载大小（字节），默认 {@link #DEFAULT_PRELOAD_LENGTH}
     */
    public PreloadManager setPreloadLength(int preloadLength) {
        if (preloadLength > 0) {
            mPreloadLength = preloadLength;
        }
        return this;
    }

    /**
     * 设置预加载窗口大小（当前下标 ± N），默认 {@link #DEFAULT_WINDOW_SIZE}
     */
    public PreloadManager setWindowSize(int windowSize) {
        if (windowSize >= 0) {
            mWindowSize = windowSize;
            scheduleCompute();
        }
        return this;
    }

    /**
     * 窗口大小，供 {@link PreloadSession} 在调度线程上读取
     */
    int getWindowSize() {
        return mWindowSize;
    }

    /**
     * 记录 url 的列表下标（仅用于日志）。
     * <p>只在调度线程调用，直接 {@code put} 即可；不使用 {@code putIfAbsent}，
     * 因为它在低版本 Android 上存在 API 可用性问题。
     */
    void putPosition(String url, int position) {
        mPositions.put(url, position);
    }

    /**
     * 设置网络策略。
     * <p>默认使用 {@link ConnectivityNetworkPolicy}：任何网络下都允许预加载（包含移动网络），
     * 但无可用网络时不发起请求。如需"仅 WiFi 预加载"，可调用
     * {@link #setCanPreLoadOnMobile(boolean) setCanPreLoadOnMobile(false)} 或传入自定义策略。
     */
    public PreloadManager setNetworkPolicy(NetworkPolicy policy) {
        if (policy == null || policy == mNetworkPolicy) return this;
        NetworkPolicy old = mNetworkPolicy;
        mNetworkPolicy = policy;
        if (old instanceof ConnectivityNetworkPolicy) {
            ((ConnectivityNetworkPolicy) old).release();
        }
        if (policy instanceof ConnectivityNetworkPolicy) {
            ((ConnectivityNetworkPolicy) policy).setOnNetworkChangedListener(this::scheduleCompute);
        }
        scheduleCompute();
        return this;
    }

    /**
     * 是否允许在移动网络下预加载，默认 {@code true}（不省流量）。
     * <p>仅在当前网络策略为默认的 {@link ConnectivityNetworkPolicy} 时生效。
     */
    public PreloadManager setCanPreLoadOnMobile(boolean canPreLoadOnMobile) {
        mDefaultNetworkPolicy.setCanPreLoadOnMobile(canPreLoadOnMobile);
        return this;
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
     * 在调度线程上重算预加载窗口。
     * <p>调度线程已关闭时静默忽略：页面销毁等回调可能晚于 {@link #shutdown()} 到达，
     * 直接 {@code execute} 会抛 {@link RejectedExecutionException}。
     */
    void scheduleCompute() {
        if (mShutdown) return;
        try {
            mScheduler.execute(this::computeWindows);
        } catch (RejectedExecutionException e) {
            // 调度线程已关闭，忽略
        }
    }

    private void computeWindows() {
        // url -&gt; 与当前下标的距离。同一 url 被多个会话命中时取最小距离。
        Map<String, Integer> desired = new HashMap<>();
        for (PreloadSession session : mSessions) {
            session.collectWindow(desired);
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

        if (!canPreloadNow() || desired.isEmpty()) return;

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
            task.executeOn(mWorkerPool);
        }
    }

    private PreloadTask newTask(String url) {
        PreloadTask task = new PreloadTask();
        task.mRawUrl = url;
        task.mCacheServer = mHttpProxyCacheServer;
        task.mPreloadLength = mPreloadLength;
        task.mListener = (u, preloaded) -> {
            if (preloaded) {
                mPreloadedUrls.add(u);
            } else {
                // 未读满，允许下次重新磁盘检查（缓存可能已被播放器写满）
                mCheckedUrls.remove(u);
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

    private boolean canPreloadNow() {
        return mNetworkPolicy.canPreload();
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
        try {
            File file = mHttpProxyCacheServer.getCacheFile(rawUrl);
            if (file.exists()) {
                return file.delete();
            }
        } catch (Exception e) {
            return false;
        }
        return true;
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
        return mHttpProxyCacheServer.getProxyUrl(rawUrl);
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
        mWorkerPool.shutdownNow();
        mScheduler.shutdownNow();
        if (mNetworkPolicy instanceof ConnectivityNetworkPolicy) {
            ((ConnectivityNetworkPolicy) mNetworkPolicy).release();
        }
    }
}
