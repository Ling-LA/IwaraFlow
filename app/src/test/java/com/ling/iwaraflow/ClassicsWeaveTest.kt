package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * 推荐流里穿插老片：现有规则不变，额外每 N 条一组，组内随机位置塞一条点赞很高的老视频。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClassicsWeaveTest {
    private fun video(id: String) = VideoItem(id, id, "作者", emptyList(), 1)
    private fun feed(n: Int) = (0 until n).map { video("new-$it") }
    private fun classics(n: Int) = (0 until n).map { video("old-$it") }
    private fun isClassic(item: VideoItem) = item.id.startsWith("old-")

    private fun engine(every: Int, seed: Long = 7L) =
        RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java), random = Random(seed))
            .apply { classicsEvery = every }

    @Test fun everyGroupOfTwelveCarriesExactlyOneClassic() {
        val out = engine(12).weaveClassics(feed(60), classics(10))
        assertEquals("60 条分 5 组，每组配 1 条老片", 65, out.size)
        val groups = out.chunked(13)
        assertEquals(5, groups.size)
        groups.forEachIndexed { index, group ->
            assertEquals("第 $index 组：${group.map { it.id }}", 1, group.count(::isClassic))
            assertEquals("组里其余 12 条是原来的推荐", 12, group.count { !isClassic(it) })
        }
        assertEquals("原来的顺序不能乱", feed(60).map { it.id }, out.filter { !isClassic(it) }.map { it.id })
    }

    @Test fun theClassicLandsAtDifferentPositionsInDifferentGroups() {
        val out = engine(12, seed = 3L).weaveClassics(feed(240), classics(30))
        val slots = out.chunked(13).map { it.indexOfFirst(::isClassic) }
        assertTrue("位置都一样就不叫随机：$slots", slots.toSet().size > 2)
        assertTrue("位置越界：$slots", slots.all { it in 0..12 })
    }

    @Test fun runningOutOfClassicsJustLeavesTheRestAlone() {
        val out = engine(12).weaveClassics(feed(60), classics(2))
        assertEquals(62, out.size)
        assertEquals(2, out.count(::isClassic))
        assertTrue("有老片的只能是前两组", out.drop(26).none(::isClassic))
    }

    @Test fun aClassicAlreadyInTheFeedIsNotShownTwice() {
        val dup = listOf(video("new-3"), video("old-1"))
        val out = engine(12).weaveClassics(feed(24), dup)
        assertEquals(1, out.count { it.id == "new-3" })
        assertEquals(1, out.count { it.id == "old-1" })
    }

    @Test fun zeroMeansOff() {
        val original = feed(30)
        assertSame(original, engine(0).weaveClassics(original, classics(5)))
        assertSame(original, engine(12).weaveClassics(original, emptyList()))
    }

    @Test fun theDefaultSpacingIsTwelve() {
        assertEquals(12, RecommendationEngine.DEFAULT_CLASSICS_EVERY)
    }

    // ---------------------------------------------------------------- 近期优先

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun dated(id: String, ageDays: Int) = VideoItem(id, id, "作者", emptyList(), 1, createdAt = now - ageDays * day)

    @Test fun videosOlderThanHalfAYearAreSplitOffInOrder() {
        val items = listOf(dated("a", 10), dated("old-1", 400), dated("b", 170), VideoItem("c", "c", "作者", emptyList(), 1), dated("old-2", 181))
        val (recent, aged) = engine(12).splitByAge(items, now)
        assertEquals("没有发布时间的算近期", listOf("a", "b", "c"), recent.map { it.id })
        assertEquals(listOf("old-1", "old-2"), aged.map { it.id })
    }

    @Test fun oldCandidatesOnlyAppearAsWovenClassics() {
        val engine = engine(12)
        val ranked = (0 until 40).map { dated("old-aged-$it", 365 + it) } + (0 until 48).map { dated("new-$it", it % 100) }
        val out = engine.assemble(ranked, emptySet(), classics(3))
        val groups = out.chunked(13)
        groups.forEachIndexed { index, group ->
            assertEquals("第 $index 组只能有一条老片：${group.map { it.id }}", 1, group.count(::isClassic))
        }
        assertEquals("近期视频一条不少、顺序不变", (0 until 48).map { "new-$it" }, out.filter { !isClassic(it) }.map { it.id })
        assertEquals("专门抓的老片排在候选里分流出来的老片前面", listOf("old-0", "old-1", "old-2", "old-aged-0"), out.filter(::isClassic).take(4).map { it.id })
    }

    @Test fun withClassicsOffOldCandidatesAreDropped() {
        val ranked = (0 until 30).map { dated("old-$it", 400) } + (0 until 30).map { dated("new-$it", 3) }
        val out = engine(0).assemble(ranked, emptySet(), emptyList())
        assertEquals(30, out.size)
        assertTrue(out.none(::isClassic))
    }

    @Test fun tooFewRecentVideosAreToppedUpWithOldOnes() {
        val ranked = (0 until 5).map { dated("new-$it", 3) } + (0 until 40).map { dated("old-$it", 400) }
        val out = engine(0).assemble(ranked, emptySet(), emptyList())
        assertEquals("补到最低条数，不给空页", RecommendationEngine.MIN_RECENT_FEED, out.size)
        assertEquals((0 until 5).map { "new-$it" }, out.take(5).map { it.id })
    }
}
