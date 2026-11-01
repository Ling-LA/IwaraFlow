package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RecommendationIntentTest {
    private fun video(id: String = "v", tags: List<String> = listOf("favorite")) =
        VideoItem(id, id, "author", tags, 100, authorId = "author-id")

    @Test fun videoDislikeSurvivesSaturatedAuthorAndTagAffinity() {
        val taste = PreferenceProfile(emptyMap(), mapOf("favorite" to 8.0),
            authorIdWeights = mapOf("author-id" to 8.0), videoWeights = mapOf("rejected" to -6.0))
        assertEquals(6.0, taste.rankingScore(video("neutral")) - taste.rankingScore(video("rejected")), 0.0)
        assertTrue(taste.rankingScore(video("neutral")) > 0.0)
    }

    @Test fun severalPositiveTagsCannotHideAStrongNegativeTagOrAuthor() {
        val tags = (0..5).map { "good$it" }
        val taste = PreferenceProfile(emptyMap(), tags.associateWith { 8.0 } + ("avoid" to -6.0),
            authorIdWeights = mapOf("author-id" to 8.0))
        assertEquals(6.0, taste.rankingScore(video(tags = tags)) - taste.rankingScore(video(tags = tags + "avoid")), 0.0)
        val authorDislike = taste.copy(authorIdWeights = mapOf("author-id" to -6.0))
        assertEquals(2.0, authorDislike.rankingScore(video(tags = tags)), 0.0)
        assertEquals(PreferenceProfile.MANUAL_POSITIVE_BOOST,
            authorDislike.copy(manualTagPreferences = mapOf("good0" to 1)).rankingScore(video(tags = tags)) -
                authorDislike.rankingScore(video(tags = tags)), 0.0)
    }

    @Test fun authorRecallSamplesAcrossLongTermInterestsWithPreferenceAndMutesPreserved() {
        val taste = PreferenceProfile(emptyMap(), emptyMap(),
            authorIdWeights = mapOf("strong" to 8.0, "also-strong" to 8.0, "weak" to 2.5, "muted" to 8.0, "negative" to -1.0),
            mutedAuthorIds = setOf("muted"))
        val random = Random(52)
        val samples = (0 until 2000).map { taste.topAuthorIds(1, 2.5, random).single() }.groupingBy { it }.eachCount()
        assertEquals(setOf("strong", "also-strong", "weak"), samples.keys)
        assertTrue(samples.getValue("strong") > samples.getValue("weak") * 1.6)
        assertTrue(samples.getValue("also-strong") > samples.getValue("weak") * 1.6)
        assertEquals(3, taste.topAuthorIds(10, 2.5, Random(1)).distinct().size)
    }
}
