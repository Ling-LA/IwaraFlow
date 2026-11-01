package com.ling.iwaraflow

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Serializes official playlist operations across pages; server acknowledgement always precedes local state. */
class PlaylistFavorites(context: Context, private val api: IwaraApi, private val history: HistoryStore) : Closeable {
    private val app = context.applicationContext
    val store = PlaylistStore(app)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private val ownedPending = HashSet<String>()
    private val ownedMigrations = HashSet<String>()
    data class Change(val saved: Boolean, val changed: Boolean)
    fun isFavorite(id: String) = store.isFavorite(id)
    fun listen(changed: (String?) -> Unit) = store.listen(changed)
    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        synchronized(pending) { pending.removeAll(ownedPending); ownedPending.clear() }
        synchronized(migrations) { migrations.removeAll(ownedMigrations); ownedMigrations.clear() }
    }

    private fun account(): PlaylistStore.Snapshot {
        if (!api.isLoggedIn()) throw IOException("请先登录 Iwara")
        val session = SecureSessionStore.accountRevision
        val verifiedId = api.playlistAccountBlocking()
        synchronized(SecureSessionStore.accountLock) {
            if (session != SecureSessionStore.accountRevision || !api.isLoggedIn()) throw IOException("账号已切换，请重试")
            val prefs = AppPrefs(app)
            if (prefs.accountId != verifiedId) {
                prefs.accountId = verifiedId
                RecommendationEngine.notifyAccountChanged(verifiedId)
            }
            history.accountId = verifiedId
        }
        val s = store.snapshot()
        requireCurrent(s)
        return s
    }
    private fun requireCurrent(s: PlaylistStore.Snapshot) {
        if (!store.current(s) || !api.isLoggedIn()) throw IOException("账号已切换，请重试")
    }
    private fun <T> submit(callback: (Result<T>) -> Unit, priority: Int = 0, block: (PlaylistStore.Snapshot) -> T) {
        val session = SecureSessionStore.accountRevision
        val queuedAccount = store.snapshot().account
        worker.execute(Task(priority) task@{
            if (closed) return@task
            var resolvedAccount = queuedAccount
            val result = runCatching {
                if (session != SecureSessionStore.accountRevision || queuedAccount != store.snapshot().account) throw IOException("账号已切换，请重试")
                // Pin the verified identity before work. Reading prefs after the final write can
                // accidentally relabel an A result as B when the account switches at that point.
                val operation = account()
                resolvedAccount = operation.account
                block(operation)
            }
            main.post {
                if (!closed) callback(if (session == SecureSessionStore.accountRevision && resolvedAccount == store.snapshot().account)
                    result else Result.failure(IOException("账号已切换，请重试")))
            }
        })
    }

    fun refresh(item: VideoItem) {
        if (closed || !api.isLoggedIn() || store.checkedAt(item.id) > System.currentTimeMillis() - 120_000L) return
        val key = "${store.snapshot().account}:${item.id}"
        synchronized(pending) { if (!pending.add(key)) return; ownedPending.add(key) }
        submit({ synchronized(pending) { pending.remove(key); ownedPending.remove(key) } }, priority = 5) { s ->
            try { memberships(s, item.id) } finally { synchronized(pending) { pending.remove(key) } }
        }
    }
    private fun memberships(s: PlaylistStore.Snapshot, videoId: String): List<PlaylistFolder> {
        requireCurrent(s)
        val rows = api.getPlaylistMembershipsBlocking(videoId)
        requireCurrent(s)
        store.confirmMemberships(s, videoId, rows.filter { it.added }.mapTo(HashSet()) { it.id })
        val known = store.folders(s).associateBy { it.id }
        rows.forEach { row -> if (row.id !in known) store.putFolder(s, row) }
        return rows
    }
    private fun folders(s: PlaylistStore.Snapshot): List<PlaylistFolder> {
        val result = LinkedHashMap<String, PlaylistFolder>()
        for (page in 0 until 1000) {
            requireCurrent(s)
            val batch = api.getPlaylistFoldersBlocking(s.account, page)
            val before = result.size
            batch.folders.forEach { result[it.id] = it }
            if (!batch.hasMore) {
                requireCurrent(s); store.replaceFolders(s, result.values.toList()); return result.values.toList()
            }
            if (result.size == before) throw IOException("收藏夹分页未前进，请稍后刷新")
        }
        throw IOException("收藏夹数量过多，请稍后再试")
    }
    fun refreshFolders(callback: (Result<List<PlaylistFolder>>) -> Unit) = submit(callback, priority = 2) { s -> folders(s) }
    fun createFolder(title: String, callback: (Result<PlaylistFolder>) -> Unit) = submit(callback) { s ->
        val row = api.createPlaylistBlocking(title)
        requireCurrent(s); store.putFolder(s, row); row
    }
    private fun defaultFolder(s: PlaylistStore.Snapshot, rows: List<PlaylistFolder>): PlaylistFolder {
        val id = store.defaultId(s)
        val existing = rows.firstOrNull { it.id == id } ?: rows.firstOrNull { it.title == DEFAULT_TITLE } ?: rows.firstOrNull()
        if (existing != null) return existing
        requireCurrent(s)
        return api.createPlaylistBlocking(DEFAULT_TITLE).also { requireCurrent(s); store.putFolder(s, it) }
    }
    fun add(item: VideoItem, callback: (Result<Change>) -> Unit) = submit(callback) { s ->
        val rows = memberships(s, item.id)
        val wasSaved = rows.any { it.added }
        // A double-reaction must not copy an already-saved video into another folder.
        if (wasSaved) return@submit Change(true, false)
        val folder = defaultFolder(s, rows)
        requireCurrent(s); api.setPlaylistVideoBlocking(folder.id, item.id, true)
        requireCurrent(s); store.confirmWrite(s, item, folder, true); store.setDefault(s, folder.id)
        Change(true, true)
    }
    /** Short tap on a saved star removes this video from every authoritative membership. */
    fun remove(item: VideoItem, callback: (Result<Change>) -> Unit) = submit(callback) { s ->
        val joined = memberships(s, item.id).filter { it.added }
        preserveExplicitRemoval(s, item.id)
        if (joined.isEmpty()) return@submit Change(false, false)
        var failure: Exception? = null
        for (folder in joined.distinctBy { it.id }) {
            try {
                requireCurrent(s); api.setPlaylistVideoBlocking(folder.id, item.id, false)
                requireCurrent(s); store.confirmRemovalWrite(s, item.id, folder)
            } catch (error: Exception) { failure = error; break }
        }
        // Even a rejected/lost write response can be followed by an authoritative result.
        // If read-back fails, the prior star stays on; no false "removed" success is emitted.
        requireCurrent(s)
        val remaining = try { memberships(s, item.id).any { it.added } } catch (error: Exception) {
            throw IOException("取消收藏结果尚未确认，请刷新后重试", error)
        }
        if (remaining) throw IOException(if (failure != null) "部分收藏夹取消失败，视频仍在收藏中，请重试"
            else "视频仍在其他收藏夹中，请重试取消", failure)
        Change(false, true)
    }
    private fun preserveExplicitRemoval(s: PlaylistStore.Snapshot, videoId: String) {
        requireCurrent(s)
        // Preserve the legacy archive itself, but do not let an unfinished automatic import
        // override a user's newer explicit removal on its next incremental worker turn.
        if ((store.migrated(s, videoId) || store.dedicatedMigrationItem(s, videoId).isNotBlank() || history.isLocalFavorite(videoId)) &&
            !store.markDedicatedMigrationItem(s, videoId, "removed_by_user")) throw IOException("无法保存取消收藏进度，请重试")
        requireCurrent(s)
    }
    internal fun loadPicker(item: VideoItem, callback: (Result<List<PlaylistFolder>>) -> Unit) = submit(callback) { s ->
        val all = folders(s).associateBy { it.id }
        memberships(s, item.id).map { row -> (all[row.id] ?: row).copy(added = row.added) }
    }
    internal fun applySelection(item: VideoItem, selected: Set<String>, callback: (Result<Change>) -> Unit) = submit(callback) { s ->
        val rows = memberships(s, item.id)
        val wasSaved = rows.any { it.added }
        val known = rows.map { it.id }.toSet()
        if ((selected - known).isNotEmpty()) throw IOException("收藏夹列表有变化，请刷新后重试")
        if (selected.isEmpty()) preserveExplicitRemoval(s, item.id)
        // Add first so moving folders cannot lose the last copy when one request fails.
        rows.filter { it.id in selected && !it.added }.forEach { folder ->
            requireCurrent(s); api.setPlaylistVideoBlocking(folder.id, item.id, true)
            requireCurrent(s); store.confirmWrite(s, item, folder, true); store.setDefault(s, folder.id)
        }
        rows.filter { it.id !in selected && it.added }.forEach { folder ->
            requireCurrent(s); api.setPlaylistVideoBlocking(folder.id, item.id, false)
            requireCurrent(s); store.confirmWrite(s, item, folder, false)
        }
        Change(selected.isNotEmpty(), wasSaved != selected.isNotEmpty())
    }
    fun showPicker(activity: Activity, item: VideoItem, callback: (Result<Change>) -> Unit) {
        if (!closed) PlaylistPicker.show(activity, this, item, callback)
    }
    fun getFolderPage(folderId: String, page: Int, callback: (Result<PlaylistVideoPage>) -> Unit) = submit(callback, priority = 2) { s ->
        try {
            val result = api.getPlaylistPageBlocking(folderId, page)
            requireCurrent(s); store.cachePage(s, page, result); result
        } catch (error: Exception) {
            requireCurrent(s)
            if (error is PlaylistHttpException && error.status in setOf(400, 401, 403, 404)) throw error
            store.page(folderId, page, s) ?: throw error
        }
    }
    /** Automatic upgrade migration is bound to its first authenticated account, with durable per-item checkpoints. */
    fun migrateLegacyIfNeeded() {
        if (closed || !api.isLoggedIn()) return
        val s = store.snapshot()
        if (s.account.isBlank() || store.migrationComplete(s) || store.migrationRetryAt(s) > System.currentTimeMillis()) return
        val owner = store.migrationOwner()
        if (!AppPrefs(app).isolateLibrary && owner.isNotBlank() && owner != s.account) return
        startLegacy(automatic = true) { }
    }
    fun importLegacy(retryUnconfirmedCreation: Boolean = false, callback: (Result<Int>) -> Unit) =
        startLegacy(automatic = false, retryUnconfirmedCreation = retryUnconfirmedCreation, callback = callback)
    /** Never adopt an existing official category by its display name or quick-save preference. */
    private fun dedicatedMigrationFolder(s: PlaylistStore.Snapshot, all: List<PlaylistFolder>, retryUnconfirmedCreation: Boolean): PlaylistFolder {
        val target = store.dedicatedMigrationTarget(s)
        if (target.isNotBlank()) return all.firstOrNull { it.id == target }
            ?: throw IOException("旧版收藏专用收藏夹已被删除；为尊重你的操作，不会自动重建")
        val pendingCreation = store.migrationCreation(s)
        if (pendingCreation != null) {
            val candidates = all.filter { it.title == pendingCreation.title && it.id !in pendingCreation.existingIds }
            if (candidates.isEmpty() && retryUnconfirmedCreation) {
                // Only the explicit recovery dialog may authorize another POST after the
                // user checked the official site. Automatic startup never retries it.
                if (!store.clearMigrationCreation(s)) throw IOException("无法保存重试进度")
                return dedicatedMigrationFolder(s, all, false)
            }
            if (candidates.size != 1) throw IOException("默认收藏夹创建结果尚未确认，请在官网核对后重试；不会重复创建或使用已有分类")
            val recovered = candidates.single()
            if (!store.setDedicatedMigrationTarget(s, recovered.id)) throw IOException("无法保存默认收藏夹，请重试")
            return recovered
        }
        val titles = all.mapTo(HashSet()) { it.title }
        val title = if (DEFAULT_TITLE !in titles) DEFAULT_TITLE else {
            val base = "$DEFAULT_TITLE（旧版收藏）"
            if (base !in titles) base else generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in titles }
        }
        val existingIds = all.mapTo(HashSet()) { it.id }
        // Persist before POST: after a lost response/crash only read back an unambiguous
        // newly-created ID. Retrying the POST could create another public playlist.
        if (!store.prepareMigrationCreation(s, title, existingIds)) throw IOException("无法保存默认收藏夹创建进度")
        requireCurrent(s)
        val created = try { api.createPlaylistBlocking(title, existingIds) } catch (error: PlaylistHttpException) {
            // Definitive rejections did not create a folder. Timeouts/5xx remain uncertain.
            if (error.status in setOf(400, 401, 403, 404, 413, 415, 422, 429)) store.clearMigrationCreation(s)
            throw error
        }
        requireCurrent(s)
        if (created.id in existingIds) throw IOException("官网返回了已有收藏夹，已停止导入以保护分类")
        if (!store.setDedicatedMigrationTarget(s, created.id)) throw IOException("无法保存默认收藏夹，请重试")
        store.putFolder(s, created)
        return created
    }
    private fun startLegacy(automatic: Boolean, retryUnconfirmedCreation: Boolean = false, callback: (Result<Int>) -> Unit) {
        val migrationKey = store.snapshot().account
        val isolatedSource = AppPrefs(app).isolateLibrary
        synchronized(migrations) { if (!migrations.add(migrationKey)) { callback(Result.failure(IOException("旧版收藏正在同步"))); return }; ownedMigrations.add(migrationKey) }
        fun finish(result: Result<Int>) {
            synchronized(migrations) { migrations.remove(migrationKey); ownedMigrations.remove(migrationKey) }
            val s = store.snapshot()
            if (s.account == migrationKey) result.onFailure {
                store.migrationProgress(s, "旧版收藏同步未完成：${it.message}，稍后会继续", failed = true)
                if (automatic && !closed) main.postDelayed({ migrateLegacyIfNeeded() }, 60_500L)
            }
            callback(result)
        }
        submit({ prepared -> prepared.onFailure { finish(Result.failure(it)) }.onSuccess { (s, folder, originals) ->
            fun next(index: Int, count: Int) {
                if (index == originals.size) {
                    store.migrationProgress(s, "旧版收藏已同步至独立默认收藏夹 · 原分类与本地记录已保留", complete = true)
                    finish(Result.success(count)); return
                }
                submit({ result -> result.onFailure { finish(Result.failure(IOException("已处理 $index 条；${it.message}"))) }
                    .onSuccess { changed -> next(index + 1, count + if (changed) 1 else 0) } }, priority = 10) {
                    requireCurrent(s); val item = originals[index]
                    if (AppPrefs(app).isolateLibrary != isolatedSource) throw IOException("本地收藏隔离设置已变化，请重新同步")
                    if (store.dedicatedMigrationItem(s, item.id).isNotBlank()) return@submit false
                    val rows = memberships(s, item.id)
                    val already = rows.any { it.id == folder.id && it.added }
                    // A v1 checkpoint only means "processed", not "inserted by us". If the
                    // user has since removed it (or deleted that entire folder), never undo
                    // that choice while repairing the old migration destination.
                    if (!already && store.migrated(s, item.id) && rows.none { it.id == store.migrationTarget(s) && it.added }) {
                        if (!store.markDedicatedMigrationItem(s, item.id, "skipped_removed")) throw IOException("无法保存同步进度，请重试")
                        return@submit false
                    }
                    if (!already) {
                        api.setPlaylistVideoBlocking(folder.id, item.id, true)
                        requireCurrent(s); store.confirmWrite(s, item, folder, true)
                    }
                    if (!store.markDedicatedMigrationItem(s, item.id, if (already) "already_present" else "added")) throw IOException("无法保存同步进度，请重试")
                    store.migrationProgress(s, "正在同步至独立默认收藏夹：${index + 1}/${originals.size} · 公开")
                    !already
                }
            }
            next(0, 0)
        } }, priority = 10) { s ->
            if (s.account != migrationKey) throw IOException("账号身份已更新，请重新同步")
            if (AppPrefs(app).isolateLibrary != isolatedSource) throw IOException("本地收藏隔离设置已变化，请重新同步")
            if (!isolatedSource && !store.claimMigration(s) && automatic) throw IOException("旧版收藏属于另一账号，请在我的收藏中手动导入")
            // Use a dedicated history instance pinned to the captured source account. Other pages
            // may update their HistoryStore.activeAccount while account discovery is in flight.
            val originals = HistoryStore(app).use { source -> source.accountId = s.account; source.localFavorites(100_000) }
            requireCurrent(s)
            if (AppPrefs(app).isolateLibrary != isolatedSource) throw IOException("本地收藏隔离设置已变化，请重新同步")
            val folder = if (originals.isEmpty()) PlaylistFolder("", DEFAULT_TITLE) else {
                dedicatedMigrationFolder(s, folders(s), retryUnconfirmedCreation)
            }
            if (folder.id.isNotBlank()) store.setDefault(s, folder.id)
            Triple(s, folder, originals)
        }
    }
    companion object {
        const val DEFAULT_TITLE = "默认收藏夹"
        private val sequence = AtomicLong()
        private class Task(private val priority: Int, private val body: () -> Unit) : Runnable, Comparable<Task> {
            private val order = sequence.incrementAndGet()
            override fun run() = body()
            override fun compareTo(other: Task) = compareValuesBy(this, other, { it.priority }, { it.order })
        }
        private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, PriorityBlockingQueue())
        private val pending = HashSet<String>()
        private val migrations = HashSet<String>()
    }
}
