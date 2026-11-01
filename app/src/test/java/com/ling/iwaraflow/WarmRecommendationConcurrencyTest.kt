package com.ling.iwaraflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WarmRecommendationConcurrencyTest {
    private fun workerBarrier() {
        val worker = WarmRecommendationStore::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(null) as ExecutorService
        worker.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun snapshot(item: VideoItem, owner: String): String = JSONObject()
        .put("version", 1).put("owner", owner).put("scope", "false|false")
        .put("savedAt", System.currentTimeMillis())
        .put("rows", JSONArray().put(JSONObject()
            .put("metadata", FeedSessionCodec.encode(FeedSessionStore.Session(listOf(item), 0, 0, true)))
            .put("name", "720").put("score", 720).put("url", item.streamUrl)
            .put("sourceSavedAt", System.currentTimeMillis())))
        .toString()

    /** Hold restore between its account check and row collection, then save from the UI side. */
    private fun overlappingRestore(changeAccount: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(app)
        prefs.privateBrowsing = false; prefs.isolateLibrary = false; prefs.isolateInterests = false
        prefs.accountId = "account-a"
        workerBarrier()
        val source = VideoSource("720", "https://cache.test/account-a-file?expires=1", 720)
        val original = VideoItem("same-video", "Fixture", "author", emptyList(), 0,
            streamUrl = source.url, sources = listOf(source))
        val disk = AtomicReference<String?>(snapshot(original, prefs.accountId))
        val readingCache = CountDownLatch(1)
        val continueRestore = CountDownLatch(1)
        val cache = mock(MediaPreloadCache::class.java)
        `when`(cache.storedProgress(source.url)).thenAnswer {
            readingCache.countDown()
            assertTrue(continueRestore.await(10, TimeUnit.SECONDS))
            MediaPreloadCache.Progress(1024 * 1024L, 128 * 1024L)
        }
        mockConstruction(SecureSessionStore::class.java) { secrets, _ ->
            `when`(secrets.secret("warm_recommendation_queue")).thenAnswer { disk.get() }
            doAnswer { call -> disk.set(call.getArgument(1)); true }
                .`when`(secrets).putSecretDurable(anyString(), nullable(String::class.java))
        }.use {
            try {
                val store = WarmRecommendationStore(app)
                store.restore(cache) {}
                assertTrue(readingCache.await(10, TimeUnit.SECONDS))
                if (changeAccount) {
                    prefs.accountId = "account-b"
                    store.save(listOf(original.copy(streamUrl = null, sources = null)))
                } else {
                    val fresh = VideoSource("720", "https://cache.test/newer-file?expires=1800000300", 720)
                    store.save(listOf(original.copy(streamUrl = fresh.url, sources = listOf(fresh))))
                }
                continueRestore.countDown()
                workerBarrier()
                // A second save without a current source exercises the retained-row fallback.
                store.save(listOf(original.copy(streamUrl = null, sources = null)))
                workerBarrier()
                if (changeAccount) {
                    assertNull("An old account's cached URL must not be re-saved under the new account", disk.get())
                } else {
                    assertEquals("An older restore must not overwrite a newer save", "https://cache.test/newer-file?expires=1800000300",
                        JSONObject(disk.get()!!).getJSONArray("rows").getJSONObject(0).getString("url"))
                }
            } finally {
                continueRestore.countDown()
                workerBarrier()
            }
        }
    }

    @Test fun accountChangeCannotImportRowsFromAnAlreadyRunningRestore() = overlappingRestore(changeAccount = true)
    @Test fun newerSaveWinsOverAnAlreadyRunningRestore() = overlappingRestore(changeAccount = false)
}
