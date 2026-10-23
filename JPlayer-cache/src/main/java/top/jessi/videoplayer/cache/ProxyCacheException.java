package top.jessi.videoplayer.cache;

/**
 * Indicates any error in work of {@code ProxyCache}.
 *
 * @author Alexey Danilov
 */
public class ProxyCacheException extends Exception {

    public ProxyCacheException(String message) {
        super(message);
    }

    public ProxyCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProxyCacheException(Throwable cause) {
        super("No explanation error", cause);
    }
}
