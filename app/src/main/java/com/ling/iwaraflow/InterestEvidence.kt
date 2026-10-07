package com.ling.iwaraflow

class InterestEvidence {
    private val videos = HashMap<String, MutableSet<String>>()
    private val authors = HashMap<String, MutableSet<String>>()
    fun observe(id: String, author: String, tags: List<String>) {
        if (id.isBlank() || id.startsWith("search:")) return
        tags.map(SearchQuery::canonicalTag).distinct().forEach { tag ->
            videos.getOrPut(tag) { HashSet() }.add(id)
            if (author.isNotBlank()) authors.getOrPut(tag) { HashSet() }.add(author)
        }
    }
    fun confidence(tag: String): Double {
        val key = SearchQuery.canonicalTag(tag)
        val n = videos[key]?.size ?: 0
        val a = authors[key]?.size ?: 0
        return (1.0 - kotlin.math.exp(-(n + a * 1.5) / 8.0)).coerceIn(0.0, 1.0)
    }
    fun confidenceMap() = videos.keys.associateWith(::confidence)
}
