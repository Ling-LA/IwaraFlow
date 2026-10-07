package com.ling.iwaraflow

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide Media3 cache facade.
 *
 * SimpleCache cannot be opened twice for the same directory. MainActivity and AuthorActivity can
 * coexist on the back stack, so all facades share one cache instance and one prefetch executor.
 *
 * Opening the 256 MB LRU index and its database used to run inside Activity.onCreate. It is now
 * built on a background thread while the first feed request is already in flight; callers join
 * only when a video really needs a media source.
 */
@OptIn(UnstableApi::class)
class MediaPreloadCache(context: Context) {
    private val appContext = context.applicationContext
    private val pending: Future<SharedState> = warmUp(appContext)
    @Volatile private var closed = false
    @Volatile private var desiredUrls: Set<String>? = null
    private val ownerId = ownerIds.incrementAndGet()
    data class Progress(val total: Long = -1, val cached: Long = 0, val bytesPerSecond: Long = 0) {
        val fraction: Double get() = if (total > 0) (cached.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    }
    private class Job {
        @Volatile var writer: CacheWriter? = null
        @Volatile var cancelled = false
        @Volatile var progress = Progress()
        @Volatile var primary = true
    }
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun progress(url: String?): Progress = if (url == null) Progress() else jobs[url]?.progress ?: Progress()

    /** At most current + one lookahead transfer. A new owner cancels obsolete requests. */
    @Synchronized fun keepPreloads(urls: Set<String>) {
        desiredUrls = urls.toSet()
        if (pending.isDone) runCatching {
            if (urls.isEmpty()) shared.evictor.owners.remove(ownerId) else shared.evictor.owners[ownerId] = urls
        }
        jobs.keys.filter { it !in urls }.forEach { url ->
            jobs.remove(url)?.let { it.cancelled = true; it.writer?.cancel() }
        }
    }

    fun prefetchFull(url: String, primary: Boolean = true) {
        if (closed || !url.startsWith("https://") || !hasDiskRoom()) return
        jobs[url]?.let { it.primary = primary; return }
        val job = Job().apply { this.primary = primary }
        if (jobs.putIfAbsent(url, job) != null) return
        initExecutor.execute prepare@{
        if (closed || job.cancelled || desiredUrls?.contains(url) == false) { jobs.remove(url, job); return@prepare }
        val state = runCatching { shared }.getOrElse { jobs.remove(url, job); return@prepare }
        state.evictor.owners.compute(ownerId) { _, keys -> keys.orEmpty() + url }
        state.executor.execute {
            var sampleAt = android.os.SystemClock.elapsedRealtime()
            var sampleBytes = 0L
            var measuredRate = 0L
            var throttleBytes = 0L
            val throttleStart = sampleAt
            try {
                val writer = CacheWriter(state.cacheFactory.createDataSourceForDownloading(), DataSpec.Builder().setUri(url).build(), null) { length, cached, added ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    sampleBytes += added
                    if (now - sampleAt >= 1000) {
                        measuredRate = sampleBytes * 1000 / (now - sampleAt)
                        sampleBytes = 0; sampleAt = now
                    }
                    val contiguous = if (length > 0) state.cache.getCachedLength(url, 0, length).coerceAtLeast(0) else cached
                    job.progress = Progress(length, contiguous, measuredRate)
                    if (job.cancelled || closed || !hasDiskRoom()) throw java.io.InterruptedIOException()
                    if (!job.primary && added > 0) {
                        // Leave most bandwidth to the currently prioritized video.
                        throttleBytes += added
                        val primaryRate = jobs.values.filter { it !== job }.maxOfOrNull { it.progress.bytesPerSecond } ?: 0
                        val limit = (primaryRate / 4).coerceIn(128 * 1024L, 2 * 1024 * 1024L)
                        var aheadMs = throttleBytes * 1000 / limit - (now - throttleStart)
                        while (aheadMs > 0 && !job.primary) {
                            if (job.cancelled || closed || !hasDiskRoom()) throw java.io.InterruptedIOException()
                            Thread.sleep(aheadMs.coerceAtMost(200))
                            aheadMs = throttleBytes * 1000 / limit - (android.os.SystemClock.elapsedRealtime() - throttleStart)
                        }
                    }
                }
                job.writer = writer
                if (!job.cancelled && !closed) {
                    writer.cache()
                    // The final fragment is committed on close, after the last progress callback.
                    val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(state.cache.getContentMetadata(url))
                        .takeIf { it > 0 } ?: job.progress.total
                    if (length > 0) job.progress = job.progress.copy(total = length,
                        cached = state.cache.getCachedLength(url, 0, length).coerceAtLeast(0))
                }
            } catch (_: Exception) {
                jobs.remove(url, job) // Allow retry after a transient network failure.
            } finally { job.writer = null }
        }
        }
    }

