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
    private fun factory(cache: MediaPreloadCache): CacheDataSource.Factory {
        val state = (field(cache, "pending") as Future<*>).get(5, TimeUnit.SECONDS)!!
        return field(state, "cacheFactory") as CacheDataSource.Factory
    }
    private fun restore(cache: MediaPreloadCache): List<VideoItem> {
        val latch = CountDownLatch(1); var result = emptyList<VideoItem>()
        WarmRecommendationStore(context).restore(cache) { result = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }
    private fun seed(): MediaPreloadCache {
        val prefs = AppPrefs(context)
        prefs.privateBrowsing = false; prefs.accountId = ""; prefs.isolateLibrary = false; prefs.isolateInterests = false
        WarmRecommendationStore.clear(context)
        val cache = MediaPreloadCache(context)
        val bytes = ins.context.assets.open("navigation-fixture.mp4").use { it.readBytes() }
        assertTrue(bytes.size > WarmRecommendationStore.MIN_PREFIX_BYTES)
        factory(cache).setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }
        val stamp = System.nanoTime()
        val items = (1..2).map { index ->
            val source = VideoSource("720", "https://warm-fixture.invalid/$stamp/$index.mp4?expires=1", 720)
            VideoItem("warm-$stamp-$index", "中性缓存示例 $index", "示例作者", listOf("animation"), 0,
                streamUrl = source.url, sources = listOf(source))
        }
        cache.keepPreloads(items.mapTo(hashSetOf()) { it.streamUrl!! })
        items.forEach { cache.registerSources(it.id, it.sources!!); cache.prefetchFull(it.streamUrl!!) }
        await("Both upcoming files must finish caching") { items.all { cache.progress(it.streamUrl).fraction == 1.0 } }
        val store = WarmRecommendationStore(context)
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
            factory(cache).setUpstreamDataSourceFactory {
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
    @Test fun expiredMalformedOrEvictedSnapshotsDoNotBlockNormalStartup() {
        val cache = seed()
        try {
            val secrets = SecureSessionStore(context)
            val key = "warm_recommendation_queue"
            val snapshot = secrets.secret(key)!!
            assertEquals(2, restore(cache).size)
            assertTrue(secrets.putSecretDurable(key, org.json.JSONObject(snapshot)
                .put("savedAt", System.currentTimeMillis() - WarmRecommendationStore.MAX_AGE_MS - 1000).toString()))
            assertTrue(restore(cache).isEmpty())
            assertTrue(secrets.putSecretDurable(key, "{broken"))
            assertTrue(restore(cache).isEmpty())
            assertTrue(secrets.putSecretDurable(key, snapshot))
            cache.keepPreloads(emptySet()); cache.clearIdleCache()
            await("Idle cache was not cleared") { cache.cacheBytes() == 0L }
            assertTrue(restore(cache).isEmpty())
        } finally { cache.close(); WarmRecommendationStore.clear(context) }
    }

}
