package top.jessi.videoplayer.cache.source;

import top.jessi.videoplayer.cache.headers.HeaderInjector;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfoStorage;
import top.jessi.videoplayer.cache.sourcestorage.SourceInfo;
import top.jessi.videoplayer.cache.HttpProxyCacheServer;

/**
 * Creates {@link Source} instances used by {@link HttpProxyCacheServer} to read remote content.
 * <p>
 * Two implementations are shipped:
 * <ul>
 * <li>{@link HttpUrlSourceFactory} (default) &mdash; backed by {@link java.net.HttpURLConnection}.</li>
 * <li>{@link OkHttpSourceFactory} &mdash; backed by OkHttp.</li>
 * </ul>
 * Select one with {@link HttpProxyCacheServer.Builder#sourceFactory(SourceFactory)}.
 */
public interface SourceFactory {

    /**
     * Creates a new source for the given url.
     *
     * @param url               a url to fetch.
     * @param sourceInfoStorage storage of previously fetched source info.
     * @param headerInjector    extra headers to add to the request.
     * @return a new source.
     */
    Source create(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector);

    /**
     * Creates a copy of the given source. The copy shares the cached {@link SourceInfo} and
     * configuration but has no opened connection, so it can be used to read independently.
     *
     * @param source a source to copy.
     * @return a detached copy of the source.
     */
    Source copy(Source source);
}
