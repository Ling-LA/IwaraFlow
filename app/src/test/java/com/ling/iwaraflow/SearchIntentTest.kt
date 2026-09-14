package com.ling.iwaraflow

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Random

/**
 * 搜索只是“我想看看这是什么”，不是“以后多给我推这个”：权重小、几个小时就淡掉，
 * 真正点开结果才算兴趣。另外，明确点过「不感兴趣：作者 / 标签」的内容连探索位都不给。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchIntentTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun searchCard(term: String) = VideoItem("search:$term", term, "", listOf(term), 0)

    @Test fun oneSearchNoLongerPinsTheProfile() {
        assertTrue("搜索权重要明显低于点开结果", HistoryStore.SEARCH_WEIGHT < HistoryStore.SEARCH_OPEN_WEIGHT)
        history.recordInteraction(searchCard("miku"), HistoryStore.ACTION_SEARCH, HistoryStore.SEARCH_WEIGHT)
        val search = history.preferenceProfile().tagWeights["miku"] ?: 0.0
        // 会话加成（×2.5）和标签折算（×0.45）之后仍然不该顶到召回门槛。
        assertTrue("一次搜索就有 $search，太容易带偏画像", search < RecommendationEngine.RECALL_TAG_MIN_WEIGHT)
    }

    @Test fun searchIntentFadesWithinHoursWhileWatchingDoesNot() {
        val now = System.currentTimeMillis()
        val store = HistoryStore(RuntimeEnvironment.getApplication())
        try {
            store.recordInteraction(searchCard("miku"), HistoryStore.ACTION_SEARCH, HistoryStore.SEARCH_WEIGHT)
            store.recordInteraction(VideoItem("v1", "t", "Alice", listOf("dance"), 0), "watch", 0.8)
            val fresh = store.preferenceProfileAt(now)
            // 一天以后：搜索几乎没了，看过的还在。
            val later = store.preferenceProfileAt(now + 24L * 3600_000L)
            val searchDrop = (later.tagWeights["miku"] ?: 0.0) / (fresh.tagWeights["miku"] ?: 1.0)
            val watchDrop = (later.tagWeights["dance"] ?: 0.0) / (fresh.tagWeights["dance"] ?: 1.0)
            assertTrue("搜索一天后该淡到一成以下：$searchDrop", searchDrop < 0.1)
            assertTrue("看过的内容不该跟着一起没：$watchDrop", watchDrop > searchDrop * 3)
        } finally { store.close() }
    }

    @Test fun anExplicitlyMutedAuthorNeverGetsAnExplorationSlot() {
        val engine = RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java), random = Random(4))
        try {
            engine.useProfile(
                PreferenceProfile(
                    authorWeights = mapOf("muted" to -5.0, "skipped" to -2.0),
                    tagWeights = emptyMap(),
                    mutedAuthors = setOf("muted")
                )
            )
            val feed = (0 until 60).map { VideoItem("ok-$it", "t", "Ok$it", emptyList(), 1) } +
                (0 until 5).map { VideoItem("muted-$it", "t", "Muted", emptyList(), 1) } +
                (0 until 5).map { VideoItem("skipped-$it", "t", "Skipped", emptyList(), 1) }
            val out = engine.exploreDisliked(feed)

            assertEquals(feed.size, out.size)
            // 探索位只能来自“划走得多”的那一类，明确拉黑的作者仍然沉在底部。
            val head = out.take(62).map { it.id }
            assertTrue("明确拉黑的作者不该被探索出来：$head", head.none { it.startsWith("muted-") })
            assertTrue("弱负反馈的内容仍然偶尔出现：$head", head.any { it.startsWith("skipped-") })
            assertEquals("拉黑的作者一条不少，只是都沉在底部", 5, out.drop(62).count { it.author == "Muted" })
        } finally { engine.close() }
    }
}
