package com.ling.iwaraflow

import java.net.URI
import java.net.URLDecoder

/** Only public Iwara page URLs are accepted; share prose and optional video slugs are allowed. */
data class IwaraSharedLink(val kind: Kind, val key: String) {
    enum class Kind { VIDEO, AUTHOR }
    val url: String get() = "https://www.iwara.tv/${if (kind == Kind.VIDEO) "video" else "profile"}/${UriEncoder.encodePathSegment(key)}"

    companion object {
        const val EXTRA_URL = "com.ling.iwaraflow.SHARED_URL"
        private val urls = Regex("https?://[^\\s<>\"'，。！？；（）【】]+", RegexOption.IGNORE_CASE)

        fun parse(text: String): IwaraSharedLink? = urls.findAll(text.take(32_768)).firstNotNullOfOrNull {
            parseUrl(it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}'))
        }

        private fun parseUrl(value: String): IwaraSharedLink? = runCatching {
            val uri = URI(value)
            if (uri.host?.lowercase() !in setOf("iwara.tv", "www.iwara.tv") || uri.userInfo != null ||
                uri.port !in listOf(-1, 80, 443)) return null
            val segments = uri.rawPath.orEmpty().trim('/').split('/').toMutableList()
            if (segments.firstOrNull() in setOf("en", "ja", "zh", "zh-cn", "zh-tw", "ko")) segments.removeAt(0)
            if (segments.size < 2) return null
            val kind = when (segments[0]) {
                "video", "videos" -> Kind.VIDEO
                "profile", "users" -> Kind.AUTHOR
                else -> return null
            }
            val key = URLDecoder.decode(segments[1].replace("+", "%2B"), "UTF-8")
            if (key.isBlank() || key.length > 200 || key in setOf(".", "..") ||
                key.any { it.isWhitespace() || it.isISOControl() || it in "/\\?#" }) return null
            IwaraSharedLink(kind, key)
        }.getOrNull()
    }
}
