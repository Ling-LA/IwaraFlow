package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ContentInterestTest {
    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
    private val empty = PreferenceProfile(emptyMap(), emptyMap())
    private fun video(title: String, body: String = "", tags: List<String> = emptyList()) =
        VideoItem("test", title, "creator", tags, 10, description = body)
    private fun evidence(count: Int, sameAuthor: Boolean = false, action: String = "like", age: Long = 0): Map<String, Double> {
        val learning = ContentInterest.Learning()
        repeat(count) { i -> learning.observe("v$i", if (sameAuthor) "author" else "author$i", action,
            if (action == "skip") -1.0 else 3.0, age, listOf("robot"), emptyList()) }
        return learning.weights(now)
    }

    @Test fun multilingualConceptIsCanonicalAndNotSplitIntoDuplicateWords() {
        for (title in listOf("Hatsune Miku", "初音未来", "初音ミク", "하츠네 미쿠")) {
            assertEquals(setOf("hatsune_miku"), ContentInterest.features(video(title)).title)
        }
    }
    @Test fun linksPromotionsAndGenericProductionCreditsDoNotBecomeInterests() {
        val f = ContentInterest.features(video("Robot dance", "A quiet forest\nPatreon subscribe robot\nhttps://example.com/promotion\nModel motion camera stage blender"))
        assertTrue("robot" in f.title)
        assertTrue("forest" in f.body)
        assertFalse("robot" in f.body)
        assertFalse(f.body.any { it in setOf("example", "promotion", "patreon", "model", "motion", "camera") })
    }
    @Test fun singleVideoAndSingleCreatorCannotEstablishTextAffinity() {
        assertTrue(evidence(1).isEmpty())
        assertTrue(evidence(5, sameAuthor = true).isEmpty())
        assertTrue(evidence(2, action = "watch").isEmpty())
        assertTrue(evidence(2).getValue("robot") > 0)
        assertTrue(evidence(3, action = "watch").getValue("robot") > 0)
    }
    @Test fun repeatedSameActionDoesNotIncreaseWeightAndUnknownAuthorsAreNotIndependent() {
        val learning = ContentInterest.Learning()
        repeat(20) { learning.observe("v1", "a1", "like", 3.0, 0, listOf("robot"), emptyList()) }
        learning.observe("v2", "a2", "like", 3.0, 0, listOf("robot"), emptyList())
        assertEquals(evidence(2), learning.weights(now))
        val unknown = ContentInterest.Learning()
        repeat(3) { unknown.observe("v$it", "", "like", 3.0, 0, listOf("robot"), emptyList()) }
        assertTrue(unknown.weights(now).isEmpty())
    }
    @Test fun commonTemplatesDecayAndNegativeFeedbackAreRespected() {
        assertTrue(evidence(6, action = "watch").isEmpty()) // repeated passive exposure may be a template
        assertTrue(evidence(6).getValue("robot") > 0) // explicit interest must survive more usage
        assertTrue(evidence(6, action = "skip").getValue("robot") < 0)
        assertTrue(evidence(3, action = "skip").getValue("robot") < 0)
        assertTrue(evidence(2, age = 30L * 86_400_000).getValue("robot") < evidence(2).getValue("robot"))
    }
    @Test fun descriptionIsWeakerAndTagsAreNotCountedTwice() {
        val taste = empty.copy(textWeights = mapOf("robot" to .6))
        assertEquals(.6, ContentInterest.score(video("robot"), taste), .0001)
        assertEquals(.15, ContentInterest.score(video("", "robot"), taste), .0001)
        assertEquals(.6, ContentInterest.score(video("robot", "robot"), taste), .0001)
        assertEquals(0.0, ContentInterest.score(video("机器人", tags = listOf("robot")), taste), .0001)
    }
    @Test fun keywordStuffingCannotDominateOrOverrideHardMute() {
        val title = "forest ocean mountain robot music dance animation"
        val taste = empty.copy(textWeights = ContentInterest.features(video(title)).title.associateWith { .6 })
        assertEquals(1.2, ContentInterest.score(video(title), taste), .0001)
        val ranker = RecommendationRanker().apply { profile = taste.copy(mutedAuthors = setOf("creator")) }
        assertTrue(ranker.hardMuteFilter(listOf(video(title))).isEmpty())
    }
    @Test fun expiredHolidayDoesNotSurviveThroughTitleLearning() {
        val learning = ContentInterest.Learning()
        repeat(2) { learning.observe("v$it", "a$it", "like", 3.0, 0, listOf("halloween", "robot"), emptyList()) }
        assertFalse("halloween" in learning.weights(now))
        assertTrue("robot" in learning.weights(now))
    }
    @Test fun searchAndVideoOnlyDislikeDoNotTeachTextPreferences() {
        val learning = ContentInterest.Learning()
        repeat(5) { learning.observe("v$it", "a$it", "search", 3.0, 0, listOf("robot"), emptyList())
            learning.observe("v$it", "a$it", "dislike_video", -3.0, 0, listOf("robot"), emptyList()) }
        assertTrue(learning.weights(now).isEmpty())
    }
}
