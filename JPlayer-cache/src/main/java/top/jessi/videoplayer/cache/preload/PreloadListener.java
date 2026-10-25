package top.jessi.videoplayer.cache.preload;

/**
 * 预加载事件回调，用于统计命中率、排查"预加载到底有没有用"。
 * <p>通过 {@link PreloadManager#setPreloadListener(PreloadListener)} 注册。
 * 不注册时引擎自带一份日志实现，可在 logcat 里直接看到汇总数据。
 * <p>所有回调都在<b>预加载线程</b>或 {@code getPlayUrl} 的调用线程（通常主线程）上触发，
 * 实现方自行保证线程安全，且不要做耗时操作。
 */
public interface PreloadListener {

    /**
     * 任务已提交到执行池。
     *
     * @param url        原始地址
     * @param position   列表下标，未知时为 {@code -1}
     */
    void onPreloadSubmit(String url, int position);

    /**
     * 读满预加载长度，缓存已就绪。
     *
     * @param url      原始地址
     * @param position 列表下标
     * @param bytes    实际读取的字节数
     * @param costMs   耗时（毫秒）
     */
    void onPreloadDone(String url, int position, long bytes, long costMs);

    /**
     * 任务被取消（滑出窗口 / 卡顿暂停）。
     *
     * @param url      原始地址
     * @param position 列表下标
     * @param bytes    取消前已读取的字节数——这部分带宽已经白花
     */
    void onPreloadCancel(String url, int position, long bytes);

    /**
     * 命中：播放器取地址时，该 url 已有可用的预加载缓存。
     */
    void onPreloadHit(String url, int position);

    /**
     * 未命中：播放器取地址时缓存不可用，退化为边播边存，这次预加载白做了。
     */
    void onPreloadMiss(String url, int position);
}
