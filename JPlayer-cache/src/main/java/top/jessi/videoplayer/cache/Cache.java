package top.jessi.videoplayer.cache;

/**
 * Cache for proxy.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public interface Cache {

    long available() throws ProxyCacheException;

    int read(byte[] buffer, long offset, int length) throws ProxyCacheException;

    void append(byte[] data, int length) throws ProxyCacheException;

    /**
     * 判断 {@code [offset, offset + length)} 是否<b>完全</b>已缓存。
     * <p>用于支持"非连续缓存"（如缓存了文件开头 + 文件末尾的 moov）：
     * 顺序缓存的前缀之外，可能还存在若干离散区间。仅当请求区间落在
     * 某个已缓存区间内时返回 {@code true}，否则应回源拉取。
     * <p>默认实现按"连续前缀"语义判断，即 {@code offset + length <= available()}；
     * 支持离散区间的实现（如 {@code FileCache}）应覆盖此方法。
     */
    default boolean isCached(long offset, long length) throws ProxyCacheException {
        return offset >= 0 && length >= 0 && offset + length <= available();
    }

    /**
     * 在指定偏移写入数据（支持"空洞写入"）。
     * <p>用于把文件末尾的 moov 等元数据写入缓存，使其不落在顺序前缀内。
     * <p>默认实现委托给 {@link #append}（仅适用于 offset 恰好等于当前长度的顺序写入），
     * 支持随机写的实现应覆盖此方法。
     */
    default void appendAt(long offset, byte[] data, int length) throws ProxyCacheException {
        append(data, length);
    }

    void close() throws ProxyCacheException;

    void complete() throws ProxyCacheException;

    boolean isCompleted();
}
