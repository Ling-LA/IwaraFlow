package com.ling.iwaraflow

import org.json.JSONObject

data class IwaraRule(val id: String, val title: String, val body: String) {
    companion object {
        private fun localized(value: Any?): String = when (value) {
            is JSONObject -> listOf("zh", "en", "ja").firstNotNullOfOrNull { language ->
                value.optString(language).takeUnless { it.isBlank() || it == "null" }
            }.orEmpty()
            is String -> value.takeUnless { it == "null" }.orEmpty()
            else -> ""
        }
        fun parse(root: JSONObject): List<IwaraRule> {
            val results = root.optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val title = localized(item.opt("title"))
                val body = localized(item.opt("body"))
                if (title.isBlank() || body.isBlank()) null else IwaraRule(item.optString("id"), title, body)
            }
        }
        fun readableBody(value: String): String = value
            .replace(Regex("!\\[([^]]*)]\\((https?://[^)]+)\\)"), "$1：$2")
            .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1（$2）")
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace("**", "").replace("__", "")
    }
}