    private val shared: SharedState
        get() = try {
            pending.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    /** 本地文件（content:// / file://）不走缓存和 HTTP 数据源，直接读。 */
    private val localFactory by lazy { DefaultMediaSourceFactory(appContext) }
    private val uncachedFactory by lazy { DefaultMediaSourceFactory(DefaultHttpDataSource.Factory()
        .setUserAgent("IwaraFlow/Android")
        .setDefaultRequestProperties(mapOf("Referer" to "https://www.iwara.tv/"))
        .setTransferListener(PlaybackMetrics.transfer(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(appContext)))) }
    private fun hasDiskRoom(): Boolean = appContext.cacheDir.usableSpace > MIN_FREE_BYTES
    fun cacheBytes(): Long = if (pending.isDone) runCatching { shared.cache.cacheSpace }.getOrDefault(0L) else 0L
    fun clearIdleCache() {
        if (!pending.isDone) return
        val state = runCatching { shared }.getOrNull() ?: return
        state.executor.execute {
            val protected = state.evictor.owners.values.flatMap { it }.toSet()
            state.cache.keys.filterNot { it in protected }.forEach { runCatching { state.cache.removeResource(it) } }
        }
    }
    fun measuredBytesPerSecond(): Long =
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(appContext).bitrateEstimate / 8


    fun createMediaSource(url: String): MediaSource {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme == "content" || scheme == "file") {
            return localFactory.createMediaSource(MediaItem.fromUri(url))
        }
        if (!hasDiskRoom() || !pending.isDone) return uncachedFactory.createMediaSource(MediaItem.fromUri(url))
        return runCatching { shared.mediaSourceFactory.createMediaSource(MediaItem.fromUri(url)) }
            .getOrElse { uncachedFactory.createMediaSource(MediaItem.fromUri(url)) }
    }

    fun prefetch(url: String) = prefetch(url, 2L * 1024L * 1024L)

    /** Cache only the beginning of a video so the next swipe can start immediately. */
    fun prefetch(url: String, bytes: Long) {
        if (closed) return
        val state = shared
        state.executor.execute {
            runCatching {
                val dataSpec = DataSpec.Builder()
                    .setUri(url)
                    .setPosition(0)
                    .setLength(bytes)
                    .build()
                CacheWriter(state.cacheFactory.createDataSourceForDownloading(), dataSpec, null, null).cache()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        jobs.values.forEach { it.cancelled = true; it.writer?.cancel() }
        jobs.clear()
        initExecutor.execute {
            if (runCatching { pending.get() }.isSuccess) {
                state?.evictor?.owners?.remove(ownerId)
                releaseShared()
            }
        }
    }

    private class SharedState(
        val cache: SimpleCache,
        val evictor: ActiveVideoCacheEvictor,
        val cacheFactory: CacheDataSource.Factory,
        val mediaSourceFactory: DefaultMediaSourceFactory,
        val executor: ExecutorService
    )

    companion object {
        internal const val MIN_FREE_BYTES = 256L * 1024L * 1024L
        private val lock = Any()
        private val refs = AtomicInteger(0)
        private val ownerIds = AtomicInteger(0)
        @Volatile private var state: SharedState? = null
        private val initExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "IwaraFlow-media-cache-init").apply { isDaemon = true }
        }

        // 单线程串行化，等价于原来的 synchronized(lock) 顺序，引用计数不会错乱。
        private fun warmUp(context: Context): Future<SharedState> =
            initExecutor.submit<SharedState> { acquire(context) }

        private fun acquire(context: Context): SharedState = synchronized(lock) {
            val existing = state
            if (existing != null) {
                refs.incrementAndGet()
                return@synchronized existing
            }

            val databaseProvider = StandaloneDatabaseProvider(context)
            val evictor = ActiveVideoCacheEvictor(2L * 1024L * 1024L * 1024L)
            val cache = SimpleCache(
                File(context.cacheDir, "video_preload"),
                evictor,
                databaseProvider
            )
            val upstreamFactory = DefaultHttpDataSource.Factory()
                .setTransferListener(PlaybackMetrics.transfer(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context)))
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.iwara.tv/"))
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) = PlaybackMetrics.cached(cachedBytesRead)
                    override fun onCacheIgnored(reason: Int) {}
                })
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val created = SharedState(
                cache = cache,
                evictor = evictor,
                cacheFactory = cacheFactory,
                mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory),
                executor = Executors.newFixedThreadPool(3)
            )
            state = created
            refs.set(1)
            created
        }

        private fun releaseShared() = synchronized(lock) {
            if (refs.decrementAndGet() > 0) return@synchronized
            val current = state ?: return@synchronized
            current.executor.shutdownNow()
            runCatching { current.cache.release() }
            state = null
            refs.set(0)
        }
    }
}
