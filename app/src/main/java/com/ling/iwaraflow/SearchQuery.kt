package com.ling.iwaraflow

import java.text.Normalizer
import java.util.Locale

/** AND between input terms, OR only between spellings of the same term. */
data class SearchQuery(val groups: List<List<String>>) {
    // One complete term is enough for recall. Local validation checks every other term,
    // including mixed-language combinations, without a Cartesian product of API requests.
    val seeds: List<String> get() = groups.maxByOrNull { aliases ->
        aliases.maxOfOrNull { normalize(it).replace("_", "").length } ?: 0
    }.orEmpty()

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
        fun terms(raw: String): List<String> {
            val input = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            val matches = tokens.findAll(input).toList()
            val result = mutableListOf<String>()
            var index = 0
            while (index < matches.size) {
                val first = matches[index]
                var term = first.groupValues.drop(1).first { it.isNotEmpty() }.trim().removePrefix("#")
                var consumed = 1
                // Known multiword names are a single concept, not separately translated words.
                if (first.groupValues[3].isNotEmpty()) for (end in index + 1 until minOf(matches.size, index + 6)) {
                    val next = matches[end]
                    val gap = input.substring(matches[end - 1].range.last + 1, next.range.first)
                    if (next.groupValues[3].isEmpty() || gap.any { !it.isWhitespace() }) break
                    val phrase = input.substring(first.range.first, next.range.last + 1).removePrefix("#")
                    if (knownAliases(phrase) != null) { term = phrase; consumed = end - index + 1 }
                }
                if (term.isNotBlank()) result += term
                index += consumed
            }
            return result.distinctBy(::normalize)
        }

        fun literal(raw: String) = SearchQuery(terms(raw).map { listOf(it) })
        fun local(raw: String, aiPrecision: Boolean = false) = SearchQuery(terms(raw).map { term ->
            knownAliases(term) ?: QueryTranslator.cached(term, aiPrecision, raw) ?: listOf(term)
        })

        // Domain terms must never be sent to a general translator (e.g. 扶她 → Support Her).
        // First spelling is the canonical Iwara tag; all aliases are equivalent, not broader concepts.
        private val aliases: List<List<String>> = SearchQuery::class.java.getResourceAsStream("/search_aliases.tsv")
            ?.bufferedReader()?.useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t').filter(String::isNotBlank) }.toList() }.orEmpty()
        private val aliasIndex = aliases.flatMap { group -> group.map { tagKey(it) to group } }.toMap()

        fun knownAliases(term: String): List<String>? = aliasIndex[tagKey(term)]
            ?.let { (listOf(term) + it).distinctBy(::normalize) }

        fun canonicalTag(raw: String): String = aliasIndex[tagKey(raw)]?.first() ?: tagKey(raw)
        private val patterns = object : LinkedHashMap<String, Regex>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?) = size > 256
        }

        internal fun conceptsIn(text: String): Set<String> = aliases.filter { group ->
            group.any { containsTerm(text, it) }
        }.map { it.first() }.toSet()

        internal fun withoutKnownConcepts(text: String): String {
            var remaining = normalize(text).replace('_', ' ')
            aliases.flatten().sortedByDescending(String::length).forEach { alias ->
                remaining = termPattern(normalize(alias).replace('_', ' ')).replace(remaining, " ")
            }
            return remaining
        }

        private fun containsTerm(text: String, term: String): Boolean {
            val needle = normalize(term).replace('_', ' ')
            if (needle.isBlank()) return false
            val haystack = normalize(text).replace('_', ' ')
            return termPattern(needle).containsMatchIn(haystack)
        }

        private fun termPattern(needle: String): Regex = synchronized(patterns) { patterns.getOrPut(needle) {
                val left = if (needle.first().isAsciiWord()) "(?<![\\p{script=Latin}0-9])" else ""
                val right = if (needle.last().isAsciiWord()) "(?![\\p{script=Latin}0-9])" else ""
                val phrase = needle.split(Regex("\\s+")).joinToString("\\s+", transform = Regex::escape)
                Regex(left + phrase + right)
            } }

        private fun Char.isAsciiWord() = this in 'a'..'z' || this in '0'..'9'
    }
}
