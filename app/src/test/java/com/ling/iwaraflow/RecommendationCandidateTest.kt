package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 候选一路带着来源信息：实时重排接得上第一次排序的口径，界面也能说清「为什么推荐给我」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationCandidateTest {
    private fun item(id: String = "v1") = VideoItem(id, "标题", "Alice", listOf("miku"), 10, authorId = "author-1")

    @Test fun theReasonNamesTheStrongestSignal() {
        assertEquals("你关注的作者更新了", RecommendationCandidate(item(), subscribed = true).reason())
        assertEquals("为你探索的新内容", RecommendationCandidate(item(), exploration = true).reason())
        assertEquals("你最近常看 #miku", RecommendationCandidate(item()).also { it.matchedTags += "miku" }.reason())
        assertEquals("你喜欢这位作者的作品", RecommendationCandidate(item()).also { it.matchedAuthorId = "author-1" }.reason())
        assertEquals("历史上的高赞作品", RecommendationCandidate(item(), classic = true).reason())
        assertEquals(
            "近期的高赞作品",
            RecommendationCandidate(item()).also { it.sources += RecommendationCandidate.Source.MONTH_TOP }.reason()
        )
        assertEquals("综合推荐", RecommendationCandidate(item()).reason())
    }

    /**
     * 同一条视频在新一轮里被重新召回时，不能沿用上一轮的来源和标记：
     * 上一轮它是“为你探索的新内容”，这一轮可能只是官方榜单回退来的。
     */
    @Test fun aNewRoundDoesNotInheritTheLastRoundsCandidate() {
        val ranker = RecommendationRanker()
        ranker.remember(item()) {
            it.exploration = true
            it.sourceScore = 9.0
            it.matchedTags += "miku"
        }
        assertEquals("为你探索的新内容", ranker.reasonFor("v1"))

        ranker.startGeneration()
        ranker.remember(item()) { it.sources += RecommendationCandidate.Source.OFFICIAL }
        val fresh = ranker.candidateFor("v1")!!
        assertEquals("综合推荐", fresh.reason())
        assertFalse("上一轮的探索位标记不该带过来", fresh.exploration)
        assertEquals("上一轮的来源分也不该带过来", 0.0, fresh.sourceScore, 1e-9)
        assertTrue(fresh.matchedTags.isEmpty())
    }

    /** 同一轮里再记一次是补充信息，不是重来一次。 */
    @Test fun rememberingTwiceInOneRoundKeepsWhatIsAlreadyThere() {
        val ranker = RecommendationRanker()
        ranker.remember(item()) { it.sourceScore = 4.0 }
        ranker.remember(item()) { it.classic = true }
        val candidate = ranker.candidateFor("v1")!!
        assertEquals(4.0, candidate.sourceScore, 1e-9)
        assertTrue(candidate.classic)
    }

    /** 关注 / 取关立刻改推荐引擎的关注名单，不用等最长 6 小时的缓存过期。 */
    @Test fun followingChangesReachTheEngineRightAway() {
        val engine = RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java))
        try {
            RecommendationEngine.notifyFollowChanged("author-9", true)
            assertTrue("author-9" in engine.followedAuthorIds())
            RecommendationEngine.notifyFollowChanged("author-9", false)
            assertFalse("author-9" in engine.followedAuthorIds())
        } finally { engine.close() }
    }

    /** 关掉的引擎不再接通知：页面已经没了，不该还在改它的状态。 */
    @Test fun aClosedEngineStopsListening() {
        val engine = RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java))
        engine.close()
        RecommendationEngine.notifyFollowChanged("author-9", true)
        assertFalse("author-9" in engine.followedAuthorIds())
    }
}
