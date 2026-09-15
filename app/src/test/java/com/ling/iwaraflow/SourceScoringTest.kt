package com.ling.iwaraflow

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 来源分与来源权重。
 *
 * 热门 / 流行 / 月高赞说的其实是同一件事（“很多人点赞”），而点赞和播放量又已经算进
 * 质量分；以前每多命中一路就 `+0.55 ×`，同时上三个榜等于把“热门”奖励了三次还多。
 * 另一头，写死的来源权重是“我们认为哪一路重要”，不是“这个用户实际更吃哪一路”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceScoringTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun item(id: String = "v1") = VideoItem(id, id, "Alice", listOf("miku"), 10, authorId = "id-Alice")

    @Test fun threeQualityBoardsCountAsOneStrongSignalPlusABit() {
        val candidate = RecommendationCandidate(item())
        candidate.noteSource(RecommendationCandidate.Source.TRENDING, 3.0)
        candidate.noteSource(RecommendationCandidate.Source.POPULARITY, 2.6)
        candidate.noteSource(RecommendationCandidate.Source.MONTH_TOP, 2.8)
        // 同组取最高的 3.0，另外两次命中各给一点小加成，而不是 3.0 + 2.6×0.55 + 2.8×0.55。
        assertEquals(3.0 + 2 * RecommendationCandidate.MULTI_HIT_BONUS, candidate.sourceScore, 1e-9)
    }

    @Test fun differentGroupsAreDifferentEvidenceAndStillStack() {
        val candidate = RecommendationCandidate(item())
        candidate.noteSource(RecommendationCandidate.Source.TRENDING, 3.0)
        candidate.noteSource(RecommendationCandidate.Source.TAG, 2.4)
        assertEquals(3.0 + 2.4 * RecommendationCandidate.CROSS_GROUP_SHARE, candidate.sourceScore, 1e-9)
    }

    @Test fun sourcesAreGroupedByWhatTheyActuallyMean() {
        fun group(label: String) = RecommendationCandidate.sourceGroup(label)
        assertEquals(RecommendationCandidate.Group.QUALITY, group(RecommendationCandidate.Source.TRENDING))
        assertEquals(RecommendationCandidate.Group.QUALITY, group(RecommendationCandidate.Source.MONTH_TOP))
        assertEquals(RecommendationCandidate.Group.PERSONAL, group(RecommendationCandidate.Source.TAG))
        assertEquals(RecommendationCandidate.Group.PERSONAL, group(RecommendationCandidate.Source.AUTHOR))
        assertEquals(RecommendationCandidate.Group.SOCIAL, group(RecommendationCandidate.Source.SUBSCRIBED))
        assertEquals(RecommendationCandidate.Group.FRESH, group(RecommendationCandidate.Source.DATE))
    }

    /** 样本还不够时不学，照旧用写死的权重。 */
    @Test fun withoutEnoughDataNothingIsLearned() {
        repeat(5) { index ->
            history.recordImpression("s1", item("v$index"), HistoryStore.SURFACE_RECOMMEND, index, sources = "tag")
            history.noteImpressionOutcome("s1", "v$index", 60_000, 120_000, completed = false, skipped = false)
        }
        assertTrue(history.learnedSourceWeights().isEmpty())
    }

    /** 用户明显更看得下去标签召回的作品，那一路就该多给点比重。 */
    @Test fun aSourceTheUserActuallyWatchesGetsMoreWeight() {
        fun record(id: String, source: String, skipped: Boolean) {
            history.recordImpression("s1", item(id), HistoryStore.SURFACE_RECOMMEND, 0, sources = source)
            history.noteImpressionOutcome("s1", id, if (skipped) 1_000 else 90_000, 120_000, false, skipped)
        }
        repeat(30) { record("tag$it", "tag", skipped = false) }
        repeat(30) { record("hot$it", "trending", skipped = true) }

        val weights = history.learnedSourceWeights()
        assertTrue("标签召回该被抬起来：$weights", weights.getValue("tag") > 1.0)
        assertTrue("热门榜该被压下去：$weights", weights.getValue("trending") < 1.0)
        assertTrue("但都要限幅，不能一路跑飞", weights.values.all { it in HistoryStore.SOURCE_WEIGHT_MIN..HistoryStore.SOURCE_WEIGHT_MAX })
    }

    /** 调试信息和打分读的是同一份拆解，两边永远对得上。 */
    @Test fun theDebugBreakdownAddsUpToTheScore() {
        val ranker = RecommendationRanker()
        val video = item()
        ranker.remember(video) { it.sourceScore = 2.5 }
        val now = System.currentTimeMillis()
        val parts = ranker.scoreParts(video, ranker.profile, now)
        assertEquals(ranker.scoreOf(video, ranker.profile, now), parts.total, 1e-9)
        val text = ranker.explain(video.id, now)!!
        assertTrue(text.startsWith("总分 "))
        assertTrue("调试信息要写清来源", text.contains("来源"))
    }

    @Test fun anUnknownVideoHasNothingToExplain() {
        assertNull(RecommendationRanker().explain("never-seen"))
    }
}
