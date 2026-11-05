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
     * 标记某个缓存文件正在被使用（已打开、正在读写）。
     * <p>实现方应保证被标记的文件不会被 LRU 清理误删：文件一旦被删除，
     * {@code RandomAccessFile} 的写入会落到已 unlink 的 inode 上，数据静默丢失，
     * 后续读取会拿到空洞或过期数据。
     * <p>默认空实现，供不需要清理的策略（如 {@code UnlimitedDiskUsage}）使用。
     */
    default void markActive(File file) {
    }

    /**
     * 取消 {@link #markActive(File)} 的标记（文件已关闭，可以被正常清理）。
     */
    default void markInactive(File file) {
    }

    /**
     * Releases resources owned by this policy. Implementations without background resources can
     * keep the default no-op behavior.
     */
    default void shutdown() {
    }

}
