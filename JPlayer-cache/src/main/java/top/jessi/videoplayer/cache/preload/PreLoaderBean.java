package top.jessi.videoplayer.cache.preload;

/**
 * 预加载使用到的实体类。
 */
public class PreLoaderBean {

    /**
     * 视频原始路径
     */
    private String originalUrl;

    /**
     * 本地代理路径
     */
    private String proxyUrl;

    /**
     * 预加载的 byte 数
     */
    private int preLoadBytes = 1024 * 1024 * 2;

    public PreLoaderBean() {
    }

    public PreLoaderBean(String originalUrl, String proxyUrl) {
        this.originalUrl = originalUrl;
        this.proxyUrl = proxyUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public String getProxyUrl() {
        return proxyUrl;
    }

    public void setProxyUrl(String proxyUrl) {
        this.proxyUrl = proxyUrl;
    }

    public int getPreLoadBytes() {
        return preLoadBytes;
    }

    public void setPreLoadBytes(int preLoadBytes) {
        this.preLoadBytes = preLoadBytes;
    }
}
