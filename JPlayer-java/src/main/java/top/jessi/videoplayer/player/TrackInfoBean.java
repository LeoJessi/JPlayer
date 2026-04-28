package top.jessi.videoplayer.player;

/**
 * 单条轨道信息（音轨或字幕轨道）
 */
public class TrackInfoBean {
    /**
     * 轨道名称
     */
    public String name;
    /**
     * 轨道语言
     */
    public String language;
    /**
     * 轨道ID（各播放器内部使用的ID）
     */
    public int trackId;
    /**
     * 是否当前选中
     */
    public boolean selected;

    /**
     * 渲染器ID（ExoPlayer 使用）
     */
    public int renderId;
    /**
     * 分组ID（ExoPlayer 使用）
     */
    public int trackGroupId;
}
