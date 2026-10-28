package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RecommendationPersonalizationTest {
    private val now = System.currentTimeMillis()
    private fun video(id: String, tags: List<String>, likes: Int = 300, views: Int = 5000) =
        VideoItem(id, id, "author-$id", tags, likes, views = views, createdAt = now)

    private fun firstPageShare(items: List<VideoItem>, profile: PreferenceProfile, matches: (VideoItem) -> Boolean): Double {
        var matched = 0
        repeat(200) { seed ->
            val ranker = RecommendationRanker(Random(seed.toLong())).apply { this.profile = profile }
            val candidates = items.associate { item -> item.id to RecommendationCandidate(item).apply { sourceScore = 5.0 } }
            matched += ranker.assemble(ranker.rank(candidates), emptySet(), emptySet(), emptyList()).take(20).count(matches)
        }
        return matched / 4000.0
    }

    @Test fun learnedInterestWinsMeaningfullyEvenWhenSourceAndHeatAreAlreadyHigh() {
        val items = (0 until 100).map { video("yes$it", listOf("favorite")) } +
            (0 until 100).map { video("other$it", listOf("other$it")) }
        val share = firstPageShare(items, PreferenceProfile(emptyMap(), mapOf("favorite" to 7.0))) { "favorite" in it.tags }
        assertTrue("First-page interest share must improve beyond random selection: $share", share > 0.65)
        assertTrue("Unfamiliar topics must still be discoverable: $share", share < 0.95)
    }

    @Test fun popularWorkWithinAnInterestOutranksLowLikeWorkWithoutExcludingIt() {
        val items = (0 until 100).map { video("popular$it", listOf("music"), 3000, 30000) } +
            (0 until 100).map { video("new$it", listOf("music"), 15, 150) }
        val share = firstPageShare(items, PreferenceProfile(emptyMap(), mapOf("music" to 6.0))) { it.likes > 1000 }
        assertTrue("High-like share must exceed a coin toss: $share", share > 0.65)
        assertTrue("New work keeps a chance: $share", share < 0.95)
    }

    @Test fun strongTasteCanBeatUnrelatedPopularityAndHardMutesAlwaysWin() {
        val ranker = RecommendationRanker()
        val taste = PreferenceProfile(emptyMap(), mapOf("favorite" to 8.0), mutedTags = setOf("blocked"))
        val favorite = video("favorite", listOf("favorite"), 300, 5000)
        val popular = video("popular", emptyList(), 5000, 60000)
        assertTrue(ranker.scoreOf(favorite, taste, now) > ranker.scoreOf(popular, taste, now))
        assertTrue(ranker.rerank(listOf(video("blocked", listOf("blocked", "favorite"), Int.MAX_VALUE)), taste, now).isEmpty())
        assertTrue(ranker.qualityScore(Int.MAX_VALUE, Int.MAX_VALUE) <= 14.0)
    }

    @Test fun aCommonScoreBaselineDoesNotFlattenTheRanking() {
        val items = (0 until 30).map { video("v$it", listOf("topic$it"), it * 100, it * 1000) }
        fun order(baseline: Double): List<String> {
            val ranker = RecommendationRanker(Random(18))
            items.forEach { item -> ranker.remember(item) { it.sourceScore = baseline } }
            return ranker.weightedOrder(items, PreferenceProfile(emptyMap(), emptyMap()), now).map { it.id }
        }
        assertEquals(order(0.0), order(100.0))
    }

    @Test fun manyBroadTagsCannotArtificiallyFillThePreferenceBudget() {
        val taste = PreferenceProfile(emptyMap(), (0..10).associate { "generic$it" to 2.8 } + ("specific" to 7.0))
        val broad = video("broad", (0..10).map { "generic$it" })
        assertTrue(taste.rankingScore(video("specific", listOf("specific"))) > taste.rankingScore(broad))
        assertEquals(taste.rankingScore(broad), taste.rankingScore(video("four", (0..3).map { "generic$it" })), 1e-9)
    }

    @Test fun strongerPositiveTagsDoNotDiscountNegativeFeedback() {
        val taste = PreferenceProfile(emptyMap(), mapOf("favorite" to 8.0, "disliked" to -6.0))
        assertEquals(2.0, taste.rankingScore(video("mixed", listOf("favorite", "disliked"))), 1e-9)
    }
}
