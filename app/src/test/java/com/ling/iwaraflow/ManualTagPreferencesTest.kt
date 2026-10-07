package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManualTagPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clean() { context.deleteDatabase("iwaraflow.db") }
    private fun video(tag: String) = VideoItem("v", "title", "author", listOf(tag), 0)

    @Test fun explicitPreferencesSurviveReopenAndDoNotDecay() {
        HistoryStore(context).use { it.setManualTagPreference(" #FUTA ", 1) }
        HistoryStore(context).use { store ->
            assertEquals(mapOf("futanari" to 1), store.manualTagPreferences())
            val future = store.preferenceProfileAt(System.currentTimeMillis() + 3650L * 86400000)
            assertTrue(future.score(video("futanari")) > 0)
            assertEquals(listOf("futanari"), future.topTags(4, 0.1))
            store.setManualTagPreference("扶她", -1)
            assertEquals(mapOf("futanari" to -1), store.manualTagPreferences())
            val negative = store.preferenceProfile()
            assertTrue(negative.score(video("futanari")) < 0)
            assertFalse(negative.isMuted(video("futanari")))
            assertTrue(negative.topTags(4, 0.1).isEmpty())
            // Search is independent of profile and its negative preferences.
            assertTrue(SearchQuery.local("扶她").matchesTags(video("futanari").tags))
            store.setManualTagPreference("futa", 0)
            assertTrue(store.manualTagPreferences().isEmpty())
        }
    }

    @Test fun manualNegativeIsAnAdditiveReductionAndPositiveGetsRecallPriority() {
        val profile = PreferenceProfile(emptyMap(), mapOf("dance" to 100.0, "robot" to 50.0),
            manualTagPreferences = mapOf("dance" to -1, "mmd" to 1))
        assertTrue(profile.score(video("dance")) < profile.copy(manualTagPreferences = emptyMap()).score(video("dance")))
        assertEquals(listOf("mmd", "robot"), profile.topTags(2, 0.1))
    }

    @Test fun manualChoiceStillChangesRankingWithSaturatedLearnedSignals() {
        val base = PreferenceProfile(mapOf("author" to 1000.0), emptyMap())
        val liked = base.copy(manualTagPreferences = mapOf("dance" to 1))
        val disliked = base.copy(manualTagPreferences = mapOf("dance" to -1))
        assertTrue(liked.rankingScore(video("dance")) > base.rankingScore(video("dance")))
        assertTrue(disliked.rankingScore(video("dance")) < base.rankingScore(video("dance")))
    }

    @Test fun upgradePreservesExistingDataAndCreatesManualPreferences() {
        HistoryStore(context).use { store ->
            store.markSeen("old-video")
            store.writableDatabase.execSQL("DROP TABLE manual_tag_preferences")
            store.writableDatabase.version = 10
        }
        HistoryStore(context).use { store ->
            store.setManualTagPreference("dance", 1)
            assertEquals(11, store.readableDatabase.version)
            assertTrue(store.loadStatuses(listOf("old-video")).seen.contains("old-video"))
        }
    }

    @Test fun aNewExplicitChoiceReplacesAnOldMuteForTheSameTag() {
        HistoryStore(context).use { store ->
            store.mute(HistoryStore.MUTE_TAG, "FUTA")
            store.setManualTagPreference("扶她", 1)
            assertTrue(store.mutedEntities().tags.isEmpty())
            assertFalse(store.preferenceProfile().isMuted(video("futa")))
            assertTrue(store.preferenceProfile().score(video("futa")) > 0)
        }
    }
}
