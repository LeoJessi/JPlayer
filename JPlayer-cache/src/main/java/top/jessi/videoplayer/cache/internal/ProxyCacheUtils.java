package top.jessi.videoplayer.cache.internal;

import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkArgument;
import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;

/**
 * Just simple utils.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class ProxyCacheUtils {

    /**
     * 默认缓冲区大小：128KB。
     * <p>用于代理层读取缓存文件和网络数据的缓冲。增大缓冲区可以减少系统调用次数，
     * 提升 I/O 性能，尤其是在本地缓存命中的场景下。128KB 可最大化减少系统调用，
     * 同时避免过大缓冲区超出 CPU 缓存导致性能下降。
     */
    public static final int DEFAULT_BUFFER_SIZE = 128 * 1024;
    static final int MAX_ARRAY_PREVIEW = 16;

    public static String getSupposablyMime(String url) {
        MimeTypeMap mimes = MimeTypeMap.getSingleton();
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        return TextUtils.isEmpty(extension) ? null : mimes.getMimeTypeFromExtension(extension);
    }

    static void assertBuffer(byte[] buffer, long offset, int length) {
        checkNotNull(buffer, "Buffer must be not null!");
        checkArgument(offset >= 0, "Data offset must be positive!");
        checkArgument(length >= 0 && length <= buffer.length, "Length must be in range [0..buffer.length]");
    }

    static String preview(byte[] data, int length) {
        int previewLength = Math.min(MAX_ARRAY_PREVIEW, Math.max(length, 0));
        byte[] dataRange = Arrays.copyOfRange(data, 0, previewLength);
        String preview = Arrays.toString(dataRange);
        if (previewLength < length) {
            preview = preview.substring(0, preview.length() - 1) + ", ...]";
        }
        return preview;
    }

    public static String encode(String url) {
        try {
            return URLEncoder.encode(url, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error encoding url", e);
        }
    }

    public static String decode(String url) {
        try {
            return URLDecoder.decode(url, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error decoding url", e);
        }
    }

    public static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                Logger.error("Error closing resource");
            }
        }
    }

    public static String computeMD5(String string) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digestBytes = messageDigest.digest(string.getBytes());
            return bytesToHexString(digestBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bytesToHexString(byte[] bytes) {
        StringBuffer sb = new StringBuffer();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
