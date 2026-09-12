package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * 打分里的质量分、多样性打散和按月点赞榜的月份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationQualityTest {
    private fun engine() =
        RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java), random = Random(3))

    @Test fun likeRateIsSmoothedSoTinySamplesDoNotWin() {
        val e = engine()
        val tiny = e.qualityScore(likes = 5, views = 10)          // 裸点赞率 50%
        val big = e.qualityScore(likes = 10_000, views = 100_000) // 10%
        assertTrue("十万播放一万赞要压过十次播放五个赞", big > tiny)
    }

    @Test fun betterLikeRateBeatsSameHeatWithWorseRate() {
        val e = engine()
        val good = e.qualityScore(likes = 800, views = 8_000)   // 10%
        val poor = e.qualityScore(likes = 800, views = 80_000)  // 1%
        assertTrue("点赞率高的分数高", good > poor)
    }

    @Test fun absoluteCountsStillMatterButLessThanBefore() {
        val e = engine()
        val popular = e.qualityScore(likes = 2_000, views = 20_000)
        val small = e.qualityScore(likes = 20, views = 200)
        assertTrue(popular > small)
        assertTrue("差距不该像纯点赞数那样悬殊", popular - small < 4.5)
        assertEquals("没有任何数据时打 0 分", 0.0, e.qualityScore(0, 0), 1e-9)
    }

    private fun by(author: String, n: Int) = VideoItem("$author-$n", "t", author, emptyList(), 1)

    @Test fun theSameAuthorIsSpreadOut() {
        val items = (0 until 5).map { by("a", it) } + (0 until 5).map { by("b", it) } + (0 until 5).map { by("c", it) }
        val out = engine().spreadAuthors(items, window = 2)
        assertEquals(items.map { it.id }.toSet(), out.map { it.id }.toSet())
        out.windowed(3).forEach { w ->
            assertEquals("任意连续 3 条里同一作者最多 1 条：${w.map { it.id }}", 3, w.map { it.author }.toSet().size)
        }
        assertEquals("同一作者内部顺序不变", (0 until 5).map { "a-$it" }, out.filter { it.author == "a" }.map { it.id })
    }

    @Test fun aSingleAuthorFeedIsLeftAlone() {
        val items = (0 until 6).map { by("solo", it) }
        assertEquals(items.map { it.id }, engine().spreadAuthors(items).map { it.id })
    }

    @Test fun monthsAreThisMonthAndTheLast() {
        // 2026-09-12 UTC
        val months = engine().monthsToSample(now = 1_789_000_000_000L)
        assertEquals(listOf("2026-09", "2026-08"), months)
        // 跨年
        assertEquals(listOf("2026-01", "2025-12"), engine().monthsToSample(now = 1_767_268_800_000L))
    }

    @Test fun roundZeroAsksForTheMonthlyTopCharts() {
        val api = mock(IwaraApi::class.java)
        val e = RecommendationEngine(api, mock(HistoryStore::class.java), random = Random(1))
        val monthly = e.requestsFor(0).filter { it.month.isNotBlank() }
        assertEquals(2, monthly.size)
        assertTrue(monthly.all { it.sort == "likes" && it.page < RecommendationEngine.MONTH_TOP_PAGES })
        e.close()
    }
}
