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
    private val pending: Future<SharedState> = warmUp(context.applicationContext)
    private var closed = false

    private val shared: SharedState
        get() = try {
            pending.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    fun createMediaSource(url: String): MediaSource =
        shared.mediaSourceFactory.createMediaSource(MediaItem.fromUri(url))

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
                CacheWriter(state.cacheFactory.createDataSource(), dataSpec, null, null).cache()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // 引用计数必须等 acquire 真正跑完，否则会漏掉一次释放。
        runCatching { pending.get() }
        releaseShared()
    }

    private class SharedState(
        val cache: SimpleCache,
        val cacheFactory: CacheDataSource.Factory,
        val mediaSourceFactory: DefaultMediaSourceFactory,
        val executor: ExecutorService
    )

    companion object {
        private val lock = Any()
        private val refs = AtomicInteger(0)
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
            val cache = SimpleCache(
                File(context.cacheDir, "video_preload"),
                LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L),
                databaseProvider
            )
            val upstreamFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.iwara.tv/"))
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val created = SharedState(
                cache = cache,
                cacheFactory = cacheFactory,
                mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory),
                executor = Executors.newSingleThreadExecutor()
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
