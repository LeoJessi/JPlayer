package top.jessi.videoplayer.player;

/**
 * 播放状态实体类，包含状态码、描述信息和可选的异常对象。
 * <p>
 * 替代原有的 int 状态码，提供更丰富的状态信息。
 */
public class PlayState {

    /**
     * 播放状态码（与 BaseVideoView 中的 STATE_* 常量对应）
     */
    private int state;

    /**
     * 状态描述信息
     */
    private String message;

    /**
     * 异常对象（仅错误状态时有值）
     */
    private Throwable throwable;

    public PlayState() {
    }

    public PlayState(int state) {
        this.state = state;
    }

    public PlayState(int state, String message) {
        this.state = state;
        this.message = message;
    }

    public PlayState(int state, String message, Throwable throwable) {
        this.state = state;
        this.message = message;
        this.throwable = throwable;
    }

    /**
     * 创建只有状态码的 PlayState
     */
    public static PlayState of(int state) {
        return new PlayState(state);
    }

    /**
     * 创建带状态码和描述的 PlayState
     */
    public static PlayState of(int state, String message) {
        return new PlayState(state, message);
    }

    /**
     * 创建错误状态的 PlayState
     */
    public static PlayState error(String message, Throwable throwable) {
        return new PlayState(BaseVideoView.STATE_ERROR, message, throwable);
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    @Override
    public String toString() {
        return "PlayState{" +
                "state=" + state +
                ", message='" + message + '\'' +
                ", throwable=" + throwable +
                '}';
    }
}
