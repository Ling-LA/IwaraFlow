package com.ling.iwaraflow

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MediaPreloadCacheTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun waitFor(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!done() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("Cache transfer did not reach expected state", done())
    }
    private fun factory(cache: MediaPreloadCache): CacheDataSource.Factory {
        val state = (field(cache, "pending").get(cache) as Future<*>).get(5, TimeUnit.SECONDS)!!
        return field(state, "cacheFactory").get(state) as CacheDataSource.Factory
    }
    private fun setUpstream(cache: MediaPreloadCache, upstream: DataSource.Factory): CacheDataSource.Factory {
        val state = (field(cache, "pending").get(cache) as Future<*>).get(5, TimeUnit.SECONDS)!!
        listOf("cacheFactory", "preloadCacheFactory").forEach { name ->
            (field(state, name).get(state) as CacheDataSource.Factory).setUpstreamDataSourceFactory(upstream)
        }
        return factory(cache)
    }
    private fun source(bytes: ByteArray, opens: AtomicInteger, slow: Boolean) = object : DataSource {
        val delegate = ByteArrayDataSource(bytes)
        override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
        override fun open(spec: DataSpec): Long { opens.incrementAndGet(); return delegate.open(spec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (slow) Thread.sleep(8)
            return delegate.read(buffer, offset, minOf(length, 32*1024))
        }
        override fun getUri() = delegate.uri
        override fun close() = delegate.close()
    }
    @Test fun fullPreloadReachesOneHundredPercentAndPlaybackReadsOnlyCachedBytes() {
        val cache = MediaPreloadCache(RuntimeEnvironment.getApplication())
        val opens = AtomicInteger(); val bytes = ByteArray(6*1024*1024) { (it % 127).toByte() }
        val factory = setUpstream(cache) { source(bytes, opens, false) }
        val url = "https://cache.test/full-${System.nanoTime()}"
        try {
            cache.keepPreloads(setOf(url)); cache.prefetchFull(url)
            waitFor { cache.progress(url).fraction == 1.0 }
            assertEquals(bytes.size.toLong(), cache.progress(url).cached)
            val requests = opens.get(); assertTrue(requests > 0)
            val reader = factory.createDataSource(); var count = 0
            reader.open(DataSpec.Builder().setUri(url).build())
            try {
                val buffer = ByteArray(32*1024)
                while (true) {
                    val n = reader.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break
                    for (i in 0 until n) assertEquals(bytes[count+i], buffer[i])
                    count += n
                }
            } finally { reader.close() }
            assertEquals(bytes.size, count); assertEquals("Cached replay must not fetch again", requests, opens.get())
            cache.prefetchFull(url); assertEquals(requests, opens.get())
        } finally { cache.close() }
    }
    @Test fun cancellingLookaheadKeepsCurrentTransferRunningAndAllowsLookaheadToRestart() {
        val cache = MediaPreloadCache(RuntimeEnvironment.getApplication())
        val opens = AtomicInteger(); val bytes = ByteArray(2*1024*1024) { 42 }
        setUpstream(cache) { source(bytes, opens, true) }
        val current = "https://cache.test/current-${System.nanoTime()}"; val next = "$current-next"
        try {
            cache.keepPreloads(setOf(current, next)); cache.prefetchFull(current); cache.prefetchFull(next, false)
            waitFor { opens.get() >= 2 }
            cache.keepPreloads(setOf(current))
            waitFor { cache.progress(current).fraction == 1.0 }
            assertTrue("Cancelled lookahead may retain reusable partial bytes", cache.progress(next).fraction < 1.0)
            cache.keepPreloads(setOf(current, next)); cache.prefetchFull(next, true)
            waitFor { cache.progress(next).fraction == 1.0 }
            assertEquals(1.0, cache.progress(current).fraction, 0.0)
        } finally { cache.close() }
    }

    @Test fun changingBudgetPreservesIdleCacheAndReadOnlyPlaybackDoesNotWrite() {
        val cache = MediaPreloadCache(RuntimeEnvironment.getApplication())
        val opens = AtomicInteger(); val bytes = ByteArray(256*1024) { 7 }
        setUpstream(cache) { source(bytes, opens, false) }
        val url = "https://cache.test/budget-${System.nanoTime()}"
        try {
            cache.keepPreloads(setOf(url)); cache.prefetchFull(url)
            waitFor { cache.progress(url).fraction == 1.0 }
            cache.keepPreloads(emptySet())
            AppPrefs(RuntimeEnvironment.getApplication()).cacheBudgetMb = 4096
            cache.applyBudget()
            // Queue a sentinel after the budget operation on the initialization worker.
            val executor = MediaPreloadCache::class.java.getDeclaredField("initExecutor").apply { isAccessible = true }.get(null) as java.util.concurrent.ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(bytes.size.toLong(), cache.storedProgress(url).cached)
            val state = (field(cache, "pending").get(cache) as Future<*>).get()!!
            val readonly = (field(state, "readOnlyCacheFactory").get(state) as CacheDataSource.Factory)
                .setUpstreamDataSourceFactory { source(bytes, opens, false) }
            fun read(link: String) {
                val reader = readonly.createDataSource()
                try {
                    reader.open(DataSpec.Builder().setUri(link).build())
                    val buffer = ByteArray(32*1024)
                    while (reader.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {}
                } finally { reader.close() }
            }
            val previous = opens.get()
            read(url); assertEquals(previous, opens.get())
            val uncached = "$url-other"
            read(uncached)
            assertEquals(previous + 1, opens.get())
            assertEquals("Low disk playback must not cache newly fetched bytes", 0L, cache.storedProgress(uncached).cached)
        } finally { cache.close() }
    }

    @Test fun cacheCleanupRequestedBeforeInitializationIsNotDropped() {
        val cache = MediaPreloadCache(RuntimeEnvironment.getApplication())
        val bytes = ByteArray(128*1024) { 9 }
        setUpstream(cache) { source(bytes, AtomicInteger(), false) }
        val url = "https://cache.test/clear-${System.nanoTime()}"
        try {
            cache.keepPreloads(setOf(url)); cache.prefetchFull(url)
            waitFor { cache.progress(url).fraction == 1.0 }
            cache.keepPreloads(emptySet())
            val second = MediaPreloadCache(RuntimeEnvironment.getApplication())
            try {
                second.clearIdleCache()
                waitFor { second.storedProgress(url).cached == 0L }
            } finally { second.close() }
        } finally { cache.close() }
    }

    @Test fun persistedProgressSurvivesDownloadingFacadeAndCacheOnlyMissNeverFetchesOldUrl() {
        val app = RuntimeEnvironment.getApplication()
        val cache = MediaPreloadCache(app)
        val opens = AtomicInteger()
        val bytes = ByteArray(128 * 1024) { 63 }
        val url = "https://cache.test/persisted-${System.nanoTime()}?expires=1"
        val mediaSource = VideoSource("720", url, 720)
        cache.registerSources("persisted", listOf(mediaSource))
        setUpstream(cache) { source(bytes, opens, false) }
        try {
            cache.keepPreloads(setOf(url)); cache.prefetchFull(url)
            waitFor { cache.progress(url).fraction == 1.0 }
            val second = MediaPreloadCache(app)
            try {
                // This facade has no in-memory Job; the persistent index is authoritative.
                factory(second)
                assertEquals(bytes.size.toLong(), second.progress(url).cached)
                assertEquals(1.0, second.progress(url).fraction, 0.0)
                MediaCacheIdentity.requireCachedBytes(url)
                val requestCount = opens.get()
                second.prefetchFull(url)
                val state = (field(second, "pending").get(second) as Future<*>).get()!!
                val cacheOnly = field(state, "cacheOnlyFactory").get(state) as CacheDataSource.Factory
                val reader = cacheOnly.createDataSource()
                try {
                    reader.open(DataSpec.Builder().setUri(url).build())
                    val buffer = ByteArray(32 * 1024)
                    var read = 0
                    while (true) {
                        val count = reader.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) break
                        read += count
                    }
                    assertEquals(bytes.size, read)
                } finally { reader.close() }
                cache.keepPreloads(emptySet()); second.clearIdleCache()
                waitFor { second.storedProgress(url).cached == 0L }
                val missing = cacheOnly.createDataSource()
                try {
                    try {
                        missing.open(DataSpec.Builder().setUri(url).build())
                        fail("Evicted expired cache must fail for re-resolution, never use old HTTP authorization")
                    } catch (_: java.io.IOException) { }
                } finally { missing.close() }
                assertEquals(requestCount, opens.get())
            } finally { second.close() }
        } finally { cache.close() }
    }

    @Test fun refreshedAuthorizationResumesOnlyMissingPrefixAndChangedFileUsesSeparateBytes() {
        val cache = MediaPreloadCache(RuntimeEnvironment.getApplication())
        val bytes = ByteArray(256 * 1024) { (it % 127).toByte() }
        val positions = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val oldUrl = "https://cache.test/partial-${System.nanoTime()}?version=1&expires=1"
        val renewedUrl = oldUrl.replace("expires=1", "expires=1800000300")
        val changedUrl = renewedUrl.replace("version=1", "version=2")
        val prefix = 64 * 1024L
        cache.registerSources("partial", listOf(VideoSource("720", oldUrl, 720)))
        setUpstream(cache) { ByteArrayDataSource(bytes) }
        try {
            cache.prefetch(oldUrl, prefix)
            waitFor { cache.storedProgress(oldUrl).cached >= prefix }
            MediaCacheIdentity.requireCachedBytes(oldUrl)
            setUpstream(cache) {
                val delegate = ByteArrayDataSource(bytes)
                object : DataSource by delegate {
                    override fun open(spec: DataSpec): Long { positions += spec.position; return delegate.open(spec) }
                }
            }
            MediaCacheIdentity.registerFresh("partial", listOf(VideoSource("720", renewedUrl, 720)))
            assertEquals(MediaCacheIdentity.key(oldUrl), MediaCacheIdentity.key(renewedUrl))
            cache.keepPreloads(setOf(renewedUrl)); cache.prefetchFull(renewedUrl)
            waitFor { cache.progress(renewedUrl).fraction == 1.0 }
            assertTrue(positions.isNotEmpty())
            assertTrue("Renewed request must skip the saved prefix: $positions", positions.all { it >= prefix })
            positions.clear()
            MediaCacheIdentity.registerFresh("partial", listOf(VideoSource("720", changedUrl, 720)))
            cache.keepPreloads(setOf(changedUrl)); cache.prefetchFull(changedUrl)
            waitFor { cache.progress(changedUrl).fraction == 1.0 }
            assertEquals("Different content must never splice old bytes", 0L, positions.first())
        } finally { cache.close() }
    }

    @Test fun releasingTheLastFacadeClosesItsCacheDatabase() {
        val cache = MediaPreloadCache(RuntimeEnvironment.getApplication())
        val state = (field(cache, "pending").get(cache) as Future<*>).get(5, TimeUnit.SECONDS)!!
        val provider = field(state, "databaseProvider").get(state) as androidx.media3.database.StandaloneDatabaseProvider
        val database = provider.writableDatabase
        assertTrue(database.isOpen)
        cache.close()
        val executor = MediaPreloadCache::class.java.getDeclaredField("initExecutor").apply { isAccessible = true }
            .get(null) as java.util.concurrent.ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
        assertFalse("Closing cache must close its SQLite connection too", database.isOpen)
    }
}
