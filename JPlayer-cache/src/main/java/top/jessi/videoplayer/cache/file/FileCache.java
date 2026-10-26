package top.jessi.videoplayer.cache.file;

import top.jessi.videoplayer.cache.Cache;
import top.jessi.videoplayer.cache.ProxyCacheException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * {@link Cache} that uses file for storing data.
 * <p>
 * 支持<b>离散区间缓存</b>：除了从 0 开始顺序下载的连续前缀外，还能把文件末尾的
 * 一段（如 MP4 的 moov）写入缓存。这样播放器起播按 Range 请求 moov 时可以直接命中，
 * 不必回源——这是"预加载秒播"的关键。
 * <p>
 * 区间状态通过同名的 {@code .meta} 旁文件持久化，进程重启后仍能识别已缓存的区间。
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class FileCache implements Cache {

    private static final String TEMP_POSTFIX = ".download";
    private static final String META_POSTFIX = ".meta";

    private final DiskUsage diskUsage;
    public File file;
    private RandomAccessFile dataFile;

    /**
     * 从 0 开始的连续缓存长度。
     * <p>顺序下载（预加载 / 边播边存）只扩展这一段；离散写入不影响它。
     * <p>是 {@link #available()} 的返回值，也是"缓存够不够本次请求"的判断基准。
     */
    private long contiguousLength;

    /**
     * 离散缓存区间的起始偏移，{@code -1} 表示不存在。
     * <p>用于缓存文件末尾的 moov：它不在连续前缀内，但播放器起播会按 Range 请求它。
     */
    private long tailOffset = -1;

    /** 离散缓存区间的长度 */
    private long tailLength;

    public FileCache(File file) throws ProxyCacheException {
        this(file, new UnlimitedDiskUsage());
    }

    public FileCache(File file, DiskUsage diskUsage) throws ProxyCacheException {
        try {
            if (diskUsage == null) {
                throw new NullPointerException();
            }
            this.diskUsage = diskUsage;
            File directory = file.getParentFile();
            Files.makeDir(directory);
            boolean completed = file.exists();
            this.file = completed ? file : new File(file.getParentFile(), file.getName() + TEMP_POSTFIX);
            this.dataFile = new RandomAccessFile(this.file, completed ? "r" : "rw");
            loadMeta();
        } catch (IOException e) {
            throw new ProxyCacheException("Error using file " + file + " as disc cache", e);
        }
    }

    /**
     * 从旁文件恢复区间状态。
     * <p>旁文件不存在时退化为"整个文件都是连续前缀"——这对未启用离散缓存的旧缓存是正确的。
     */
    private void loadMeta() {
        File metaFile = metaFile();
        if (!metaFile.exists()) {
            contiguousLength = file.length();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(metaFile))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    contiguousLength = Long.parseLong(parts[0]);
                    tailOffset = Long.parseLong(parts[1]);
                    tailLength = Long.parseLong(parts[2]);
                    return;
                }
            }
        } catch (Exception ignored) {
            // 旁文件损坏时退化为整文件连续
        }
        contiguousLength = file.length();
    }

    private void saveMeta() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(metaFile()))) {
            writer.write(contiguousLength + " " + tailOffset + " " + tailLength);
        } catch (IOException ignored) {
            // 旁文件只用于恢复区间，写失败不影响缓存数据本身
        }
    }

    private File metaFile() {
        return new File(file.getParentFile(), file.getName() + META_POSTFIX);
    }

    @Override
    public synchronized long available() throws ProxyCacheException {
        return contiguousLength;
    }

    @Override
    public synchronized int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        try {
            dataFile.seek(offset);
            return dataFile.read(buffer, 0, length);
        } catch (IOException e) {
            String format = "Error reading %d bytes with offset %d from file[%d bytes] to buffer[%d bytes]";
            throw new ProxyCacheException(String.format(format, length, offset, available(), buffer.length), e);
        }
    }

    @Override
    public synchronized void append(byte[] data, int length) throws ProxyCacheException {
        // 顺序追加等价于在连续前缀末尾写入；isCompleted 检查在 appendAt 内
        appendAt(contiguousLength, data, length);
    }

    /**
     * 在指定偏移写入数据。
     * <p>{@code offset == contiguousLength} 时扩展连续前缀；{@code offset > contiguousLength}
     * 时记录为离散区间（并合并相邻区间）；{@code offset < contiguousLength} 时视为重叠写入，忽略。
     */
    @Override
    public synchronized void appendAt(long offset, byte[] data, int length) throws ProxyCacheException {
        try {
            if (isCompleted()) {
                throw new ProxyCacheException("Error append cache: cache file " + file + " is completed!");
            }
            if (length < 0 || length > data.length) {
                throw new ProxyCacheException("Error append cache: invalid length " + length);
            }
            if (length == 0) {
                return;
            }
            dataFile.seek(offset);
            dataFile.write(data, 0, length);
            updateContiguous(offset, length);
            saveMeta();
        } catch (IOException e) {
            throw new ProxyCacheException("Error writing " + length + " bytes at " + offset + " to " + file, e);
        }
    }

    private void updateContiguous(long offset, int length) {
        long end = offset + length;
        if (offset == contiguousLength) {
            contiguousLength = end;
            // 与离散区间相连则合并成一条连续前缀
            if (tailOffset >= 0 && contiguousLength >= tailOffset) {
                contiguousLength = Math.max(tailOffset + tailLength, contiguousLength);
                tailOffset = -1;
                tailLength = 0;
            }
        } else if (offset > contiguousLength) {
            if (tailOffset < 0) {
                tailOffset = offset;
                tailLength = length;
            } else {
                long min = Math.min(tailOffset, offset);
                long max = Math.max(tailOffset + tailLength, end);
                tailOffset = min;
                tailLength = max - min;
            }
        }
        // offset < contiguousLength：重叠写入，不改变区间
    }

    /**
     * 判断 {@code [offset, offset + length)} 是否完全落在已缓存的区间内。
     */
    @Override
    public synchronized boolean isCached(long offset, long length) throws ProxyCacheException {
        if (offset < 0 || length < 0) {
            return false;
        }
        long end = offset + length;
        if (end <= contiguousLength) {
            return true;
        }
        return tailOffset >= 0 && offset >= tailOffset && end <= tailOffset + tailLength;
    }

    @Override
    public synchronized void close() throws ProxyCacheException {
        try {
            dataFile.close();
            diskUsage.touch(file);
        } catch (IOException e) {
            throw new ProxyCacheException("Error closing file " + file, e);
        }
    }

    @Override
    public synchronized void complete() throws ProxyCacheException {
        if (isCompleted()) {
            return;
        }

        close();
        String fileName = file.getName().substring(0, file.getName().length() - TEMP_POSTFIX.length());
        File completedFile = new File(file.getParentFile(), fileName);
        boolean renamed = file.renameTo(completedFile);
        if (!renamed) {
            throw new ProxyCacheException("Error renaming file " + file + " to " + completedFile + " for completion!");
        }
        file = completedFile;
        try {
            dataFile = new RandomAccessFile(file, "r");
            diskUsage.touch(file);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening " + file + " as disc cache", e);
        }
        // 完成后整个文件都是连续的，离散区间不再需要
        contiguousLength = file.length();
        tailOffset = -1;
        tailLength = 0;
        saveMeta();
    }

    @Override
    public synchronized boolean isCompleted() {
        return !isTempFile(file);
    }

    /**
     * Returns file to be used fo caching. It may as original file passed in constructor as some temp file for not completed cache.
     *
     * @return file for caching.
     */
    public File getFile() {
        return file;
    }

    private boolean isTempFile(File file) {
        return file.getName().endsWith(TEMP_POSTFIX);
    }

}
