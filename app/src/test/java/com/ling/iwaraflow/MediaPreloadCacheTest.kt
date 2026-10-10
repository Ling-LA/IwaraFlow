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
        val factory = factory(cache).setUpstreamDataSourceFactory { source(bytes, opens, false) }
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
        factory(cache).setUpstreamDataSourceFactory { source(bytes, opens, true) }
        val current = "https://cache.test/current-${System.nanoTime()}"; val next = "$current-next"
        try {
            cache.keepPreloads(setOf(current, next)); cache.prefetchFull(current); cache.prefetchFull(next, false)
            waitFor { opens.get() >= 2 }
            cache.keepPreloads(setOf(current))
            waitFor { cache.progress(current).fraction == 1.0 }
            assertEquals(0.0, cache.progress(next).fraction, 0.0)
            cache.keepPreloads(setOf(current, next)); cache.prefetchFull(next, true)
            waitFor { cache.progress(next).fraction == 1.0 }
            assertEquals(1.0, cache.progress(current).fraction, 0.0)
        } finally { cache.close() }
    }
}
