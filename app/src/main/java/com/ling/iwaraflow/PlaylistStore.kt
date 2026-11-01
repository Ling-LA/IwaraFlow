package com.ling.iwaraflow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/** Acknowledged official playlist state. Legacy device-only favorites never enter this store implicitly. */
class PlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val accounts = context.applicationContext.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
    data class Snapshot(val account: String, val session: Long)
    private val account get() = accounts.getString(AppPrefs.KEY_ACCOUNT_ID, "").orEmpty()
    fun snapshot() = Snapshot(account, SecureSessionStore.accountRevision)
    fun current(s: Snapshot) = s.account.isNotBlank() && s.account == account && s.session == SecureSessionStore.accountRevision
    private fun key(s: Snapshot, value: String) = "${s.account}:$value"
    fun folders(s: Snapshot = snapshot()): List<PlaylistFolder> = parseFolders(prefs.getString(key(s, "folders"), "[]").orEmpty())
    fun lastSync(s: Snapshot = snapshot()) = prefs.getLong(key(s, "synced"), 0L)
    fun defaultId(s: Snapshot = snapshot()) = prefs.getString(key(s, "default"), "").orEmpty()
    fun setDefault(s: Snapshot, id: String) = synchronized(lock) {
        if (current(s)) prefs.edit().putString(key(s, "default"), id).apply()
    }
    fun memberships(id: String, s: Snapshot = snapshot()): Set<String> = prefs.getStringSet(key(s, "member:$id"), emptySet()).orEmpty().toSet()
    fun isFavorite(id: String) = snapshot().let { current(it) && memberships(id, it).isNotEmpty() }
    fun favoriteIds(ids: Collection<String>, s: Snapshot = snapshot()): Set<String> = s.let {
        if (!current(s)) emptySet() else ids.filterTo(HashSet()) { memberships(it, s).isNotEmpty() }
    }
    fun checkedAt(id: String, s: Snapshot = snapshot()) = prefs.getLong(key(s, "checked:$id"), 0L)

    fun replaceFolders(s: Snapshot, rows: List<PlaylistFolder>): Boolean = synchronized(lock) {
        if (!current(s)) return false
        val existing = folders(s).map { it.id }.toSet()
        val removed = existing - rows.map { it.id }.toSet()
        val edit = prefs.edit().putString(key(s, "folders"), encodeFolders(rows)).putLong(key(s, "synced"), System.currentTimeMillis())
        if (removed.isNotEmpty()) prefs.all.forEach { (name, value) ->
            if (name.startsWith(key(s, "member:")) && value is Set<*>) edit.putStringSet(name, value.filterIsInstance<String>().toSet() - removed)
            if (removed.any { name.startsWith(key(s, "page:$it:")) }) edit.remove(name)
        }
        edit.putLong("revision", prefs.getLong("revision", 0L) + 1).apply()
        true
    }
    fun putFolder(s: Snapshot, row: PlaylistFolder) = synchronized(lock) {
        if (!current(s)) return
        val all = folders(s).toMutableList(); val index = all.indexOfFirst { it.id == row.id }
        if (index >= 0) all[index] = row else all += row
        prefs.edit().putString(key(s, "folders"), encodeFolders(all)).putLong("revision", prefs.getLong("revision", 0L) + 1).apply()
    }
    fun confirmMemberships(s: Snapshot, videoId: String, ids: Set<String>): Boolean = synchronized(lock) {
        if (!current(s)) return false
        prefs.edit().putStringSet(key(s, "member:$videoId"), ids.toSet())
            .putLong(key(s, "checked:$videoId"), System.currentTimeMillis())
            .putLong("revision", prefs.getLong("revision", 0L) + 1).apply()
        true
    }
    fun confirmWrite(s: Snapshot, item: VideoItem, folder: PlaylistFolder, enabled: Boolean): Boolean = synchronized(lock) {
        if (!current(s)) return false
        val old = memberships(item.id, s)
        val ids = if (enabled) old + folder.id else old - folder.id
        val all = folders(s).toMutableList(); val index = all.indexOfFirst { it.id == folder.id }
        val row = if (index >= 0) all[index] else folder
        val delta = if ((folder.id in old) == enabled) 0 else if (enabled) 1 else -1
        val changed = row.copy(count = (row.count.coerceAtLeast(0) + delta).coerceAtLeast(0), thumbnail = if (enabled && row.thumbnail.isBlank()) item.thumbnailUrl else row.thumbnail)
        if (index >= 0) all[index] = changed else all += changed
        val edit = prefs.edit().putString(key(s, "folders"), encodeFolders(all))
            .putStringSet(key(s, "member:${item.id}"), ids).putLong(key(s, "checked:${item.id}"), System.currentTimeMillis())
        // Any mutation invalidates folder pages; retaining them would reintroduce removed entries.
        prefs.all.keys.filter { it.startsWith(key(s, "page:${folder.id}:")) }.forEach(edit::remove)
        edit.putLong("revision", prefs.getLong("revision", 0L) + 1).apply()
        true
    }
    /** A multi-folder cancellation is not globally confirmed until memberships are read back.
     * Invalidate acknowledged folder contents/counts while retaining the conservative star.
     */
    fun confirmRemovalWrite(s: Snapshot, videoId: String, folder: PlaylistFolder): Boolean = synchronized(lock) {
        if (!current(s)) return false
        val all = folders(s).toMutableList(); val index = all.indexOfFirst { it.id == folder.id }
        val row = if (index >= 0) all[index] else folder
        val changed = row.copy(count = (row.count - if (folder.id in memberships(videoId, s)) 1 else 0).coerceAtLeast(0))
        if (index >= 0) all[index] = changed else all += changed
        val edit = prefs.edit().putString(key(s, "folders"), encodeFolders(all)).putLong(key(s, "checked:$videoId"), 0L)
        prefs.all.keys.filter { it.startsWith(key(s, "page:${folder.id}:")) }.forEach(edit::remove)
        edit.putLong("revision", prefs.getLong("revision", 0L) + 1).apply()
        true
    }
    fun cachePage(s: Snapshot, index: Int, page: PlaylistVideoPage) = synchronized(lock) {
        if (!current(s)) return
        val value = JSONObject().put("folder", folderJson(page.folder)).put("total", page.total).put("more", page.hasMore)
            .put("items", JSONArray(page.items.map(::videoJson)))
        val edit = prefs.edit().putString(key(s, "page:${page.folder.id}:$index"), value.toString())
        if (index == 0 && !page.hasMore) {
            val currentIds = page.items.mapTo(HashSet()) { it.id }
            prefs.all.forEach { (name, value) ->
                if (name.startsWith(key(s, "member:")) && value is Set<*> && name.removePrefix(key(s, "member:")) !in currentIds)
                    edit.putStringSet(name, value.filterIsInstance<String>().toSet() - page.folder.id)
            }
            prefs.all.keys.filter { it.startsWith(key(s, "page:${page.folder.id}:")) && it != key(s, "page:${page.folder.id}:0") }.forEach(edit::remove)
        }
        page.items.forEach { edit.putStringSet(key(s, "member:${it.id}"), memberships(it.id, s) + page.folder.id) }
        edit.putLong("revision", prefs.getLong("revision", 0L) + 1).apply()
        putFolder(s, page.folder)
    }
    fun page(folder: String, index: Int, s: Snapshot = snapshot()): PlaylistVideoPage? = runCatching {
        val root = JSONObject(prefs.getString(key(s, "page:$folder:$index"), null) ?: return null)
        val rows = root.getJSONArray("items")
        PlaylistVideoPage(parseFolder(root.getJSONObject("folder")), (0 until rows.length()).map { video(rows.getJSONObject(it)) },
            root.getInt("total"), root.getBoolean("more"), offline = true)
    }.getOrNull()
    fun previews(folder: String): List<VideoItem> = page(folder, 0)?.items.orEmpty().take(3)
    fun migrationOwner() = prefs.getString("migration_owner", "").orEmpty()
    fun migrationTarget(s: Snapshot) = prefs.getString(key(s, "migration_target"), "").orEmpty()
    fun setMigrationTarget(s: Snapshot, id: String): Unit = synchronized(lock) {
        if (current(s)) prefs.edit().putString(key(s, "migration_target"), id).commit()
    }
    fun claimMigration(s: Snapshot): Boolean = synchronized(lock) {
        if (!current(s)) return false
        val owner = migrationOwner()
        owner == s.account || (owner.isBlank() && prefs.edit().putString("migration_owner", s.account).commit())
    }
    fun migrated(s: Snapshot, id: String) = prefs.getBoolean(key(s, "migrated:$id"), false)
    fun markMigrated(s: Snapshot, id: String): Boolean = synchronized(lock) {
        current(s) && prefs.edit().putBoolean(key(s, "migrated:$id"), true).commit()
    }
    // v1 may have copied into an existing category. Preserve its journal; it is not proof
    // that an item was newly inserted, and must never authorize deletion from that folder.
    data class MigrationCreation(val title: String, val existingIds: Set<String>)
    fun dedicatedMigrationTarget(s: Snapshot) = prefs.getString(key(s, "migration_v2_target"), "").orEmpty()
    fun setDedicatedMigrationTarget(s: Snapshot, id: String): Boolean = synchronized(lock) {
        current(s) && prefs.edit().putString(key(s, "migration_v2_target"), id)
            .remove(key(s, "migration_v2_creation")).commit()
    }
    fun migrationCreation(s: Snapshot): MigrationCreation? = runCatching {
        val raw = prefs.getString(key(s, "migration_v2_creation"), null) ?: return null
        val value = JSONObject(raw); val ids = value.getJSONArray("existingIds")
        MigrationCreation(value.getString("title"), (0 until ids.length()).mapTo(HashSet()) { ids.getString(it) })
    }.getOrNull()
    fun prepareMigrationCreation(s: Snapshot, title: String, existingIds: Set<String>): Boolean = synchronized(lock) {
        current(s) && prefs.edit().putString(key(s, "migration_v2_creation"), JSONObject()
            .put("title", title).put("existingIds", JSONArray(existingIds.toList())).toString()).commit()
    }
    fun clearMigrationCreation(s: Snapshot): Boolean = synchronized(lock) {
        current(s) && prefs.edit().remove(key(s, "migration_v2_creation")).commit()
    }
    fun dedicatedMigrationItem(s: Snapshot, id: String) = prefs.getString(key(s, "migration_v2_item:$id"), "").orEmpty()
    fun markDedicatedMigrationItem(s: Snapshot, id: String, outcome: String): Boolean = synchronized(lock) {
        current(s) && prefs.edit().putString(key(s, "migration_v2_item:$id"), outcome).commit()
    }
    fun migrationComplete(s: Snapshot = snapshot()) = prefs.getBoolean(key(s, "migration_v2_complete"), false)
    fun migrationStatus() = prefs.getString(key(snapshot(), "migration_status"), "").orEmpty()
    fun migrationRetryAt(s: Snapshot = snapshot()) = prefs.getLong(key(s, "migration_v2_retry"), 0L)
    fun migrationProgress(s: Snapshot, message: String, complete: Boolean = false, failed: Boolean = false): Unit = synchronized(lock) {
        if (!current(s)) return
        prefs.edit().putString(key(s, "migration_status"), message).putBoolean(key(s, "migration_v2_complete"), complete)
            .putLong(key(s, "migration_v2_retry"), if (failed) System.currentTimeMillis() + 60_000L else 0L)
            .putLong("revision", prefs.getLong("revision", 0L) + 1).commit()
    }
    fun listen(changed: (String?) -> Unit): Closeable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name -> if (name == "revision") changed(null) }
        val accountListener = SharedPreferences.OnSharedPreferenceChangeListener { _, name -> if (name == AppPrefs.KEY_ACCOUNT_ID) changed(null) }
        prefs.registerOnSharedPreferenceChangeListener(listener); accounts.registerOnSharedPreferenceChangeListener(accountListener)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener); accounts.unregisterOnSharedPreferenceChangeListener(accountListener) }
    }
    companion object {
        internal const val FILE = "iwara_official_playlists"
        private val lock = Any()
        private fun folderJson(f: PlaylistFolder) = JSONObject().put("id", f.id).put("title", f.title).put("count", f.count).put("thumbnail", f.thumbnail)
        private fun parseFolder(o: JSONObject) = PlaylistFolder(o.getString("id"), o.getString("title"), o.optInt("count"), o.optString("thumbnail"))
        private fun encodeFolders(rows: List<PlaylistFolder>) = JSONArray(rows.map(::folderJson)).toString()
        private fun parseFolders(raw: String): List<PlaylistFolder> = runCatching { JSONArray(raw).let { rows ->
            (0 until rows.length()).map { parseFolder(rows.getJSONObject(it)) }
        } }.getOrDefault(emptyList())
        private fun videoJson(v: VideoItem) = JSONObject().put("id", v.id).put("title", v.title).put("author", v.author)
            .put("tags", JSONArray(v.tags)).put("likes", v.likes).put("views", v.views).put("created", v.createdAt)
            .put("authorId", v.authorId).put("username", v.authorUsername).put("thumbnail", v.thumbnailUrl)
        private fun video(o: JSONObject): VideoItem {
            val tags = o.getJSONArray("tags")
            return VideoItem(o.getString("id"), o.getString("title"), o.getString("author"), (0 until tags.length()).map(tags::getString),
                o.optInt("likes"), views = o.optInt("views"), createdAt = o.optLong("created"), authorId = o.optString("authorId"),
                authorUsername = o.optString("username"), thumbnailUrl = o.optString("thumbnail"), localFavorite = true)
        }
    }
}
