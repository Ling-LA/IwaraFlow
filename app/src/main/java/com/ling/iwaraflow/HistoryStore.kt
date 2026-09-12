package com.ling.iwaraflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一条下载记录：哪个视频、哪个清晰度、系统下载器给的编号。 */
data class DownloadRecord(val item: VideoItem, val quality: String, val downloadId: Long)

class HistoryStore(context: Context) : SQLiteOpenHelper(context, "iwaraflow.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history(
                video_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                tags TEXT NOT NULL,
                last_position INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0,
                watched_at INTEGER NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE favorites(
                video_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                tags TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE interactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL,
                action TEXT NOT NULL,
                author TEXT NOT NULL,
                tags TEXT NOT NULL,
                weight REAL NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE seen_videos(
                video_id TEXT PRIMARY KEY,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(DOWNLOADS_TABLE)
        db.execSQL("CREATE INDEX idx_history_time ON history(watched_at DESC)")
        db.execSQL("CREATE INDEX idx_interactions_time ON interactions(created_at DESC)")
        db.execSQL("CREATE INDEX idx_seen_last_time ON seen_videos(last_seen_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE history ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            } catch (_: Throwable) { }
        }
        if (oldVersion < 3) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS seen_videos(
                    video_id TEXT PRIMARY KEY,
                    first_seen_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_seen_last_time ON seen_videos(last_seen_at DESC)")
            db.execSQL(
                """INSERT OR IGNORE INTO seen_videos(video_id, first_seen_at, last_seen_at)
                   SELECT video_id, watched_at, watched_at FROM history""".trimIndent()
            )
        }
        if (oldVersion < 4) db.execSQL(DOWNLOADS_TABLE)
        if (oldVersion < 5) {
            // 4 → 5：下载记录带上作者 id / 用户名 / 简介，已下载的视频离线也能进作者主页、看简介。
            // 从 <4 直接升上来的表建出来就带这几列，ALTER 会报重复，所以逐条兜住。
            for (column in listOf("author_id TEXT NOT NULL DEFAULT ''", "author_username TEXT NOT NULL DEFAULT ''", "description TEXT NOT NULL DEFAULT ''")) {
                try { db.execSQL("ALTER TABLE downloads ADD COLUMN $column") } catch (_: Throwable) { }
            }
        }
    }

    /** 下载的时候记一笔，"已下载"那一页读的就是这张表。 */
    @Synchronized
    fun recordDownload(item: VideoItem, quality: String, downloadId: Long) {
        val values = ContentValues().apply {
            put("video_id", item.id)
            put("quality", quality)
            put("title", item.title)
            put("author", item.author)
            put("tags", item.tags.joinToString("\u001F"))
            put("download_id", downloadId)
            put("created_at", System.currentTimeMillis())
            put("author_id", item.authorId)
            put("author_username", item.authorUsername)
            put("description", item.description)
        }
        writableDatabase.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 给已有的下载记录补上作者 id / 用户名 / 简介。旧版本下载的、或从系统下载器补录的记录
     * 没有这些字段，播放时联网拉到一次详情就回写，之后视频变私密或者离线也还能用。
     * 传空串的字段不覆盖已有的值。
     */
    @Synchronized
    fun updateDownloadDetail(videoId: String, authorId: String, authorUsername: String, description: String) {
        val values = ContentValues()
        if (authorId.isNotBlank()) values.put("author_id", authorId)
        if (authorUsername.isNotBlank()) values.put("author_username", authorUsername)
        if (description.isNotBlank()) values.put("description", description)
        if (values.size() == 0) return
        writableDatabase.update("downloads", values, "video_id=?", arrayOf(videoId))
    }

    @Synchronized
    fun downloadRecords(limit: Int = 300): List<DownloadRecord> {
        val out = ArrayList<DownloadRecord>()
        readableDatabase.query(
            "downloads",
            arrayOf("video_id", "quality", "title", "author", "tags", "download_id", "author_id", "author_username", "description"),
            null, null, null, null, "created_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += DownloadRecord(
                    item = VideoItem(
                        id = c.getString(0),
                        title = c.getString(2),
                        author = c.getString(3),
                        tags = splitTags(c.getString(4)),
                        likes = 0,
                        authorId = c.getString(6).orEmpty(),
                        authorUsername = c.getString(7).orEmpty(),
                        description = c.getString(8).orEmpty()
                    ),
                    quality = c.getString(1),
                    downloadId = c.getLong(5)
                )
            }
        }
        return out
    }

    @Synchronized
    fun forgetDownload(videoId: String, quality: String) {
        writableDatabase.delete("downloads", "video_id=? AND quality=?", arrayOf(videoId, quality))
    }

    @Synchronized
    fun recordWatch(item: VideoItem, positionMs: Long, durationMs: Long, completed: Boolean) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("video_id", item.id)
            put("title", item.title)
            put("author", item.author)
            put("tags", item.tags.joinToString("\u001F"))
            put("last_position", positionMs.coerceAtLeast(0))
            put("duration", durationMs.coerceAtLeast(0))
            put("watched_at", now)
            put("completed", if (completed) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        markSeen(item.id, now)
    }

    @Synchronized
    fun markSeen(videoId: String, timestamp: Long = System.currentTimeMillis()) {
        if (videoId.isBlank()) return
        val db = writableDatabase
        val initial = ContentValues().apply {
            put("video_id", videoId)
            put("first_seen_at", timestamp)
            put("last_seen_at", timestamp)
        }
        db.insertWithOnConflict("seen_videos", null, initial, SQLiteDatabase.CONFLICT_IGNORE)
        val update = ContentValues().apply { put("last_seen_at", timestamp) }
        db.update("seen_videos", update, "video_id=?", arrayOf(videoId))
    }

    /** 一次写入一批已看 ID：同步几百条官方点赞时按单条写会很慢。 */
    @Synchronized
    fun markSeen(videoIds: Collection<String>, timestamp: Long = System.currentTimeMillis()) {
        val ids = videoIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                val initial = ContentValues().apply {
                    put("video_id", id)
                    put("first_seen_at", timestamp)
                    put("last_seen_at", timestamp)
                }
                db.insertWithOnConflict("seen_videos", null, initial, SQLiteDatabase.CONFLICT_IGNORE)
                db.update("seen_videos", ContentValues().apply { put("last_seen_at", timestamp) },
                    "video_id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun isSeen(videoId: String): Boolean {
        readableDatabase.query(
            "seen_videos", arrayOf("video_id"), "video_id=?", arrayOf(videoId),
            null, null, null, "1"
        ).use { if (it.moveToFirst()) return true }

        readableDatabase.query(
            "history", arrayOf("video_id"), "video_id=?", arrayOf(videoId),
            null, null, null, "1"
        ).use { return it.moveToFirst() }
    }

    @Synchronized
    fun setLocalFavorite(item: VideoItem, enabled: Boolean) {
        if (enabled) {
            val values = ContentValues().apply {
                put("video_id", item.id)
                put("title", item.title)
                put("author", item.author)
                put("tags", item.tags.joinToString("\u001F"))
                put("created_at", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            markSeen(item.id)
        } else {
            writableDatabase.delete("favorites", "video_id=?", arrayOf(item.id))
        }
        item.localFavorite = enabled
    }

    @Synchronized
    fun isLocalFavorite(videoId: String): Boolean {
        readableDatabase.query(
            "favorites", arrayOf("video_id"), "video_id=?", arrayOf(videoId),
            null, null, null, "1"
        ).use { return it.moveToFirst() }
    }

    @Synchronized
    fun recordInteraction(item: VideoItem, action: String, weight: Double) {
        val values = ContentValues().apply {
            put("video_id", item.id)
            put("action", action)
            put("author", item.author)
            put("tags", item.tags.joinToString("\u001F"))
            put("weight", weight)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("interactions", null, values)
        writableDatabase.execSQL(
            "DELETE FROM interactions WHERE id NOT IN (SELECT id FROM interactions ORDER BY created_at DESC LIMIT 1000)"
        )
    }

    @Synchronized
    fun recentHistory(limit: Int = 100): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        readableDatabase.query(
            "history",
            arrayOf("video_id", "title", "author", "tags"),
            null, null, null, null, "watched_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += VideoItem(
                    id = c.getString(0),
                    title = c.getString(1),
                    author = c.getString(2),
                    tags = splitTags(c.getString(3)),
                    likes = 0
                )
            }
        }
        return out
    }

    @Synchronized
    fun localFavorites(limit: Int = 200): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        readableDatabase.query(
            "favorites",
            arrayOf("video_id", "title", "author", "tags"),
            null, null, null, null, "created_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += VideoItem(
                    id = c.getString(0),
                    title = c.getString(1),
                    author = c.getString(2),
                    tags = splitTags(c.getString(3)),
                    likes = 0,
                    localFavorite = true
                )
            }
        }
        return out
    }

    @Synchronized
    fun preferenceProfile(): PreferenceProfile {
        val author = HashMap<String, Double>()
        val tags = HashMap<String, Double>()
        readableDatabase.query(
            "interactions",
            arrayOf("author", "tags", "weight", "created_at"),
            null, null, null, null, "created_at DESC", "500"
        ).use { c ->
            val now = System.currentTimeMillis()
            while (c.moveToNext()) {
                val ageDays = ((now - c.getLong(3)).coerceAtLeast(0) / 86_400_000.0)
                val decay = 1.0 / (1.0 + ageDays / 30.0)
                val w = c.getDouble(2) * decay
                val a = c.getString(0).lowercase()
                author[a] = (author[a] ?: 0.0) + w
                splitTags(c.getString(1)).forEach { tag ->
                    val key = tag.lowercase()
                    tags[key] = (tags[key] ?: 0.0) + w * 0.45
                }
            }
        }
        return PreferenceProfile(author, tags)
    }

    /**
     * 首次查询会顺带打开数据库并跑升级迁移。启动时在后台先做掉，
     * 首屏视频到达后主线程读收藏状态就不用再等这一步。
     */
    fun warmUp() {
        Thread({ runCatching { readableDatabase } }, "IwaraFlow-history-warmup").apply { isDaemon = true }.start()
    }

    private fun splitTags(raw: String?): List<String> =
        raw?.split("\u001F")?.filter { it.isNotBlank() } ?: emptyList()

    companion object {
        /** 同一个视频的不同清晰度各算一条，所以主键是视频加清晰度。 */
        private val DOWNLOADS_TABLE = """CREATE TABLE IF NOT EXISTS downloads(
                video_id TEXT NOT NULL,
                quality TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                tags TEXT NOT NULL,
                download_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                author_id TEXT NOT NULL DEFAULT '',
                author_username TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(video_id, quality)
            )""".trimIndent()
    }
}
