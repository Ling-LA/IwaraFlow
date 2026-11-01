@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ling.iwaraflow

import android.content.Intent
import android.os.SystemClock
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Can run in two separate instrumentation processes with -e phase prepare / restore. */
@RunWith(AndroidJUnit4::class)
class WarmRecommendationDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun setUpstream(cache: MediaPreloadCache, upstream: androidx.media3.datasource.DataSource.Factory) {
        val state = (field(cache, "pending") as Future<*>).get(5, TimeUnit.SECONDS)!!
        listOf("cacheFactory", "preloadCacheFactory", "readOnlyCacheFactory").forEach { name ->
            (field(state, name) as CacheDataSource.Factory).setUpstreamDataSourceFactory(upstream)
        }
    }
    private fun restore(cache: MediaPreloadCache): List<VideoItem> {
        val latch = CountDownLatch(1); var result = emptyList<VideoItem>()
        WarmRecommendationStore(context).restore(cache) { result = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }
    private fun seed(prefixOnly: Boolean = false): MediaPreloadCache {
        val prefs = AppPrefs(context)
        prefs.privateBrowsing = false; prefs.accountId = ""; prefs.isolateLibrary = false; prefs.isolateInterests = false
        WarmRecommendationStore.clear(context)
        val cache = MediaPreloadCache(context)
        val promoMedia = InstrumentationRegistry.getArguments().getString("promoMedia") == "true"
        val bytes = if (promoMedia) {
            require(context.packageName == "com.ling.iwaraflow.qa")
            java.io.File(context.filesDir, "promo-custom.mp4").readBytes()
        } else ins.context.assets.open("navigation-fixture.mp4").use { it.readBytes() }
        assertTrue(bytes.size > WarmRecommendationStore.MIN_PREFIX_BYTES)
        setUpstream(cache) { ByteArrayDataSource(bytes) }
        val stamp = System.nanoTime()
        val items = (1..2).map { index ->
            val source = VideoSource("720", "https://warm-fixture.invalid/$stamp/$index.mp4?expires=1", 720)
            VideoItem("warm-$stamp-$index", "中性缓存示例 $index", "示例作者", listOf("animation"), 0,
                streamUrl = source.url, sources = listOf(source))
        }
        cache.keepPreloads(items.mapTo(hashSetOf()) { it.streamUrl!! })
        items.forEach {
            cache.registerSources(it.id, it.sources!!)
            if (prefixOnly) cache.prefetch(it.streamUrl!!, 128 * 1024L) else cache.prefetchFull(it.streamUrl!!)
        }
        await("Both upcoming files must finish caching") {
            items.all { if (prefixOnly) cache.storedProgress(it.streamUrl!!).cached >= 128 * 1024L else cache.progress(it.streamUrl).fraction == 1.0 }
        }
        val ageDays = InstrumentationRegistry.getArguments().getString("ageDays", "0").toLong().coerceIn(0, 3650)
        val store = WarmRecommendationStore(context) { System.currentTimeMillis() - ageDays * 24 * 60 * 60 * 1000L }
        main { store.save(items) }
        await("Encrypted queue was not committed") { store.available() }
        context.getSharedPreferences("warm_device_test", 0).edit()
            .putInt("pid", android.os.Process.myPid()).putString("first", items.first().id)
            .putString("second", items.last().id).commit()
        return cache
    }

    @Test fun coldStartUsesEncryptedQueueAndPlaysCachedVideoWithExpiredUrl() {
        val phase = InstrumentationRegistry.getArguments().getString("phase", "both")
        if (phase != "restore") {
            val cache = seed()
            cache.close()
            if (phase == "prepare") return
        }
        val fixture = context.getSharedPreferences("warm_device_test", 0)
        if (phase == "restore") assertNotEquals("Must use a new process", fixture.getInt("pid", 0), android.os.Process.myPid())
        val cache = MediaPreloadCache(context)
        try {
            // Any unexpected media request fails. The expired .invalid URL cannot be fetched.
            val networkOpens = java.util.concurrent.atomic.AtomicInteger()
            setUpstream(cache) {
                object : androidx.media3.datasource.DataSource by ByteArrayDataSource(byteArrayOf(0)) {
                    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                        networkOpens.incrementAndGet()
                        throw java.io.IOException("Unexpected network request")
                    }
                }
            }
            val restored = restore(cache)
            assertEquals(listOf(fixture.getString("first", ""), fixture.getString("second", "")), restored.map { it.id })
            assertNotNull("Complete expired files remain playable offline", restored.first().sources)
            assertTrue("Quality selection must obtain fresh choices on demand", restored.first().refreshSourcesForSelection)
            context.getSharedPreferences(AppPrefs.FILE, 0).edit()
                .putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).putBoolean(PlaybackGuide.key(false), true).commit()
            val home = ins.startActivitySync(Intent(context, MainActivityV3::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivityV3
            try {
                main { (field(home, "api") as IwaraApi).close() }
                await("Cold Activity did not render cached video") {
                    var playing = false
                    main {
                        val adapter = field(home, "adapter") as VideoAdapter
                        val recycler = home.findViewById<ViewPager2>(R.id.pager).getChildAt(0) as RecyclerView
                        val holder = recycler.findViewHolderForAdapterPosition(0)
                        val player = holder?.let { field(it, "player") as? ExoPlayer }
                        playing = adapter.items.firstOrNull()?.id == fixture.getString("first", "") &&
                            player?.isPlaying == true && player.videoSize.width > 0 && player.currentPosition > 500
                    }
                    playing
                }
                assertEquals("Cached playback must not open the network source", 0, networkOpens.get())
                ProfileUiDeviceTest().screenshot("warm-cold-start")
            } finally { main { home.finish() }; ins.waitForIdleSync() }
        } finally { cache.close(); WarmRecommendationStore.clear(context) }
    }

    @Test fun seenMutedPrivacyAndAccountChangesRejectSavedQueue() {
        val cache = seed()
        try {
            val original = restore(cache)
            assertEquals(2, original.size)
            HistoryStore(context).use { it.markSeen(original.first().id) }
            assertEquals(listOf(original.last().id), restore(cache).map { it.id })
            HistoryStore(context).use { it.mute("tag", "animation") }
            assertTrue(restore(cache).isEmpty())
            HistoryStore(context).use { it.unmute("tag", "animation") }
            AppPrefs(context).accountId = "another-account"
            assertTrue(restore(cache).isEmpty())
            AppPrefs(context).accountId = ""
            val store = WarmRecommendationStore(context)
            main { store.save(original) }
            await("Queue save failed") { store.available() }
            AppPrefs(context).privateBrowsing = true
            assertFalse(store.available())
            assertTrue(restore(cache).isEmpty())
        } finally { AppPrefs(context).privateBrowsing = false; cache.close(); WarmRecommendationStore.clear(context) }
    }
    @Test fun malformedOrEvictedSnapshotsDoNotBlockNormalStartup() {
        val cache = seed()
        try {
            val secrets = SecureSessionStore(context)
            val key = "warm_recommendation_queue"
            val snapshot = secrets.secret(key)!!
            assertEquals(2, restore(cache).size)
            assertTrue(secrets.putSecretDurable(key, "{broken"))
            assertTrue(restore(cache).isEmpty())
            assertTrue(secrets.putSecretDurable(key, snapshot))
            cache.keepPreloads(emptySet()); cache.clearIdleCache()
            await("Idle cache was not cleared") { cache.cacheBytes() == 0L }
            assertTrue(restore(cache).isEmpty())
        } finally { cache.close(); WarmRecommendationStore.clear(context) }
    }

    @Test fun completeCachedBytesRemainReusableAfterSevenThirtyAndNinetyDays() {
        val cache = seed()
        val expected = ins.context.assets.open("navigation-fixture.mp4").use { it.readBytes() }
        try {
            val secrets = SecureSessionStore(context)
            val snapshot = secrets.secret("warm_recommendation_queue")!!
            val networkOpens = java.util.concurrent.atomic.AtomicInteger()
            setUpstream(cache) {
                object : androidx.media3.datasource.DataSource by ByteArrayDataSource(byteArrayOf(0)) {
                    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                        networkOpens.incrementAndGet(); throw java.io.IOException("Expired URL must never be requested")
                    }
                }
            }
            for (days in listOf(7, 30, 90)) {
                val aged = org.json.JSONObject(snapshot)
                val savedAt = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
                aged.put("savedAt", savedAt)
                val rows = aged.getJSONArray("rows")
                repeat(rows.length()) { rows.getJSONObject(it).put("sourceSavedAt", savedAt) }
                assertTrue(secrets.putSecretDurable("warm_recommendation_queue", aged.toString()))
                val restored = restore(cache)
                assertEquals("A $days-day-old queue still has two cached files", 2, restored.size)
                val state = (field(cache, "pending") as Future<*>).get(5, TimeUnit.SECONDS)!!
                val cacheOnly = field(state, "cacheOnlyFactory") as CacheDataSource.Factory
                restored.forEach { item ->
                    assertTrue(MediaCacheIdentity.isCacheOnly(item.streamUrl!!))
                    val reader = cacheOnly.createDataSource()
                    val output = java.io.ByteArrayOutputStream()
                    try {
                        reader.open(androidx.media3.datasource.DataSpec.Builder().setUri(item.streamUrl!!).build())
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = reader.read(buffer, 0, buffer.size)
                            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                            output.write(buffer, 0, n)
                        }
                    } finally { reader.close() }
                    assertArrayEquals("Cached media bytes changed after $days days", expected, output.toByteArray())
                }
            }
            assertEquals(0, networkOpens.get())
        } finally { cache.close(); WarmRecommendationStore.clear(context) }
    }

    @Test fun expiredPartialCacheRefreshesCredentialsAndFetchesOnlyMissingBytes() {
        val cache = seed(prefixOnly = true)
        try {
            val secrets = SecureSessionStore(context)
            val snapshot = org.json.JSONObject(secrets.secret("warm_recommendation_queue")!!)
            val first = snapshot.getJSONArray("rows").getJSONObject(0)
            val oldUrl = first.getString("url")
            val store = WarmRecommendationStore(context)
            val latch = CountDownLatch(1); var restored = emptyList<VideoItem>()
            store.restore(cache) { restored = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(2, restored.size)
            assertNull(restored.first().streamUrl); assertNull(restored.first().sources)
            assertEquals("720", restored.first().selectedQuality)
            // Saving upcoming items while fresh resolution is pending must keep their cached identity.
            main { store.save(restored) }
            assertEquals(2, restore(cache).size)
            val bytes = ins.context.assets.open("navigation-fixture.mp4").use { it.readBytes() }
            val positions = java.util.concurrent.CopyOnWriteArrayList<Long>()
            setUpstream(cache) {
                val data = ByteArrayDataSource(bytes)
                object : androidx.media3.datasource.DataSource by data {
                    override fun open(spec: androidx.media3.datasource.DataSpec): Long { positions += spec.position; return data.open(spec) }
                }
            }
            val freshUrl = oldUrl.substringBefore('?') + "?expires=${System.currentTimeMillis() / 1000 + 600}"
            val fresh = VideoSource("720", freshUrl, 720)
            MediaCacheIdentity.registerFresh(restored.first().id, listOf(fresh))
            assertEquals(MediaCacheIdentity.key(oldUrl), MediaCacheIdentity.key(freshUrl))
            cache.keepPreloads(setOf(freshUrl)); cache.prefetchFull(freshUrl)
            await("Fresh URL did not resume cached prefix") { cache.progress(freshUrl).fraction == 1.0 }
            assertTrue(positions.isNotEmpty())
            assertTrue("Cached prefix must not be downloaded again: $positions", positions.all { it >= 128 * 1024L })
        } finally { cache.close(); WarmRecommendationStore.clear(context) }
    }

    @Test fun savingRestoredUnsignedSnapshotDoesNotRenewItsCredentialLifetime() {
        val cache = seed()
        try {
            val secrets = SecureSessionStore(context)
            val key = "warm_recommendation_queue"
            val snapshot = org.json.JSONObject(secrets.secret(key)!!)
            val savedAt = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            snapshot.put("savedAt", savedAt)
            val rows = snapshot.getJSONArray("rows")
            repeat(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                val oldUrl = row.getString("url")
                val unsignedUrl = oldUrl.substringBefore('?')
                row.put("url", unsignedUrl).put("sourceSavedAt", savedAt)
                val sources = row.getJSONArray("sources")
                repeat(sources.length()) { j ->
                    val source = sources.getJSONObject(j)
                    if (source.getString("url") == oldUrl) source.put("url", unsignedUrl)
                }
            }
            assertTrue(secrets.putSecretDurable(key, snapshot.toString()))
            val store = WarmRecommendationStore(context)
            val latch = CountDownLatch(1); var restored = emptyList<VideoItem>()
            store.restore(cache) { restored = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(2, restored.size)
            assertTrue(restored.all { MediaCacheIdentity.isCacheOnly(it.streamUrl!!) })
            main { store.save(restored) }
            assertEquals(2, restore(cache).size) // Ordered after the queued durable save.
            val resaved = org.json.JSONObject(secrets.secret(key)!!).getJSONArray("rows")
            repeat(resaved.length()) { i ->
                val row = resaved.getJSONObject(i)
                assertEquals(savedAt, row.getLong("sourceSavedAt"))
                assertFalse(WarmRecommendationStore.sourceFresh(row.getString("url"), row.getLong("sourceSavedAt"), System.currentTimeMillis()))
            }
        } finally { cache.close(); WarmRecommendationStore.clear(context) }
    }

}
