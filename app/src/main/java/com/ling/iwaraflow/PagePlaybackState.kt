package com.ling.iwaraflow

import android.os.Bundle

/** Shared process-recreation contract, excluding transient URLs and private browsing. */
internal object PagePlaybackState {
    fun save(out: Bundle, prefs: AppPrefs, items: List<VideoItem>, index: Int, inFeed: Boolean, page: Int = 0, base: Int = 0) {
        if (prefs.privateBrowsing || items.isEmpty()) return
        out.putString("page_owner", prefs.accountId)
        out.putBoolean("page_in_feed", inFeed)
        val encoded = org.json.JSONObject(FeedSessionCodec.encode(FeedSessionStore.Session(items, index.coerceIn(items.indices), page, true)))
        encoded.put("start", encoded.getInt("start") + base).put("end", encoded.getInt("end") + base)
        out.putString("page_items", encoded.toString())
    }
    fun read(state: Bundle?, prefs: AppPrefs): FeedSessionStore.Session? {
        if (state == null || prefs.privateBrowsing || state.getString("page_owner") != prefs.accountId) return null
        return state.getString("page_items")?.let(FeedSessionCodec::decode)
    }
    fun start(state: Bundle): Int = runCatching { org.json.JSONObject(state.getString("page_items")!!).optInt("start") }.getOrDefault(0)
    fun end(state: Bundle): Int = runCatching { org.json.JSONObject(state.getString("page_items")!!).optInt("end") }.getOrDefault(0)
}
