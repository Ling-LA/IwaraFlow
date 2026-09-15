package com.ling.iwaraflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/** 一条下载记录：哪个视频、哪个清晰度、系统下载器给的编号。 */
data class DownloadRecord(val item: VideoItem, val quality: String, val downloadId: Long)

/** 用户明确拉黑的作者 / 标签。不随时间衰减，也不会滚出行为窗口。 */
data class MutedEntities(
    val authors: Set<String> = emptySet(),
    val authorIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet()
)

class HistoryStore(context: Context) : SQLiteOpenHelper(context, "iwaraflow.db", null, 8) {
    /**
     * **UI 线程不写库**。划一条视频要写好几笔（观看记录、行为、已看标记），
     * 而这些方法都是 `@Synchronized` 的——和后台算画像抢同一把锁，用久了就是划走那一下的微卡顿。
     * 写操作都排到这一条线程上，顺序照旧（单线程），只是不再挡住手势。
     */
    private val writes = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IwaraFlow-history-write").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    /**
     * 当前登录的 Iwara 账号 id，页面在启动和登录时写进来（见 [AppPrefs.accountId]）。
     *
     * 只影响**从云端导进来的**那部分数据：官方点赞种子、同步进来的已看标记。
     * 本机自己的行为（看了多久、本地收藏、不感兴趣）永远是设备级的，不带账号。
     * 空串表示没登录 / 还不知道是谁——那就只认设备级的数据。
     * 构造时直接从偏好里读一份，这样每个页面各自 new 出来的库都已经知道现在是谁。
     */
    @Volatile var accountId: String = runCatching {
        context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            .getString(AppPrefs.KEY_ACCOUNT_ID, "").orEmpty()
    }.getOrDefault("")

    /** 把一次写库交给写线程。页面已经销毁（写队列关掉了）就丢掉，不抛异常。 */
    fun post(block: () -> Unit) {
        if (closed) return
        // shutdown 之后排队的写照旧跑完（close 会等它们），只有新来的才丢。
        runCatching { writes.execute { runCatching { block() } } }
    }

    /** 划走 / 点赞这些都发生在 UI 线程上，写库交给写线程。 */
    fun recordInteractionAsync(item: VideoItem, action: String, weight: Double) =
        post { recordInteraction(item, action, weight) }

    fun recordWatchAsync(item: VideoItem, positionMs: Long, durationMs: Long, completed: Boolean) =
        post { recordWatch(item, positionMs, durationMs, completed) }

    fun markSeenAsync(videoId: String) = post { markSeen(videoId) }

    /** 收藏状态 UI 立刻就要用，所以先在调用线程上改好，落库再排队。 */
    fun setLocalFavoriteAsync(item: VideoItem, enabled: Boolean) {
        item.localFavorite = enabled
        post { setLocalFavorite(item, enabled) }
    }

