package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * 内容多样性和标签聚合：打散作者只挡住了“连着五条同一个人”，挡不住
 * “二十条不同作者、全是同一种内容”；标签直接相加也让标签多的视频白占便宜。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationDiversityTest {
    private fun engine() =
        RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java), random = Random(3))

    private fun item(id: String, author: String, tags: List<String>) =
        VideoItem(id, id, author, tags, 1, authorId = "id-$author")

    @Test fun tagOverlapCountsAsSimilarAndSoDoesTheSameAuthor() {
        val e = engine()
        val a = item("a", "Alice", listOf("miku", "dance"))
        val b = item("b", "Bob", listOf("miku", "dance"))
        val c = item("c", "Bob", listOf("other"))
        val d = item("d", "Alice", listOf("miku"))
        assertEquals("标签完全一样就是最像", 1.0, e.similarity(a, b), 1e-9)
        assertEquals("同一个作者直接算最像", 1.0, e.similarity(a, d), 1e-9)
        assertEquals("没有共同标签、不同作者", 0.0, e.similarity(b, c), 1e-9)
        assertEquals("一半重合", 1.0 / 3, e.similarity(a, item("e", "Eve", listOf("miku", "x"))), 1e-9)
    }

    @Test fun oneTopicNoLongerHogsTheWholeScreen() {
        val e = engine()
        // 前十条全是同一种内容（不同作者），后面才是别的。
        val same = (0 until 10).map { item("same-$it", "A$it", listOf("miku", "dance")) }
        val other = (0 until 10).map { item("other-$it", "B$it", listOf("topic-$it")) }
        val out = e.diversify(same + other)

        assertEquals(20, out.size)
        assertEquals("一条都不能丢", (same + other).map { it.id }.toSet(), out.map { it.id }.toSet())
        val head = out.take(6).count { it.id.startsWith("same-") }
        assertTrue("前六条里同一种内容不该超过一半：${out.take(6).map { it.id }}", head <= 3)
    }

    @Test fun aFeedWithNothingInCommonKeepsItsOrder() {
        val e = engine()
        val items = (0 until 12).map { item("v$it", "A$it", listOf("tag-$it")) }
        assertEquals(items.map { it.id }, e.diversify(items).map { it.id })
    }

    /** 标签多的视频不该只因为标签多就赢。 */
    @Test fun onlyTheStrongestTagsCount() {
        val taste = PreferenceProfile(
            authorWeights = emptyMap(),
            tagWeights = mapOf("a" to 1.0, "b" to 1.0, "c" to 1.0, "d" to 1.0, "e" to 1.0, "f" to 1.0, "g" to 1.0)
        )
        val few = VideoItem("few", "t", "X", listOf("a", "b", "c"), 0)
        val many = VideoItem("many", "t", "Y", listOf("a", "b", "c", "d", "e", "f", "g"), 0)
        assertEquals(3.0, taste.score(few), 1e-9)
        assertEquals("最多只算 ${PreferenceProfile.TOP_TAGS} 个标签", 4.0, taste.score(many), 1e-9)
    }

    /** 强负反馈的标签必须算得进来，不能因为“只取最强的几个”被正标签挤掉。 */
    @Test fun strongNegativeTagsAreStillCounted() {
        val taste = PreferenceProfile(
            authorWeights = emptyMap(),
            tagWeights = mapOf("good" to 1.0, "bad" to -5.0, "x" to 0.2, "y" to 0.2, "z" to 0.2)
        )
        val item = VideoItem("v", "t", "X", listOf("good", "x", "y", "z", "bad"), 0)
        assertTrue("被讨厌的标签要压过几个弱正标签：${taste.score(item)}", taste.score(item) < 0)
    }
}
