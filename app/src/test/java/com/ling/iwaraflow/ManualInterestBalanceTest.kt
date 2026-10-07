package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ManualInterestBalanceTest {
    private val taste = PreferenceProfile(emptyMap(), emptyMap(), manualTagPreferences = mapOf("dance" to 1))
    private fun video(id: String, tag: String) = VideoItem(id, id, id, listOf(tag, "unique-$id"), 100,
        authorId = "author-$id", views = 2000, createdAt = System.currentTimeMillis())
    private fun liked(count: Int) = (0 until count).map { video("liked-$it", "dance") }
    private fun other(count: Int) = (0 until count).map { video("other-$it", "other") }

    @Test fun positiveManualBoostIsBoundedEvenWithManyMatchingTagsAndSaturatedLearning() {
        val item = video("v", "dance").copy(tags = listOf("dance", "robot", "mmd", "miku"))
        val learned = PreferenceProfile(mapOf(item.author to 1000.0), emptyMap())
        val single = learned.copy(manualTagPreferences = mapOf("dance" to 1))
        val many = learned.copy(manualTagPreferences = item.tags.associateWith { 1 })
        val gain = single.rankingScore(item) - learned.rankingScore(item)
        assertTrue(gain > 0.0)
        assertTrue(gain <= PreferenceProfile.MANUAL_POSITIVE_BOOST)
        assertEquals(single.rankingScore(item), many.rankingScore(item), 1e-9)
        val negative = many.copy(manualTagPreferences = many.manualTagPreferences + ("dance" to -1))
        assertTrue(negative.rankingScore(item) < learned.rankingScore(item))
    }

    @Test fun otherTopicsReachTheFirstPageEvenWhenBuriedBeyondTheLookaheadAndResultLimit() {
        val ranker = RecommendationRanker(Random(4)).apply { profile = taste }
        // Diverse authors and extra tags defeat author-only or Jaccard-only diversity. All
        // 160 interest matches outrank the alternatives, which used to miss the 80-item cut.
        val candidates = (liked(160) + other(80)).associate { item ->
            item.id to RecommendationCandidate(item, sourceScore = if (taste.matchesManualInterest(item)) 10.0 else 1.0)
        }
        // Deliberately recreate the worst-case score-only order. Normal ranking now samples
        // with positive probabilities, but the final diversity guard must also handle this input.
        val ranked = ranker.rank(candidates).sortedByDescending { ranker.scoreOf(it, taste, System.currentTimeMillis()) }
        assertTrue(ranked.take(80).all(taste::matchesManualInterest))
        val result = ranker.assemble(ranked, emptySet(), emptySet(), emptyList())
        assertEquals(80, result.size)
        assertEquals(20, result.count { !taste.matchesManualInterest(it) })
        assertTrue(result.take(10).count { !taste.matchesManualInterest(it) } >= 2)
        result.chunked(4).forEach { block -> assertEquals(1, block.count { !taste.matchesManualInterest(it) }) }
        assertEquals(result.size, result.map { it.id }.distinct().size)
        assertTrue(result.filterNot(taste::matchesManualInterest).all { ranker.candidateFor(it.id)?.exploration == true })
    }

    @Test fun choosingAnAlreadyLearnedTagStillIncreasesItsChance() {
        val item = video("learned", "dance")
        val base = PreferenceProfile(emptyMap(), mapOf("dance" to 1000.0))
        val chosen = base.copy(manualTagPreferences = mapOf("dance" to 1))
        assertTrue(chosen.rankingScore(item) > base.rankingScore(item))
    }

    @Test fun followedAndClassicInsertionsCannotOverwriteTheTopicMix() {
        val ranker = RecommendationRanker(Random(9)).apply { profile = taste; classicsEvery = 3 }
        val preferred = liked(120)
        val classicPool = liked(40).map { it.copy(id = "classic-${it.id}",
            createdAt = System.currentTimeMillis() - 2 * RecommendationRanker.CLASSIC_MIN_AGE_MS) }
        val result = ranker.assemble(preferred + other(80), preferred.take(30).mapTo(HashSet()) { it.id }, emptySet(), classicPool)
        assertEquals(80, result.size)
        var consecutive = 0
        result.forEach {
            consecutive = if (taste.matchesManualInterest(it)) consecutive + 1 else 0
            assertTrue("Too many manual-interest matches after insertion", consecutive <= 3)
        }
    }

    @Test fun liveRerankingRetainsOtherTopicsUsingTheNewProfile() {
        val ranker = RecommendationRanker(Random(5)) // The stored profile can still be the old one.
        val input = liked(60) + other(20)
        repeat(5) {
            val result = ranker.rerank(input, taste, System.currentTimeMillis())
            assertEquals(input.map { it.id }.toSet(), result.map { it.id }.toSet())
            result.chunked(4).forEach { block -> assertTrue(block.any { !taste.matchesManualInterest(it) }) }
        }
    }

    @Test fun noInterestsOrAlreadyMixedFeedKeepsTheOriginalOrder() {
        val ranker = RecommendationRanker()
        val mixed = liked(12).zip(other(12)).flatMap { listOf(it.first, it.second) }
        assertEquals(mixed, ranker.balanceManualInterests(mixed, taste))
        val clustered = liked(30) + other(10)
        assertEquals(clustered, ranker.balanceManualInterests(clustered, PreferenceProfile(emptyMap(), emptyMap())))
    }

    @Test fun scarceAlternativesDoNotDropVideosOrForceDislikedAndMutedTopics() {
        val ranker = RecommendationRanker()
        val profile = taste.copy(manualTagPreferences = mapOf("dance" to 1, "avoid" to -1), mutedTags = setOf("blocked"))
        val avoided = video("avoid", "avoid")
        val blocked = video("blocked", "blocked")
        val input = liked(40) + listOf(avoided, blocked) + other(2)
        val result = ranker.balanceManualInterests(input, profile)
        assertEquals(input.size, result.size)
        assertEquals(input.map { it.id }.toSet(), result.map { it.id }.toSet())
        assertTrue(result[3].id.startsWith("other-"))
        assertTrue(result[7].id.startsWith("other-"))
        assertEquals(listOf(avoided, blocked), result.takeLast(2))
        val allLiked = liked(50)
        assertEquals(allLiked, ranker.balanceManualInterests(allLiked, profile))
    }

    @Test fun canonicalAliasesShareOneManualBoostAndAreRecognizedInTheMix() {
        val profile = taste.copy(manualTagPreferences = mapOf("futanari" to 1))
        val item = video("alias", "FUTA").copy(tags = listOf("FUTA", "futanari", "扶她"))
        assertTrue(profile.matchesManualInterest(item))
        assertEquals(PreferenceProfile.MANUAL_POSITIVE_BOOST, profile.manualTagScore(item), 1e-9)
    }
}
