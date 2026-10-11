package com.ling.iwaraflow

import android.content.Context
import org.json.JSONArray

internal class ManualSearchTerms(context: Context) {
    private val prefs = context.getSharedPreferences("manual_search_terms", Context.MODE_PRIVATE)
    fun get(term: String): List<String> = runCatching {
        val rows = JSONArray(prefs.getString(SearchQuery.normalize(term), "[]"))
        (0 until minOf(rows.length(), 10)).map(rows::getString)
    }.getOrDefault(emptyList())
    fun set(term: String, words: List<String>) {
        val editor = prefs.edit()
        if (prefs.all.size >= 100 && !prefs.contains(SearchQuery.normalize(term))) prefs.all.keys.firstOrNull()?.let { editor.remove(it) }
        val clean = words.map(String::trim).filter { it.isNotEmpty() && it.length <= 80 && !it.contains('\n') }
            .distinctBy(SearchQuery::normalize).take(10)
        editor.putString(SearchQuery.normalize(term), JSONArray(clean).toString()).apply()
    }
    fun merge(query: SearchQuery): SearchQuery = SearchQuery(query.groups.map { group ->
        (group + get(group.first())).distinctBy(SearchQuery::normalize)
    })
}
