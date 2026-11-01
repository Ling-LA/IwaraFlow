package com.ling.iwaraflow

import android.database.sqlite.SQLiteDatabase

/** Local library queries share deterministic ordering across pages and searches. */
object SavedLibrary {
    data class Page(val items: List<VideoItem>, val total: Int)
    fun page(db: SQLiteDatabase, favorites: Boolean, offset: Int, limit: Int = 48,
             query: String = "", ascending: Boolean = false, likedIds: Set<String>? = null): Page {
        require(offset >= 0 && limit in 1..1000)
        val table = if (favorites) "favorites" else "history"
        val time = if (favorites) "created_at" else "watched_at"
        val selection = if (query.isBlank()) null else "instr(lower(title || ' ' || author || ' ' || tags), lower(?)) > 0"
        val args = if (selection == null) null else arrayOf(query.trim())
        val direction = if (ascending) "ASC" else "DESC"
        if (likedIds != null) {
            if (likedIds.isEmpty()) return Page(emptyList(), 0)
            // Scan the indexed local history on the library worker. An IN clause can exceed
            // Android SQLite's bind limit for long-lived accounts with thousands of likes.
            var total = 0
            val items = ArrayList<VideoItem>()
            db.query(table, arrayOf("video_id", "title", "author", "tags"), selection, args, null, null,
                "$time $direction, video_id $direction").use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (id !in likedIds) continue
                    if (total >= offset && items.size < limit) items += VideoItem(id, c.getString(1), c.getString(2),
                        c.getString(3).split('\u001F').filter { it.isNotBlank() }, 0, liked = true, localFavorite = favorites)
                    total++
                }
            }
            return Page(items, total)
        }
        val total = db.query(table, arrayOf("COUNT(*)"), selection, args, null, null, null).use {
            it.moveToFirst(); it.getInt(0)
        }
        val items = ArrayList<VideoItem>()
        db.query(table, arrayOf("video_id", "title", "author", "tags"), selection, args, null, null,
            "$time $direction, video_id $direction", "$offset,$limit").use { c ->
            while (c.moveToNext()) items += VideoItem(c.getString(0), c.getString(1), c.getString(2),
                c.getString(3).split('\u001F').filter { it.isNotBlank() }, 0, localFavorite = favorites)
        }
        return Page(items, total)
    }
}