    /** 等写队列清空。关库前和用例里要用：不等的话读到的是写之前的状态。 */
    fun awaitWrites(timeoutMs: Long = AWAIT_WRITES_MS) {
        if (closed) return
        val done = java.util.concurrent.CountDownLatch(1)
        post { done.countDown() }
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    override fun close() {
        if (!closed) {
            closed = true
            writes.shutdown()
            runCatching { writes.awaitTermination(AWAIT_WRITES_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        super.close()
    }

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
                created_at INTEGER NOT NULL,
                author_id TEXT NOT NULL DEFAULT '',
                account_id TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE seen_videos(
                video_id TEXT PRIMARY KEY,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                account_id TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(DOWNLOADS_TABLE)
        db.execSQL(MUTED_TABLE)
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
        if (oldVersion < 6) {
            // 5 → 6：行为记录带上作者 id，画像才能按作者 id 去召回作品。
            try { db.execSQL("ALTER TABLE interactions ADD COLUMN author_id TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) { }
        }
        if (oldVersion < 7) {
            // 6 → 7：「不感兴趣：作者 / 标签」搬进自己的表。
            // 以前它只是 interactions 里的一行，而那张表是滚动窗口（只留最近 3000 条、
            // 画像只读最近 2000 条）——刷得够多，几个月前拉黑的作者就会悄悄回到推荐里。
            // 拉黑是**显式**的意思表示，只有兴趣管理里的“恢复”能解除，不该被时间冲掉。
            db.execSQL(MUTED_TABLE)
            backfillMutes(db)
        }
        if (oldVersion < 8) {
            // 7 → 8：从云端导进来的数据挂到账号上。
            // 以前官方点赞和同步进来的已看标记都是设备级的，换个账号登录，
            // 上一个账号的点赞还在当口味用，它点过的视频对新账号也算“已看”。
            for (table in listOf("interactions", "seen_videos")) {
                try { db.execSQL("ALTER TABLE $table ADD COLUMN account_id TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) { }
            }
            // 已经存在的官方点赞种子不知道属于谁，留着就等于继续串。直接删掉，
            // 下次同步会在正确的账号下重新种一遍（页面会把 likedSyncAt 清零）。
            try { db.execSQL("DELETE FROM interactions WHERE action='$ACTION_CLOUD_LIKE'") } catch (_: Throwable) { }
        }
    }

    /** 一条待写入的静音：类型、键、互为别名的另一个键、时间。 */
    private class MuteRow(val type: String, val key: String, val alias: String, val at: Long)

    /** 把老版本记在 interactions 里的「不感兴趣：作者 / 标签」搬进 [MUTED_TABLE]。 */
    private fun backfillMutes(db: SQLiteDatabase) {
        val rows = ArrayList<MuteRow>()
        runCatching {
            db.query(
                "interactions", arrayOf("author", "author_id", "tags", "action", "created_at"),
                "action IN (?, ?)", arrayOf(ACTION_DISLIKE_AUTHOR, ACTION_DISLIKE_TAG),
                null, null, null
            ).use { c ->
                while (c.moveToNext()) {
                    val at = c.getLong(4)
                    if (c.getString(3) == ACTION_DISLIKE_AUTHOR) {
                        val name = c.getString(0).orEmpty().lowercase()
                        val id = c.getString(1).orEmpty()
                        if (name.isNotBlank()) rows += MuteRow(MUTE_AUTHOR, name, id, at)
                        if (id.isNotBlank()) rows += MuteRow(MUTE_AUTHOR_ID, id, name, at)
                    } else {
                        splitTags(c.getString(2)).forEach { tag ->
                            rows += MuteRow(MUTE_TAG, tag.lowercase(), "", at)
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) return
        db.beginTransaction()
        try {
            rows.forEach { insertMute(db, it.type, it.key, it.at, it.alias) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertMute(db: SQLiteDatabase, type: String, key: String, at: Long, alias: String = "") {
        val values = ContentValues().apply {
            put("type", type)
            put("entity_key", key)
            put("created_at", at)
            put("alias", alias)
        }
        db.insertWithOnConflict("muted_entities", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * 记一条永久静音。和 [recordInteraction] 里那条负反馈是两回事：
     * 那条只是把打分压下去（会随时间衰减、会滚出窗口），这条是硬屏蔽，只有“恢复”能删。
     */
    @Synchronized
    fun mute(type: String, key: String) {
        val value = key.trim().let { if (type == MUTE_AUTHOR_ID) it else it.lowercase() }
        if (value.isBlank()) return
        insertMute(writableDatabase, type, value, System.currentTimeMillis())
    }

    /**
     * 拉黑一个作者：名字和 id 各记一行，**互为别名**。
     * 兴趣管理里列出来的是名字，但拉黑时真正拦住内容的多半是 id（作者可能改名）；
     * 记下别名，按名字“恢复”时才能把 id 那一行也一起删掉。
     */
    @Synchronized
    fun muteAuthor(name: String, authorId: String) {
        val label = name.trim().lowercase()
        val id = authorId.trim()
        val db = writableDatabase
        val now = System.currentTimeMillis()
        if (label.isNotBlank()) insertMute(db, MUTE_AUTHOR, label, now, id)
        if (id.isNotBlank()) insertMute(db, MUTE_AUTHOR_ID, id, now, label)
    }

    /** 解除一个作者的静音，名字和 id 传哪个都行：互为别名的两行一起删。 */
    @Synchronized
    fun unmuteAuthor(key: String): Int {
        val value = key.trim()
        if (value.isBlank()) return 0
        return writableDatabase.delete(
            "muted_entities",
            "type IN (?, ?) AND (entity_key=? OR entity_key=? OR alias=? OR alias=?)",
            arrayOf(MUTE_AUTHOR, MUTE_AUTHOR_ID, value, value.lowercase(), value, value.lowercase())
        )
    }

    /** 兴趣管理里的“恢复”：解除静音，返回删掉了几条。 */
    @Synchronized
    fun unmute(type: String, key: String): Int {
        val value = key.trim().let { if (type == MUTE_AUTHOR_ID) it else it.lowercase() }
        if (value.isBlank()) return 0
        return writableDatabase.delete("muted_entities", "type=? AND entity_key=?", arrayOf(type, value))
    }

    /** 当前所有的硬屏蔽：作者名、作者 id、标签各一份。 */
    @Synchronized
    fun mutedEntities(): MutedEntities {
        val authors = HashSet<String>()
        val authorIds = HashSet<String>()
        val tags = HashSet<String>()
        runCatching {
            readableDatabase.query("muted_entities", arrayOf("type", "entity_key"), null, null, null, null, null)
                .use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(1).orEmpty()
                        if (key.isBlank()) continue
                        when (c.getString(0)) {
                            MUTE_AUTHOR -> authors += key
                            MUTE_AUTHOR_ID -> authorIds += key
                            MUTE_TAG -> tags += key
                        }
                    }
                }
        }
        return MutedEntities(authors, authorIds, tags)
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
        insertSeen(writableDatabase, videoId, timestamp, "")
    }

    /**
     * 写一条已看。[account] 为空表示**这台设备上真的看过**（本机行为，永远算数）；
     * 非空表示这是从某个账号的云端数据同步进来的，只对那个账号算数。
     */
    private fun insertSeen(db: SQLiteDatabase, videoId: String, timestamp: Long, account: String) {
        val initial = ContentValues().apply {
            put("video_id", videoId)
            put("first_seen_at", timestamp)
            put("last_seen_at", timestamp)
            put("account_id", account)
        }
        db.insertWithOnConflict("seen_videos", null, initial, SQLiteDatabase.CONFLICT_IGNORE)
        val update = ContentValues().apply { put("last_seen_at", timestamp) }
        // 只更新时间：本机看过的那一行不该被后来的同步改成账号级，反过来也一样。
        db.update("seen_videos", update, "video_id=?", arrayOf(videoId))
    }

    /** 一次写入一批已看 ID：同步几百条官方点赞时按单条写会很慢。 */
    @Synchronized
    fun markSeen(
        videoIds: Collection<String>,
        timestamp: Long = System.currentTimeMillis(),
        account: String = ""
    ) {
        val ids = videoIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id -> insertSeen(db, id, timestamp, account) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun isSeen(videoId: String): Boolean {
        readableDatabase.query(
            "seen_videos", arrayOf("video_id"), "video_id=? AND $ACCOUNT_SCOPE",
            arrayOf(videoId, accountId), null, null, null, "1"
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

    /**
     * 一次问清一批视频的「看过没 / 收藏没」。
     *
     * 推荐候选分桶和首页装点原来是每条视频各查一次 `isSeen` 和 `isLocalFavorite`，
     * 几百条候选就是上千次 SQLite 查询，刷新推荐的延迟里有不少是耗在这上面的。
     * SQLite 的变量个数有上限，按 [STATUS_BATCH] 分批问。
     */
    @Synchronized
    fun loadStatuses(videoIds: Collection<String>): VideoStatuses {
        val ids = videoIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return VideoStatuses(emptySet(), emptySet())
        val seen = HashSet<String>()
        val favorites = HashSet<String>()
        val db = readableDatabase
        ids.chunked(STATUS_BATCH).forEach { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val args = batch.toTypedArray()
            fun collect(table: String, into: MutableSet<String>, scoped: Boolean = false) {
                val where = if (scoped) "video_id IN ($placeholders) AND $ACCOUNT_SCOPE" else "video_id IN ($placeholders)"
                val params = if (scoped) args + accountId else args
                db.query(table, arrayOf("video_id"), where, params, null, null, null)
                    .use { c -> while (c.moveToNext()) into += c.getString(0) }
            }
            // 别的账号同步进来的“已看”不算数，本机真的看过的（account_id 为空）永远算数。
            collect("seen_videos", seen, scoped = true)
            // 老版本只写了 history，没有 seen_videos 的那批也算看过。
            collect("history", seen)
            collect("favorites", favorites)
        }
        return VideoStatuses(seen, favorites)
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
        writableDatabase.insert("interactions", null, interactionValues(item, action, weight, System.currentTimeMillis()))
        trimInteractions()
    }

    /**
     * 把行为表修剪回 [MAX_INTERACTIONS] 条，**摊销着做**。
     *
     * 以前每插一条就跑一次 `DELETE ... NOT IN (SELECT ... LIMIT 3000)`——这条语句要
     * 先排序三千行再做一次反向匹配，而插入本身只是一行。刷视频时每划一条就写好几笔行为，
     * 代价全压在划走那一下。现在攒够 [TRIM_EVERY] 次才看一眼，而且只有真的超出
     * [MAX_INTERACTIONS] + [TRIM_SLACK] 才删；表最多比上限多留几百行，画像只读最近
     * [PROFILE_ROWS] 条，多出来的那点完全看不见。
     */
    private fun trimInteractions(inserted: Int = 1) {
        writesSinceTrim += inserted
        if (writesSinceTrim < TRIM_EVERY) return
        writesSinceTrim = 0
        val db = writableDatabase
        val count = runCatching { android.database.DatabaseUtils.queryNumEntries(db, "interactions") }.getOrDefault(0L)
        if (count <= MAX_INTERACTIONS + TRIM_SLACK) return
        db.execSQL(
            "DELETE FROM interactions WHERE id NOT IN (SELECT id FROM interactions ORDER BY created_at DESC LIMIT $MAX_INTERACTIONS)"
        )
    }

    /** 距离上一次修剪写进去了多少条行为，见 [trimInteractions]。 */
    private var writesSinceTrim = 0

    /**
     * 把 Iwara 官方的历史点赞作为画像的种子：一个用了 Iwara 多年的账号第一次装上就有准确的偏好。
     * 权重比本地实时点赞低，每条视频只记一次（同步是反复跑的）。返回新记入的条数。
     */
    @Synchronized
    fun seedCloudLikes(items: List<VideoItem>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var added = 0
        db.beginTransaction()
        try {
            items.forEach { item ->
                if (item.id.isBlank()) return@forEach
                val exists = db.query(
                    "interactions", arrayOf("id"), "video_id=? AND action=? AND account_id=?",
                    arrayOf(item.id, ACTION_CLOUD_LIKE, accountId),
                    null, null, null, "1"
                ).use { it.moveToFirst() }
                if (exists) return@forEach
                db.insert("interactions", null,
                    interactionValues(item, ACTION_CLOUD_LIKE, CLOUD_LIKE_WEIGHT, now, accountId))
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) trimInteractions(added)
        return added
    }

    /** [account] 只有云端导进来的行为才填（见 [accountId]），本机行为一律留空。 */
    private fun interactionValues(
        item: VideoItem, action: String, weight: Double, now: Long, account: String = ""
    ): ContentValues {
        val values = ContentValues().apply {
            put("video_id", item.id)
            put("action", action)
            put("author", item.author)
            put("author_id", item.authorId)
            put("tags", item.tags.joinToString("\u001F"))
            put("weight", weight)
            put("created_at", now)
            put("account_id", account)
        }
        return values
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

    /**
     * 长期口味 + 当前这一会儿的兴趣。所有行为按时间衰减（一个月折半）；最近 [SESSION_WINDOW_MS]
     * 里发生的再乘 [SESSION_BOOST]——连着看了几条同一主题，后面的推荐就该马上跟上。
     * 官方点赞的种子不算“当前兴趣”，否则一次同步进来几百条就把画像冲成旧口味。
     */
    fun preferenceProfile(): PreferenceProfile = preferenceProfileAt(System.currentTimeMillis())

    @Synchronized
    internal fun preferenceProfileAt(now: Long): PreferenceProfile {
        val author = HashMap<String, Double>()
        val authorIds = HashMap<String, Double>()
        val tags = HashMap<String, Double>()
        val videos = HashMap<String, Double>()
        // 长期持续快速划走：同一作者 / 标签攒够 [LONG_TERM_SKIPS] 次划走，再额外压一层。
        val authorSkips = HashMap<String, Int>()
        val authorIdSkips = HashMap<String, Int>()
        val tagSkips = HashMap<String, Int>()
        // 明确点过「不感兴趣：作者 / 标签」的：这类内容在打分之前就被剔掉，连探索位都不给。
        // 读的是独立的 muted_entities 表，不是下面这个滚动窗口——拉黑是永久的。
        val muted = mutedEntities()
        readableDatabase.query(
            "interactions",
            arrayOf("author", "tags", "weight", "created_at", "author_id", "action", "video_id"),
            // 别的账号导进来的点赞不算这个账号的口味；本机行为（account_id 为空）永远算数。
            ACCOUNT_SCOPE, arrayOf(accountId), null, null, "created_at DESC", PROFILE_ROWS.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val at = c.getLong(3)
                val action = c.getString(5)
                val ageDays = ((now - at).coerceAtLeast(0) / 86_400_000.0)
                // 搜索只是“我想看看这是什么”，不代表“以后多给我推这个”：几个小时就淡掉。
                // 真正点开结果、看得久、点赞收藏之后才会变成长期兴趣。
                val decay = if (action == ACTION_SEARCH) {
                    val ageHours = ((now - at).coerceAtLeast(0) / 3_600_000.0)
                    1.0 / (1.0 + ageHours / SEARCH_HALF_LIFE_HOURS)
                } else 1.0 / (1.0 + ageDays / 30.0)
                val session = if (now - at <= SESSION_WINDOW_MS && action != ACTION_CLOUD_LIKE) SESSION_BOOST else 1.0
                val w = c.getDouble(2) * decay * session
                // 「不感兴趣：当前视频」是视频级的：只压这一条，不落到作者和标签上。
                if (action == ACTION_DISLIKE_VIDEO) {
                    val videoId = c.getString(6).orEmpty()
                    if (videoId.isNotBlank()) videos[videoId] = (videos[videoId] ?: 0.0) + w
                    continue
                }
                val a = c.getString(0).lowercase()
                val id = c.getString(4).orEmpty()
                val itemTags = splitTags(c.getString(1)).map { it.lowercase() }
                if (a.isNotBlank()) author[a] = (author[a] ?: 0.0) + w
                if (id.isNotBlank()) authorIds[id] = (authorIds[id] ?: 0.0) + w
                itemTags.forEach { key -> tags[key] = (tags[key] ?: 0.0) + w * 0.45 }
                if (action == ACTION_SKIP) {
                    if (a.isNotBlank()) authorSkips[a] = (authorSkips[a] ?: 0) + 1
                    if (id.isNotBlank()) authorIdSkips[id] = (authorIdSkips[id] ?: 0) + 1
                    itemTags.forEach { key -> tagSkips[key] = (tagSkips[key] ?: 0) + 1 }
                }
            }
        }
        authorSkips.forEach { (key, n) -> if (n >= LONG_TERM_SKIPS) author[key] = (author[key] ?: 0.0) - LONG_TERM_AUTHOR_PENALTY }
        authorIdSkips.forEach { (key, n) -> if (n >= LONG_TERM_SKIPS) authorIds[key] = (authorIds[key] ?: 0.0) - LONG_TERM_AUTHOR_PENALTY }
        tagSkips.forEach { (key, n) -> if (n >= LONG_TERM_SKIPS) tags[key] = (tags[key] ?: 0.0) - LONG_TERM_TAG_PENALTY }
        return PreferenceProfile(author, tags, authorIds, videos, muted.authors, muted.authorIds, muted.tags)
    }

    /**
     * 推荐质量的本地诊断指标。
     *
     * 单元测试回答的是「算法有没有按我写的规则跑」，回答不了「这套规则有没有让推荐变好」。
     * 这里从本地的行为表和观看记录算几个能直接看出好坏的数：快速划走率、平均有效播放、
     * 播放完成率、点赞收藏率、作者 / 标签重复率。全部只算本机数据，不上传。
     */
    @Synchronized
    fun recommendationMetrics(limit: Int = METRICS_ROWS): RecommendationMetrics {
        var watches = 0
        var skips = 0
        var likes = 0
        val authors = ArrayList<String>()
        val tags = ArrayList<String>()
        readableDatabase.query(
            "interactions", arrayOf("action", "author", "tags"),
            null, null, null, null, "created_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val action = c.getString(0)
                when (action) {
                    "watch" -> { watches += 1; authors += c.getString(1).lowercase(); tags += splitTags(c.getString(2)).map { it.lowercase() } }
                    ACTION_SKIP -> { skips += 1; authors += c.getString(1).lowercase(); tags += splitTags(c.getString(2)).map { it.lowercase() } }
                    "like", "favorite" -> likes += 1
                }
            }
        }
        var played = 0L
        var playedRows = 0
        var completed = 0
        readableDatabase.query(
            "history", arrayOf("last_position", "duration", "completed"),
            null, null, null, null, "watched_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                played += c.getLong(0).coerceAtLeast(0L)
                playedRows += 1
                if (c.getInt(2) == 1) completed += 1
            }
        }
        val viewed = watches + skips
        val distinctAuthors = authors.filter { it.isNotBlank() }.toSet().size
        val authorTotal = authors.count { it.isNotBlank() }
        val distinctTags = tags.toSet().size
        return RecommendationMetrics(
            samples = viewed,
            quickSkipRate = if (viewed == 0) 0.0 else skips.toDouble() / viewed,
            averageWatchMs = if (playedRows == 0) 0L else played / playedRows,
            completionRate = if (playedRows == 0) 0.0 else completed.toDouble() / playedRows,
            reactionRate = if (viewed == 0) 0.0 else likes.toDouble() / viewed,
            authorRepeatRate = if (authorTotal == 0) 0.0 else 1.0 - distinctAuthors.toDouble() / authorTotal,
            tagRepeatRate = if (tags.isEmpty()) 0.0 else 1.0 - distinctTags.toDouble() / tags.size
        )
    }

    /**
     * 兴趣管理里的“恢复”：把某个作者 / 标签的「不感兴趣」记录删掉，
     * 既解除硬屏蔽（muted_entities），也删掉行为表里那条压分的负反馈，
     * 它就能重新参与推荐和探索。返回一共删掉了几条。
     */
    @Synchronized
    fun forgetDislike(kind: DislikeSheet.Kind, key: String): Int {
        val value = key.trim()
        if (value.isBlank()) return 0
        val db = writableDatabase
        return when (kind) {
            // 兴趣管理里列的是作者名，老记录里也可能只有作者 id，两种键都能解除。
            DislikeSheet.Kind.AUTHOR -> unmuteAuthor(value) + db.delete(
                "interactions",
                "action=? AND (LOWER(author)=? OR author_id=?)",
                arrayOf(ACTION_DISLIKE_AUTHOR, value.lowercase(), value)
            )
            DislikeSheet.Kind.TAG -> unmute(MUTE_TAG, value) + db.delete(
                "interactions",
                "action=? AND (LOWER(tags)=? OR LOWER(tags) LIKE ? OR LOWER(tags) LIKE ? OR LOWER(tags) LIKE ?)",
                arrayOf(
                    ACTION_DISLIKE_TAG, value.lowercase(),
                    "${value.lowercase()}%", "%${value.lowercase()}", "%${value.lowercase()}%"
                )
            )
            DislikeSheet.Kind.VIDEO -> db.delete(
                "interactions", "action=? AND video_id=?", arrayOf(ACTION_DISLIKE_VIDEO, value)
            )
        }
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
        /** 官方历史点赞种进画像时用的动作名和权重（低于本地点赞的 2.0）。 */
        const val ACTION_CLOUD_LIKE = "cloud_like"
        const val CLOUD_LIKE_WEIGHT = 1.2
        /** 行为表最多留多少条；官方点赞种子可能一次进来几百条，所以比以前放宽。 */
        const val MAX_INTERACTIONS = 3000
        /** 算画像时读多少条最近的行为。 */
        const val PROFILE_ROWS = 2000
        /** 账号作用域：本机行为（空账号）永远算数，云端导进来的只对导它进来的账号算数。 */
        private const val ACCOUNT_SCOPE = "(account_id='' OR account_id=?)"
        /** 修剪行为表的摊销参数：攒够这么多条才检查一次，超出上限这么多才真的删，见 [trimInteractions]。 */
        const val TRIM_EVERY = 100
        const val TRIM_SLACK = 200
        /** 等写队列清空最多等这么久（关库、用例里用）。 */
        const val AWAIT_WRITES_MS = 5000L
        /** 批量查“看过没 / 收藏没”时一次问多少个 id（SQLite 的变量个数有上限）。 */
        const val STATUS_BATCH = 400
        /** 算推荐诊断指标时看最近多少条记录。 */
        const val METRICS_ROWS = 200
        /** 最近这么久里的行为算“当前兴趣”，权重再乘 [SESSION_BOOST]。 */
        const val SESSION_WINDOW_MS = 45L * 60L * 1000L
        const val SESSION_BOOST = 2.5
        /** 快速划走的动作名；同一作者 / 标签在画像窗口里攒到这么多次就额外压一层。 */
        const val ACTION_SKIP = "skip"
        /** 「不感兴趣：当前视频」。只作用于这条视频本身，见 [preferenceProfileAt]。 */
        const val ACTION_DISLIKE_VIDEO = "dislike_video"
        /** 「不感兴趣：作者 / 标签」。明确的负反馈，比“划走得快”重得多，连探索位都不给。 */
        const val ACTION_DISLIKE_AUTHOR = "dislike_author"
        const val ACTION_DISLIKE_TAG = "dislike_tag"

        /** [MUTED_TABLE] 里的三种键：作者名（小写）、作者 id、标签（小写）。 */
        const val MUTE_AUTHOR = "author"
        const val MUTE_AUTHOR_ID = "author_id"
        const val MUTE_TAG = "tag"

        /**
         * 搜索关键词只当**短期意图**：权重小、几个小时就淡掉。
         * 一次好奇的搜索不该把长期画像带偏；真正点开搜索结果才是兴趣。
         */
        const val ACTION_SEARCH = "search"
        const val SEARCH_WEIGHT = 0.25
        const val SEARCH_OPEN_WEIGHT = 1.0
        /** 搜索行为的衰减尺度（小时）：3 小时后权重减半。 */
        const val SEARCH_HALF_LIFE_HOURS = 3.0
        const val LONG_TERM_SKIPS = 5
        const val LONG_TERM_AUTHOR_PENALTY = 1.5
        const val LONG_TERM_TAG_PENALTY = 0.8

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

        /**
         * 硬屏蔽表。**故意独立于 interactions**：那张表是滚动窗口，会被新行为挤掉；
         * 「不感兴趣：作者 / 标签」是用户明说的，只有兴趣管理里的“恢复”能解除。
         */
        private val MUTED_TABLE = """CREATE TABLE IF NOT EXISTS muted_entities(
                type TEXT NOT NULL,
                entity_key TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                alias TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(type, entity_key)
            )""".trimIndent()
    }
}
