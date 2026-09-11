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
}
