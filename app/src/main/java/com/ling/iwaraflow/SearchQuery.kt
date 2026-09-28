package com.ling.iwaraflow

import java.text.Normalizer
import java.util.Locale

/** AND between input terms, OR only between spellings of the same term. */
data class SearchQuery(val groups: List<List<String>>) {
    // One complete term is enough for recall. Local validation checks every other term,
    // including mixed-language combinations, without a Cartesian product of API requests.
    val seeds: List<String> get() = groups.firstOrNull().orEmpty()

    fun matchesTitle(title: String): Boolean = matches { containsTerm(title, it) }
    fun matchesAuthor(author: IwaraAuthor): Boolean = matches {
        containsTerm(author.name, it) || containsTerm(author.username, it)
    }
    fun matchesTags(tags: List<String>): Boolean {
        val keys = tags.map(::tagKey).toSet()
        return matches { tagKey(it) in keys }
    }

    private fun matches(predicate: (String) -> Boolean): Boolean =
        groups.isNotEmpty() && groups.all { aliases -> aliases.any(predicate) }

    companion object {
        fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim()

        fun tagKey(raw: String): String = normalize(raw).removePrefix("#")
            .replace(Regex("\\s+"), "_")

        private val tokens = Regex("\"([^\"]+)\"|“([^”]+)”|([^\\s,，、;；]+)")
        fun terms(raw: String): List<String> = tokens.findAll(Normalizer.normalize(raw, Normalizer.Form.NFKC))
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() }.trim().removePrefix("#") }
            .filter { it.isNotBlank() }.distinctBy(::normalize).toList()

        fun literal(raw: String) = SearchQuery(terms(raw).map { listOf(it) })
        fun local(raw: String) = SearchQuery(terms(raw).map { term ->
            knownAliases(term) ?: QueryTranslator.cached(term) ?: listOf(term)
        })

        // Domain terms must never be sent to a general translator (e.g. 扶她 → Support Her).
        // First spelling is the canonical Iwara tag; all aliases are equivalent, not broader concepts.
        private val aliases = listOf(
            listOf("futanari", "futa", "扶她", "扶他", "ふたなり", "フタナリ", "フタ", "후타나리", "후타"),
            listOf("hatsune_miku", "hatsune miku", "初音未来", "初音未來", "初音ミク", "하츠네 미쿠"),
            listOf("genshin_impact", "genshin impact", "原神", "원신"),
            listOf("honkai_star_rail", "honkai star rail", "崩坏星穹铁道", "崩壞星穹鐵道", "崩壊スターレイル", "붕괴 스타레일"),
            listOf("clara", "克拉拉", "クラーラ", "클라라")
        )
        private val aliasIndex = aliases.flatMap { group -> group.map { tagKey(it) to group } }.toMap()

        fun knownAliases(term: String): List<String>? = aliasIndex[tagKey(term)]
            ?.let { (listOf(term) + it).distinctBy(::normalize) }

        fun canonicalTag(raw: String): String = aliasIndex[tagKey(raw)]?.first() ?: tagKey(raw)

        private fun containsTerm(text: String, term: String): Boolean {
            val needle = normalize(term).replace('_', ' ')
            if (needle.isBlank()) return false
            val haystack = normalize(text).replace('_', ' ')
            val left = if (needle.first().isAsciiWord()) "(?<![a-z0-9])" else ""
            val right = if (needle.last().isAsciiWord()) "(?![a-z0-9])" else ""
            val phrase = needle.split(Regex("\\s+")).joinToString("\\s+", transform = Regex::escape)
            return Regex(left + phrase + right).containsMatchIn(haystack)
        }

        private fun Char.isAsciiWord() = this in 'a'..'z' || this in '0'..'9'
    }
}
