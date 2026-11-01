package com.ling.iwaraflow

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only explicitly resolved video sources share data across rotating authorization parameters. */
internal object MediaCacheIdentity {
    private val keys = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }
    private val cacheOnly = object : LinkedHashMap<String, Boolean>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 512
    }
    @Synchronized fun register(videoId: String, sources: List<VideoSource>) {
        if (videoId.isBlank()) return
        sources.forEach { source ->
            val parsed = source.url.toHttpUrlOrNull() ?: return@forEach
            val stable = parsed.newBuilder().apply {
                // Preserve content/quality/version parameters; strip only expiring authorization.
                listOf("token", "expires", "signature", "Signature", "Policy", "Key-Pair-Id").forEach(::removeAllQueryParameters)
            }.build()
            val identity = videoId + "|" + source.name + "|" + source.score + "|" + stable
            keys[source.url] = "video:" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    @Synchronized fun key(url: String): String = keys[url] ?: url

    /** Restored expired links may identify cached bytes, but must never authorize a network read. */
    @Synchronized fun requireCachedBytes(url: String) { cacheOnly[url] = true }
    @Synchronized fun isCacheOnly(url: String): Boolean = cacheOnly[url] == true

    /** Only a newly resolved server response can make the same URL eligible for networking again. */
    @Synchronized fun registerFresh(videoId: String, sources: List<VideoSource>) {
        register(videoId, sources)
        sources.forEach { cacheOnly.remove(it.url) }
    }
}
