package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeasonalInterestTest {
    private val neutral = PreferenceProfile(emptyMap(), emptyMap())
    private fun date(value: String) = Instant.parse("${value}T12:00:00Z").toEpochMilli()
    private fun video(vararg tags: String) = VideoItem("v", "title", "author", tags.toList(), 100)

    @Test fun holidayBonusIsTemporaryBoundedAndDoesNotStackAliases() {
        val item = video("halloween", "happy_halloween", "万圣节")
        val peak = SeasonalInterest.boost(item, neutral, date("2026-10-31"))
        assertEquals(SeasonalInterest.MAX_BOOST, peak, 0.0)
        assertTrue(SeasonalInterest.boost(item, neutral, date("2026-10-27")) in 0.0..peak)
        assertEquals(0.0, SeasonalInterest.boost(item, neutral, date("2026-11-02")), 0.0)
        assertEquals(0.0, SeasonalInterest.boost(video("halloween_costume_shop"), neutral, date("2026-10-31")), 0.0)
        assertEquals(SeasonalInterest.MAX_BOOST, SeasonalInterest.boost(video("xmas"), neutral, date("2026-12-25")), 0.0)
    }

    @Test fun calendarUsesLocalDayAndHandlesNewYearAcrossYearBoundary() {
        val newYear = SeasonalInterest.active(date("2026-12-31"), ZoneId.of("Asia/Shanghai"))
        assertTrue(newYear.any { it.tag == "new_year" })
        val boundary = Instant.parse("2026-11-01T18:00:00Z").toEpochMilli()
        assertTrue(SeasonalInterest.active(boundary, ZoneId.of("UTC")).any { it.tag == "halloween" })
        assertTrue(SeasonalInterest.active(boundary, ZoneId.of("Asia/Shanghai")).none { it.tag == "halloween" })
    }

    @Test fun seasonalBoostRespectsNegativePreferencesAndHardMutes() {
        val time = date("2026-10-31")
        val profiles = listOf(neutral.copy(mutedTags = setOf("halloween")),
            neutral.copy(manualTagPreferences = mapOf("halloween" to -1)),
            neutral.copy(tagWeights = mapOf("halloween" to -2.0)),
            neutral.copy(mutedAuthors = setOf("author")))
        profiles.forEach { assertEquals(0.0, SeasonalInterest.boost(video("halloween"), it, time), 0.0) }
        val ranker = RecommendationRanker(Random(2)) { time }
        ranker.profile = profiles.first()
        assertTrue(ranker.rank(mapOf("v" to RecommendationCandidate(video("halloween")))).isEmpty())
    }

    @Test fun holidayRecallReplacesBroadSourceWithoutAddingRequestsOrOverridingMute() {
        fun requests(day: String, taste: PreferenceProfile = neutral): List<RecommendationEngine.PageRequest> {
            val engine = RecommendationEngine(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
                random = Random(20), nowMs = { date(day) })
            return try { engine.useProfile(taste); engine.requestsFor(0) } finally { engine.close() }
        }
        val regular = requests("2026-03-15")
        val holiday = requests("2026-10-31")
        assertEquals(regular.size, holiday.size)
        assertEquals(1, holiday.count { it.sort == RecommendationEngine.SEASONAL_PREFIX + "halloween" })
        assertTrue(requests("2026-10-31", neutral.copy(mutedTags = setOf("万圣节")))
            .none { it.sort.startsWith(RecommendationEngine.SEASONAL_PREFIX) })
        assertTrue(regular.none { it.sort.startsWith(RecommendationEngine.SEASONAL_PREFIX) })
    }

    @Test fun scoreIncludesSameHolidayBonusInInitialAndLiveRanking() {
        val time = date("2026-12-25")
        val ranker = RecommendationRanker(Random(3)) { time }
        val item = video("christmas")
        ranker.rank(mapOf(item.id to RecommendationCandidate(item)))
        assertEquals(SeasonalInterest.MAX_BOOST, ranker.scoreParts(item, neutral, time).seasonal, 0.0)
        assertEquals(0.0, ranker.scoreParts(item, neutral, date("2027-01-15")).seasonal, 0.0)
    }
}
