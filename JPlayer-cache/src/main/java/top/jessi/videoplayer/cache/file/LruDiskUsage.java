package top.jessi.videoplayer.cache.file;

import top.jessi.videoplayer.cache.internal.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link DiskUsage} that uses LRU (Least Recently Used) strategy to trim cache.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public abstract class LruDiskUsage implements DiskUsage {

    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();

    @Override
    public void touch(File file) throws IOException {
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
    }

    private void touchInBackground(File file) throws IOException {
        Files.setLastModifiedNow(file);
        List<File> files = Files.getLruListFiles(file.getParentFile());
        trim(files);
    }

    protected abstract boolean accept(File file, long totalSize, int totalCount);

    private void trim(List<File> files) {
        long totalSize = countTotalSize(files);
        int totalCount = files.size();
        for (File file : files) {
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
                } else {
                    Logger.error("Error deleting file " + file + " for trimming cache");
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
