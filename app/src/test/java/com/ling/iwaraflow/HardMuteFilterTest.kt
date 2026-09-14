package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * 显式负反馈压过所有隐式正反馈：点过「不感兴趣：作者 / 标签」的内容在**打分之前**
 * 就被剔掉，站方数据再漂亮、画像里其它信号再正，也不进推荐流。
 *
 * 以前拉黑只是一个很负的权重，要和“看得久”“点过赞”一起加权算；
 * 一个又拉黑过、又恰好命中好几个喜欢的标签的作者照样能排上来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardMuteFilterTest {
    private val taste = PreferenceProfile(
        authorWeights = mapOf("muted" to 8.0),
        tagWeights = mapOf("loved" to 8.0),
        authorIdWeights = mapOf("id-Muted" to 8.0),
        mutedAuthors = setOf("muted"),
        mutedAuthorIds = setOf("id-Muted"),
        mutedTags = setOf("banned")
    )

    private fun ranker() = RecommendationRanker(Random(7)).apply { profile = taste }

    private fun item(id: String, author: String, tags: List<String>, likes: Int = 10, ageDays: Long = 1) =
        VideoItem(
            id, id, author, tags, likes, views = likes * 10,
            createdAt = System.currentTimeMillis() - ageDays * 86_400_000L,
            authorId = "id-$author"
        )

    /** 拉黑的作者哪怕数据最好、还命中了最喜欢的标签，也不该出现。 */
    @Test fun aMutedAuthorNeverSurvivesRankingHoweverGoodItLooks() {
        val star = item("star", "Muted", listOf("loved"), likes = 100_000)
        val plain = item("plain", "Alice", listOf("other"), likes = 1)
        val merged = linkedMapOf(
            star.id to RecommendationCandidate(star, sourceScore = 9.0),
            plain.id to RecommendationCandidate(plain)
        )
        assertEquals(listOf("plain"), ranker().rank(merged).map { it.id })
    }

    @Test fun aMutedTagIsFilteredToo() {
        val banned = item("banned", "Alice", listOf("banned", "loved"), likes = 50_000)
        val keep = item("keep", "Bob", listOf("loved"))
        val merged = linkedMapOf(
            banned.id to RecommendationCandidate(banned, sourceScore = 9.0),
            keep.id to RecommendationCandidate(keep)
        )
        assertEquals(listOf("keep"), ranker().rank(merged).map { it.id })
    }

    /** 实时重排是同一套口径，拉黑同样在打分之前生效。 */
    @Test fun rerankAppliesTheSameHardFilter() {
        val items = listOf(
            item("star", "Muted", listOf("loved"), likes = 100_000),
            item("banned", "Alice", listOf("banned")),
            item("keep", "Bob", listOf("loved"))
        )
        val out = ranker().rerank(items, taste, System.currentTimeMillis())
        assertEquals(listOf("keep"), out.map { it.id })
    }

    /** 老片穿插位也不是拉黑内容的后门。 */
    @Test fun classicsAreFilteredAsWell() {
        val out = ranker().rankClassics(
            listOf(
                item("old-muted", "Muted", listOf("loved"), likes = 100_000, ageDays = 800),
                item("old-banned", "Alice", listOf("banned"), likes = 100_000, ageDays = 800),
                item("old-keep", "Bob", listOf("loved"), ageDays = 800)
            )
        )
        assertEquals(listOf("old-keep"), out.map { it.id })
    }

    /** 没拉黑任何东西时，硬过滤一条都不动。 */
    @Test fun anEmptyMuteListChangesNothing() {
        val items = (0 until 5).map { item("v$it", "A$it", listOf("t$it")) }
        val open = RecommendationRanker(Random(7)).apply { profile = PreferenceProfile(emptyMap(), emptyMap()) }
        assertEquals(items.map { it.id }, open.hardMuteFilter(items).map { it.id })
    }
}
