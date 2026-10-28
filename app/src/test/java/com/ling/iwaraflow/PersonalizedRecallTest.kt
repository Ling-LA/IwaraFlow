package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonalizedRecallTest {
    private val profile = PreferenceProfile(emptyMap(), mapOf("music" to 8.0))
    private fun video(id: String, personal: Boolean = false) = VideoItem(id, id, "author-$id",
        if (personal) listOf("music") else emptyList(), 400, views = 6000, createdAt = System.currentTimeMillis())

    private fun history(): HistoryStore = mock(HistoryStore::class.java).also {
        `when`(it.preferenceProfile()).thenReturn(profile)
        `when`(it.isSeen(anyString())).thenAnswer { call -> call.getArgument<String>(0).startsWith("old") }
    }

    private fun load(engine: RecommendationEngine): List<VideoItem> {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        engine.load(true) { result.set(it); done.countDown() }
        assertTrue("Recommendation callback missing", done.await(10, TimeUnit.SECONDS))
        return result.get().getOrThrow()
    }

    @Test fun interestRecallIncludesBothHighLikesAndNewUploads() {
        val engine = RecommendationEngine(mock(IwaraApi::class.java), history(), random = Random(18))
        try {
            engine.useProfile(profile)
            val first = engine.requestsFor(0).filter { it.sort.startsWith(RecommendationEngine.TAG_PREFIX) }
            assertEquals(setOf("likes", "date"), first.map { it.tagSort }.toSet())
            assertTrue(first.all { it.sort == RecommendationEngine.TAG_PREFIX + "music" && it.page in 0..1 })
            val next = engine.requestsFor(1).filter { it.sort.startsWith(RecommendationEngine.TAG_PREFIX) }
            assertEquals(setOf("likes", "date"), next.map { it.tagSort }.toSet())
            assertTrue(next.all { it.page in 2..7 })
        } finally { engine.close() }
    }

    @Test fun watchedInterestPagesAreReplenishedEvenWhenGlobalChartsHaveEnoughUnseenItems() {
        val api = mock(IwaraApi::class.java)
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenReturn(
            VideoListPage((0 until 60).map { video("global$it") }, -1))
        `when`(api.getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())).thenAnswer { call ->
            val prefix = if (call.getArgument<Int>(1) < 2) "old" else "fresh-interest"
            (0 until 20).map { video("$prefix$it", personal = true) }
        }
        val engine = RecommendationEngine(api, history(), random = Random(12))
        try {
            val feed = load(engine)
            assertTrue("Previously watched interest pages must not leave only global results",
                feed.any { it.id.startsWith("fresh-interest") })
            assertTrue(feed.none { it.id.startsWith("old") })
            verify(api, times(4)).getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())
        } finally { engine.close() }
    }

    @Test fun emptyInterestSourcesDoNotAddAnotherStartupRound() {
        val api = mock(IwaraApi::class.java)
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenReturn(
            VideoListPage((0 until 24).map { video("global$it") }, -1))
        `when`(api.getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())).thenReturn(emptyList())
        val engine = RecommendationEngine(api, history())
        try {
            assertEquals(24, load(engine).size)
            verify(api, times(2)).getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())
        } finally { engine.close() }
    }

    @Test fun completedSourcesSurviveAnEarlierTimedOutRequest() {
        val api = mock(IwaraApi::class.java)
        val returned = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        `when`(api.getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())).thenAnswer { call ->
            if (call.getArgument<String>(3) == "likes") {
                returned.await(2, TimeUnit.SECONDS)
                blocked.await(5, TimeUnit.SECONDS)
                emptyList<VideoItem>()
            } else {
                val items = (0 until 24).map { video("ready$it", personal = true) }
                returned.countDown()
                items
            }
        }
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenReturn(VideoListPage(emptyList(), -1))
        val engine = RecommendationEngine(api, history(), listBudgetMs = 500L)
        try {
            val start = System.nanoTime()
            assertEquals((0 until 24).map { "ready$it" }.toSet(), load(engine).map { it.id }.toSet())
            assertTrue("An unfinished source must not extend the request budget", (System.nanoTime() - start) / 1_000_000 < 2500)
        } finally { blocked.countDown(); engine.close() }
    }

    @Test fun optionalInterestRefillSharesTheFirstBatchDeadline() {
        val api = mock(IwaraApi::class.java)
        val blocked = CountDownLatch(1)
        `when`(api.getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())).thenAnswer { call ->
            if (call.getArgument<Int>(1) < 2) (0 until 20).map { video("old$it", personal = true) }
            else {
                blocked.await(4, TimeUnit.SECONDS)
                emptyList<VideoItem>()
            }
        }
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenAnswer { call ->
            if (call.getArgument<String>(0) == "trending") Thread.sleep(800L)
            VideoListPage((0 until 24).map { video("global$it") }, -1)
        }
        val engine = RecommendationEngine(api, history(), listBudgetMs = 1000L)
        try {
            val start = System.nanoTime()
            assertEquals(24, load(engine).size)
            assertTrue("Optional recall must not receive another full timeout",
                (System.nanoTime() - start) / 1_000_000 < 1600)
            verify(api, times(4)).getVideosByTagBlocking(anyString(), anyInt(), anyInt(), anyString())
        } finally { blocked.countDown(); engine.close() }
    }
}
