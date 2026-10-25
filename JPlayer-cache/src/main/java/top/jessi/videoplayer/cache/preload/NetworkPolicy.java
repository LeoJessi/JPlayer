package top.jessi.videoplayer.cache.preload;

/**
 * 预加载网络策略。
 * <p>业务方可通过 {@link PreloadManager#setNetworkPolicy(NetworkPolicy)} 替换默认实现，
 * 例如实现"仅 WiFi 预加载"以节省流量。
 */
public interface NetworkPolicy {

    /**
     * 当前网络是否允许预加载。
     *
     * @return {@code true} 表示允许
     */
    boolean canPreload();

    /**
     * 默认策略：<b>任何网络下都允许预加载</b>（包含移动网络，默认不省流量）。
     * <p>如需节省流量，请通过 {@link PreloadManager#setNetworkPolicy(NetworkPolicy)} 替换为
     * 仅 WiFi 允许的实现。
     */
    NetworkPolicy DEFAULT = new NetworkPolicy() {
        @Override
        public boolean canPreload() {
            return true;
        }
    };
}
