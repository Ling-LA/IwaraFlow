package com.ling.iwaraflow

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Explicit user-controlled portable backup. No credentials, history or upload tickets. */
object LocalDataBackup {
    private val tables = listOf("favorites", "manual_tag_preferences", "preference_entities", "muted_entities")
    fun export(db: SQLiteDatabase, interests: SQLiteDatabase = db, multipliers: Map<String, Double> = emptyMap()): String {
        val root = JSONObject().put("format", "IwaraFlow-local").put("version", 1)
        root.put("system_multipliers", JSONObject(multipliers))
        tables.forEach { table ->
            val rows = JSONArray()
            (if (table == "favorites") db else interests).query(table, null, null, null, null, null, null).use { c ->
                require(c.count <= 20_000) { "备份条目过多" }
                while (c.moveToNext()) {
                    val row = JSONObject()
                    c.columnNames.forEachIndexed { index, column ->
                        row.put(column, when (c.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
                            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            else -> c.getString(index)
                        })
                    }
                    if (table == "preference_entities") row.put("score", row.getDouble("score").coerceIn(-6.0, 8.0))
                    validate(table, row)
                    rows.put(row)
                }
            }
            root.put(table, rows)
        }
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 5_000_000) { "备份超过 5 MB" } }
    }
    fun restore(db: SQLiteDatabase, text: String, interests: SQLiteDatabase = db, onMultipliers: (Map<String, Double>) -> Unit = {}): Int {
        require(text.length <= 5_000_000) { "备份文件过大" }
        val root = JSONObject(text)
        require(root.optString("format") == "IwaraFlow-local" && root.optInt("version") == 1) { "不是支持的 IwaraFlow 备份" }
        val multipliers = linkedMapOf<String, Double>()
        root.optJSONObject("system_multipliers")?.let { values ->
            require(values.length() <= 20000)
            values.keys().forEach { key ->
                val value = values.getDouble(key)
                require(key.isNotBlank() && key.length <= 100 && value.isFinite() && value in 0.0..1.5)
                multipliers[SearchQuery.canonicalTag(key)] = value
            }
        }
        var count = 0
        db.beginTransaction()
        var interestsStarted = false
        try {
            if (interests !== db) { interests.beginTransaction(); interestsStarted = true }
            tables.forEach { table ->
                val target = if (table == "favorites") db else interests
                val columns = target.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toSet() }
                val rows = root.optJSONArray(table) ?: JSONArray()
                require(rows.length() <= 20_000) { "备份条目过多" }
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    validate(table, row)
                    val values = ContentValues()
                    row.keys().forEach { key ->
                        require(key in columns) { "备份字段不受支持" }
                        when (val value = row.get(key)) {
                            JSONObject.NULL -> values.putNull(key)
                            is Int -> values.put(key, value)
                            is Long -> values.put(key, value)
                            is Double -> { require(value.isFinite()); values.put(key, value) }
                            is String -> { require(value.length <= 50_000); values.put(key, value) }
                            else -> error("备份字段格式异常")
                        }
                    }
                    require(values.size() > 0)
                    if (target.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) count++
                }
            }
            db.setTransactionSuccessful()
            if (interests !== db) interests.setTransactionSuccessful()
        } finally {
            try { if (interestsStarted) interests.endTransaction() } finally { db.endTransaction() }
        }
        onMultipliers(multipliers)
        return count
    }
    private fun validate(table: String, row: JSONObject) {
        fun text(key: String, max: Int = 1000, blank: Boolean = false) {
            val value = row.get(key)
            require(value is String && value.length <= max && (blank || value.isNotBlank())) { "备份中的 $key 格式异常" }
        }
        fun time(key: String) { require(row.get(key) is Number && row.getLong(key) >= 0 && row.getLong(key) <= System.currentTimeMillis() + 86_400_000) }
        when (table) {
            "favorites" -> { text("video_id"); text("title", 50000, true); text("author", blank = true); text("tags", 50000, true); time("created_at") }
            "manual_tag_preferences" -> { text("tag", 100); require(row.get("preference") is Number && row.getDouble("preference") in setOf(-1.0, 1.0)) }
            "preference_entities" -> { text("entity_key"); require(row.getString("type") in setOf("author", "author_id", "tag")); val score = row.getDouble("score"); require(score.isFinite() && score in -6.0..8.0); time("updated_at") }
            "muted_entities" -> { text("entity_key"); text("alias", blank = true); require(row.getString("type") in setOf("author", "author_id", "tag")); time("created_at") }
        }
    }
}
