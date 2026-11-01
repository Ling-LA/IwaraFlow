package com.ling.iwaraflow

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A small, temporary calendar bonus. It never becomes a learned/manual preference. */
object SeasonalInterest {
    data class Occasion(val name: String, val tag: String, val aliases: Set<String>, val strength: Double)
    private data class Holiday(val name: String, val tag: String, val month: Int, val day: Int,
        val before: Int, val after: Int, val aliases: Set<String>)
    private val holidays = listOf(
        Holiday("万圣节", "halloween", 10, 31, 5, 1,
            setOf("halloween", "happy_halloween", "happyhalloween", "万圣节", "萬聖節", "ハロウィン")),
        Holiday("圣诞节", "christmas", 12, 25, 7, 2,
            setOf("christmas", "xmas", "merry_christmas", "圣诞节", "聖誕節", "クリスマス")),
        Holiday("新年", "new_year", 1, 1, 2, 2,
            setOf("new_year", "newyear", "happy_new_year", "新年", "正月")),
        Holiday("情人节", "valentine", 2, 14, 3, 1,
            setOf("valentine", "valentines_day", "valentine_day", "valentine's_day", "情人节", "情人節", "バレンタイン"))
    )

    fun active(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Occasion> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return holidays.mapNotNull { holiday ->
            // Include the adjacent year so New Year's window works in late December.
            val delta = (date.year - 1..date.year + 1).map { year ->
                ChronoUnit.DAYS.between(LocalDate.of(year, holiday.month, holiday.day), date).toInt()
            }.firstOrNull { it in -holiday.before..holiday.after } ?: return@mapNotNull null
            val distance = if (delta < 0) -delta.toDouble() / (holiday.before + 1)
                else delta.toDouble() / (holiday.after + 1)
            Occasion(holiday.name, holiday.tag, holiday.aliases, (1.0 - distance).coerceIn(0.0, 1.0))
        }
    }

    fun eligible(taste: PreferenceProfile, occasion: Occasion): Boolean = occasion.aliases.none { alias ->
        taste.mutedTags.any { SearchQuery.canonicalTag(it) == alias } ||
            (taste.manualTagPreferences[alias] ?: 0) < 0 || (taste.tagWeights[alias] ?: 0.0) < 0.0
    }

    fun boost(item: VideoItem, taste: PreferenceProfile, now: Long): Double {
        if (taste.isMuted(item) || taste.matchesManualDisinterest(item) || taste.rankingScore(item) < 0) return 0.0
        val tags = item.tags.map(SearchQuery::canonicalTag).toSet()
        return active(now).filter { eligible(taste, it) && it.aliases.any(tags::contains) }
            .maxOfOrNull { MAX_BOOST * it.strength } ?: 0.0
    }

    const val MAX_BOOST = 2.0
}
