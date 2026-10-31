package top.jessi.videoplayer.cache.file;

import java.io.File;
import java.io.IOException;

/**
 * Declares how {@link FileCache} will use disc space.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public interface DiskUsage {

    void touch(File file) throws IOException;

    /**
     * Releases resources owned by this policy. Implementations without background resources can
     * keep the default no-op behavior.
     */
    default void shutdown() {
    }

}
