package com.ling.iwaraflow

import android.database.sqlite.SQLiteDatabase

/** Local library queries share deterministic ordering across pages and searches. */
object SavedLibrary {
    data class Page(val items: List<VideoItem>, val total: Int)
    fun page(db: SQLiteDatabase, favorites: Boolean, offset: Int, limit: Int = 48,
             query: String = "", ascending: Boolean = false): Page {
        require(offset >= 0 && limit in 1..1000)
        val table = if (favorites) "favorites" else "history"
        val time = if (favorites) "created_at" else "watched_at"
        val selection = if (query.isBlank()) null else "instr(lower(title || ' ' || author || ' ' || tags), lower(?)) > 0"
        val args = if (selection == null) null else arrayOf(query.trim())
        val total = db.query(table, arrayOf("COUNT(*)"), selection, args, null, null, null).use {
            it.moveToFirst(); it.getInt(0)
        }
        val direction = if (ascending) "ASC" else "DESC"
        val items = ArrayList<VideoItem>()
        db.query(table, arrayOf("video_id", "title", "author", "tags"), selection, args, null, null,
            "$time $direction, video_id $direction", "$offset,$limit").use { c ->
            while (c.moveToNext()) items += VideoItem(c.getString(0), c.getString(1), c.getString(2),
                c.getString(3).split('\u001F').filter { it.isNotBlank() }, 0, localFavorite = favorites)
        }
        return Page(items, total)
    }
}
