package com.ling.iwaraflow

/** Distinguishes broad, repeatedly exposed tags from more selective learned interests.
 * One video contributes once, even when it has watch/like/save/replay events. Old or
 * sparse evidence has little effect; stored long-term preferences are never erased. */
internal class TagSpecificity {
    private data class Exposure(val tags: MutableSet<String>, var weight: Double)
    private val exposures = HashMap<String, Exposure>()

    fun observe(id: String, tags: List<String>, weight: Double) {
        if (id.isBlank() || id.startsWith("search:") || !weight.isFinite() || weight <= 0.0) return
        val canonical = tags.map(SearchQuery::canonicalTag).filter { it.isNotBlank() }.toSet()
        val exposure = exposures.getOrPut(id) { Exposure(mutableSetOf(), 0.0) }
        exposure.tags += canonical
        exposure.weight = maxOf(exposure.weight, weight.coerceAtMost(1.0))
    }

    fun multipliers(): Map<String, Double> {
        val total = exposures.values.sumOf { it.weight }
        if (total <= 0.0) return emptyMap()
        val counts = HashMap<String, Double>()
        exposures.values.forEach { exposure ->
            exposure.tags.forEach { counts[it] = (counts[it] ?: 0.0) + exposure.weight }
        }
        val confidence = (total / 20.0).coerceAtMost(1.0)
        // Keep at least 35% of a broad tag's positive signal. This only calibrates
        // learned positive scores; negative feedback, explicit choices and mutes keep
        // their own meaning and are handled by PreferenceProfile/HistoryStore.
        return counts.mapValues { (_, count) -> 1.0 - 0.65 * confidence * count / total }
    }
}
