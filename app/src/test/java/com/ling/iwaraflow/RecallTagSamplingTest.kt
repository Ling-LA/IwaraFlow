package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RecallTagSamplingTest {
    @Test fun largeInterestProfilesCanBeSampledRepeatedlyWithoutSortingFailures() {
        // Startup recall sees hundreds of tied tags after the learned weights hit their cap.
        // A random comparator used to throw TimSort's "violates its general contract" here.
        val tags = (0 until 512).associate { "topic_$it" to 8.0 }
        val profile = PreferenceProfile(emptyMap(), tags)
        repeat(100) { seed ->
            val all = profile.topTags(tags.size, 0.5, Random(seed.toLong()))
            assertEquals(tags.keys, all.toSet())
            assertEquals(tags.size, all.size)
            val selected = profile.topTags(4, 0.5, Random(seed.toLong()))
            assertEquals(all.take(4), selected)
        }
    }

    @Test fun samplingKeepsThresholdsManualChoicesAndBlockedTagsEffective() {
        val profile = PreferenceProfile(emptyMap(), mapOf(
            "learned" to 4.0, "weak" to 0.1, "blocked" to 8.0, "disliked" to 8.0
        ), mutedTags = setOf("blocked"), manualTagPreferences = mapOf(
            "manual" to 1, "blocked" to 1, "disliked" to -1
        ))
        repeat(50) { seed ->
            assertEquals(setOf("learned", "manual"), profile.topTags(10, 0.5, Random(seed.toLong())).toSet())
        }
        assertTrue(PreferenceProfile(emptyMap(), emptyMap()).topTags(4, 0.5, Random(0)).isEmpty())
        assertTrue(profile.topTags(0, 0.5, Random(0)).isEmpty())
    }

    @Test fun weightedSamplingBoostsInterestsWithoutMonopolizingRecall() {
        val profile = PreferenceProfile(emptyMap(), mapOf("favored" to 8.0, "other" to 0.5),
            manualTagPreferences = mapOf("favored" to 1))
        val random = Random(42)
        val favored = (0 until 2000).count { profile.topTags(1, 0.5, random).single() == "favored" }
        assertTrue("Interest should have more opportunities, while other topics remain reachable: $favored",
            favored in 1700..1850)
    }
}
