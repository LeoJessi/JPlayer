package top.jessi.videoplayer.cache;

/**
 * Indicates interruption error in work of {@code ProxyCache} fired by user.
 *
 * @author Alexey Danilov
 */
public class InterruptedProxyCacheException extends ProxyCacheException {

    public InterruptedProxyCacheException(String message) {
        super(message);
    }

    public InterruptedProxyCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public InterruptedProxyCacheException(Throwable cause) {
        super(cause);
    }
}
