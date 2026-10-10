package com.ling.iwaraflow

import org.json.JSONArray
import org.json.JSONObject

/** Small instance-state snapshot; signed CDN URLs and credentials are never retained. */
internal object FeedSessionCodec {
    fun encode(session: FeedSessionStore.Session): String {
        val start = (session.currentIndex - 2).coerceAtLeast(0)
        val items = session.items.drop(start).take(40)
        return JSONObject().put("index", session.currentIndex - start).put("page", session.currentPage)
            .put("paging", session.pagingEnabled).put("items", JSONArray(items.map { v ->
                JSONObject().put("id", v.id).put("title", v.title.take(300)).put("author", v.author.take(120))
                    .put("authorId", v.authorId).put("username", v.authorUsername).put("tags", JSONArray(v.tags.take(30)))
                    .put("likes", v.likes).put("views", v.views).put("created", v.createdAt).put("liked", v.liked).put("likeAccount", v.likeStateAccount)
                    .put("favorite", v.localFavorite).put("position", v.resumePositionMs).put("playWhenReady", v.resumePlayWhenReady).put("quality", v.selectedQuality)
            })).toString()
    }
    fun decode(text: String): FeedSessionStore.Session? = runCatching {
        require(text.length <= 150000)
        val root = JSONObject(text); val rows = root.getJSONArray("items"); require(rows.length() in 1..40)
        val items = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val tags = row.getJSONArray("tags")
            VideoItem(row.getString("id"), row.getString("title"), row.getString("author"),
                (0 until tags.length()).map(tags::getString), row.optInt("likes"), views = row.optInt("views"),
                createdAt = row.optLong("created"), liked = row.optBoolean("liked"), likeStateAccount = row.optString("likeAccount").takeIf { it.isNotBlank() }, localFavorite = row.optBoolean("favorite"),
                authorId = row.optString("authorId"), authorUsername = row.optString("username"),
                resumePositionMs = row.optLong("position").coerceAtLeast(0),
                resumePlayWhenReady = row.optBoolean("playWhenReady", true),
                selectedQuality = row.optString("quality").takeUnless { it.isBlank() || it == "null" })
        }
        FeedSessionStore.Session(items, root.optInt("index").coerceIn(items.indices), root.optInt("page").coerceAtLeast(0), root.optBoolean("paging", true))
    }.getOrNull()
}
