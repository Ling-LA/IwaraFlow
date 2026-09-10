package com.ling.iwaraflow

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 冷启动首屏：慢的候选视频不能拖住已经验证好的视频。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupFeedLatencyTest {
    private lateinit var server: MockWebServer

    @Before fun startFixtureCdn() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().setResponseCode(206).setBody("iwaraflow-fixture-bytes")
                val delayMs = request.path?.substringAfter("delay=", "")?.toLongOrNull() ?: 0L
                if (delayMs > 0L) response.setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
                return response
            }
        }
        server.start()
    }

    @After fun stopFixtureCdn() {
        server.shutdown()
    }

    private fun item(id: String, delayMs: Long = 0L): VideoItem {
        val url = server.url("/$id?delay=$delayMs").toString()
        return VideoItem(id, id, "fixture", emptyList(), 0, sources = listOf(VideoSource("Source", url, 10000)))
    }

    @Test fun firstBatchReachesTheFeedBeforeASlowCandidateFinishes() {
        val gate = PlayableVideoGate(mock(IwaraApi::class.java))
        val items = listOf(item("v0"), item("v1"), item("v2"), item("v3", 1500L), item("v4"))
        val headSeen = CountDownLatch(1)
        val batchSeen = CountDownLatch(1)
        val batchDelivered = AtomicBoolean(false)
        val headWasFirst = AtomicBoolean(false)
        val head = AtomicReference<List<String>>(emptyList())
        val batch = AtomicReference<List<String>>(emptyList())
        try {
            gate.filterPlayable(
                items,
                "highest",
                maxItems = items.size,
                onFirstBatch = { first ->
                    headWasFirst.set(!batchDelivered.get())
                    head.set(first.map { it.id })
                    headSeen.countDown()
                }
            ) { playable ->
                batch.set(playable.map { it.id })
                batchDelivered.set(true)
                batchSeen.countDown()
            }
            assertTrue("首屏批次没有单独送达", headSeen.await(10, TimeUnit.SECONDS))
            assertTrue("首屏批次必须早于完整批次", headWasFirst.get())
            assertEquals(listOf("v0", "v1", "v2"), head.get())
            assertTrue("完整批次没有送达", batchSeen.await(15, TimeUnit.SECONDS))
            assertEquals(listOf("v0", "v1", "v2", "v3", "v4"), batch.get())
        } finally {
            gate.close()
        }
    }

    @Test fun batchBudgetDropsAStragglerInsteadOfHoldingTheFeed() {
        val gate = PlayableVideoGate(mock(IwaraApi::class.java), batchBudgetMs = 800L)
        val items = listOf(item("v0"), item("v1", 3000L), item("v2"), item("v3"))
        val delivered = CountDownLatch(1)
        val ids = AtomicReference<List<String>>(emptyList())
        val startedAt = System.nanoTime()
        try {
            gate.filterPlayable(items, "highest") { playable ->
                ids.set(playable.map { it.id })
                delivered.countDown()
            }
            assertTrue("完整批次没有送达", delivered.await(15, TimeUnit.SECONDS))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(listOf("v0", "v2", "v3"), ids.get())
            assertTrue("超时候选拖住了整批：${elapsedMs}ms", elapsedMs < 2_500L)
        } finally {
            gate.close()
        }
    }

    @Test fun coldStartKeepsTheSmallCandidateWindow() {
        val gate = PlayableVideoGate(mock(IwaraApi::class.java))
        val items = (0 until 20).map { index -> item("cold$index") }
        val delivered = CountDownLatch(1)
        val ids = AtomicReference<List<String>>(emptyList())
        try {
            gate.filterPlayable(items, "highest", maxItems = 30) { playable ->
                ids.set(playable.map { it.id })
                delivered.countDown()
            }
            assertTrue("首屏批次没有送达", delivered.await(20, TimeUnit.SECONDS))
            assertEquals(PlayableVideoGate.COLD_START_CANDIDATES, ids.get().size)
        } finally {
            gate.close()
        }
    }

    @Test fun laterPagesValidateTheWholePage() {
        val gate = PlayableVideoGate(mock(IwaraApi::class.java))
        val items = (0 until 20).map { index -> item("page$index") }
        val delivered = CountDownLatch(1)
        val ids = AtomicReference<List<String>>(emptyList())
        try {
            gate.filterPlayable(items, "highest", maxItems = items.size, maxCandidates = items.size) { playable ->
                ids.set(playable.map { it.id })
                delivered.countDown()
            }
            assertTrue("续页批次没有送达", delivered.await(20, TimeUnit.SECONDS))
            assertEquals(items.map { it.id }, ids.get())
        } finally {
            gate.close()
        }
    }

    @Test fun refreshingRecommendationsMarksIwaraLikesAsSeen() {
        val api = mock(IwaraApi::class.java)
        val history = HistoryStore(RuntimeEnvironment.getApplication())
        `when`(api.isLoggedIn()).thenReturn(true)
        `when`(api.getFavoriteVideosBlocking(anyInt(), anyInt()))
            .thenReturn(listOf(VideoItem("liked-1", "liked", "fixture", emptyList(), 1)))
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            listOf(VideoItem("${invocation.getArgument<String>(0)}-1", "t", "fixture", emptyList(), 1))
        }
        val engine = RecommendationEngine(api, history)
        val delivered = CountDownLatch(1)
        try {
            engine.load(true) { delivered.countDown() }
            assertTrue("推荐列表没有返回", delivered.await(15, TimeUnit.SECONDS))
            assertTrue("Iwara 官方点赞没有计入已看", history.isSeen("liked-1"))
        } finally {
            engine.close()
            history.close()
        }
    }

    @Test fun stalledRankingListDoesNotHoldTheRecommendationFeed() {
        val api = mock(IwaraApi::class.java)
        val history = mock(HistoryStore::class.java)
        `when`(history.preferenceProfile()).thenReturn(PreferenceProfile(emptyMap(), emptyMap()))
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            if (sort == "popularity") Thread.sleep(6_000L)
            listOf(VideoItem("$sort-1", sort, "fixture", emptyList(), 1))
        }
        val engine = RecommendationEngine(api, history, listBudgetMs = 600L)
        val delivered = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        val startedAt = System.nanoTime()
        try {
            engine.load(false) { outcome ->
                result.set(outcome)
                delivered.countDown()
            }
            assertTrue("推荐列表没有返回", delivered.await(15, TimeUnit.SECONDS))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val videos = result.get().getOrNull()
            assertNotNull("推荐列表不应该整体失败", videos)
            assertTrue("其它榜单的候选必须保留", videos!!.isNotEmpty())
            assertTrue("超时榜单不应该进入结果", videos.none { it.id == "popularity-1" })
            assertTrue("慢榜单拖住了冷启动：${elapsedMs}ms", elapsedMs < 3_000L)
        } finally {
            engine.close()
        }
    }
}
