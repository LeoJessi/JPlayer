package top.jessi.videoplayer.cache.file;

import top.jessi.videoplayer.cache.internal.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link DiskUsage} that uses LRU (Least Recently Used) strategy to trim cache.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public abstract class LruDiskUsage implements DiskUsage {

    /** 空闲期定时清理的周期。 */
    private static final long TRIM_INTERVAL_MS = 5 * 60 * 1000L;
    /** 启动后首次定时清理前的等待时间。 */
    private static final long TRIM_INITIAL_DELAY_MS = 60 * 1000L;

    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService schedulerThread =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lru-disk-usage-scheduler");
                t.setDaemon(true);
                return t;
            });
    /** 正在被读写的缓存文件，清理时必须跳过。 */
    private final Set<File> mActiveFiles =
            Collections.newSetFromMap(new ConcurrentHashMap<File, Boolean>());
    /** 最近一次 touch 的缓存目录，供定时清理使用。 */
    private volatile File mCacheDirectory;
    /** 串行化所有 trim，避免 touch 触发的清理与定时清理并发删同一个文件。 */
    private final Object mTrimLock = new Object();

    protected LruDiskUsage() {
        schedulerThread.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                trimIfNeeded();
            }
        }, TRIM_INITIAL_DELAY_MS, TRIM_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void markActive(File file) {
        if (file != null) {
            mActiveFiles.add(file);
        }
    }

    @Override
    public void markInactive(File file) {
        if (file != null) {
            mActiveFiles.remove(file);
        }
    }

    @Override
    public void touch(File file) throws IOException {
        mCacheDirectory = file.getParentFile();
        if (workerThread.isShutdown()) {
            return;
        }
        try {
            workerThread.submit(new TouchCallable(file));
        } catch (RejectedExecutionException ignored) {
            // 关闭过程中的最后一次 touch 不影响缓存数据正确性。
        }
    }

    @Override
    public void shutdown() {
        workerThread.shutdown();
        schedulerThread.shutdown();
    }

    /** 定时清理：即使没有任何 close/complete 事件，也能把缓存压在上限以内。 */
    private void trimIfNeeded() {
        File dir = mCacheDirectory;
        if (dir == null) {
            return;
        }
        try {
            trim(Files.getLruListFiles(dir));
        } catch (Exception e) {
            Logger.warn("Error trimming cache: " + e.getMessage());
        }
    }

    private void touchInBackground(File file) throws IOException {
        Files.setLastModifiedNow(file);
        List<File> files = Files.getLruListFiles(file.getParentFile());
        trim(files);
    }

    protected abstract boolean accept(File file, long totalSize, int totalCount);

    private void trim(List<File> files) {
        synchronized (mTrimLock) {
            long totalSize = countTotalSize(files);
            int totalCount = files.size();
            for (File file : files) {
                // 正在读写的文件不能删：删掉后写入会落到已 unlink 的 inode 上，数据静默丢失。
                if (mActiveFiles.contains(file)) {
                    continue;
                }
                boolean accepted = accept(file, totalSize, totalCount);
                if (!accepted) {
                    long fileSize = file.length();
                    boolean deleted = file.delete();
                    if (deleted) {
                        File metaFile = new File(file.getParentFile(), file.getName() + ".meta");
                        if (metaFile.exists() && !metaFile.delete()) {
                            Logger.warn("Error deleting cache metadata " + metaFile);
                        }
                        totalCount--;
                        totalSize -= fileSize;
                        Logger.info("Cache file " + file + " is deleted because it exceeds cache limit");
                    } else if (file.exists()) {
                        Logger.error("Error deleting file " + file + " for trimming cache");
                    }
                }
            }
        }
    }

    private long countTotalSize(List<File> files) {
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        return totalSize;
    }

    private class TouchCallable implements Callable<Void> {

        private final File file;

        public TouchCallable(File file) {
            this.file = file;
        }

        @Override
        public Void call() throws Exception {
            touchInBackground(file);
            return null;
        }
    }
}
