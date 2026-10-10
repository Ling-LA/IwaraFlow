package com.ling.iwaraflow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Account-scoped acknowledged likes, shared by every page and persisted across restarts. */
class VideoLikeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val accounts = context.applicationContext.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
    data class Snapshot(val account: String, val session: Long, val revision: Long)
    private data class State(val liked: Boolean, val count: Int?, val revision: Long, val localAt: Long, val actionRevision: Long)
    private val account get() = accounts.getString(AppPrefs.KEY_ACCOUNT_ID, "").orEmpty()
    fun snapshot() = synchronized(lock) { Snapshot(account, SecureSessionStore.accountRevision, preferences.getLong("revision", 0L)) }
    fun isCurrent(snapshot: Snapshot) = snapshot.account == account && snapshot.session == SecureSessionStore.accountRevision
    private fun key(account: String, id: String) = "video:$account:$id"
    private fun read(account: String, id: String): State? = preferences.getString(key(account, id), null)?.let { raw ->
        runCatching { JSONObject(raw).let { State(it.getBoolean("liked"), if (it.has("count")) it.getInt("count") else null,
            it.getLong("revision"), it.optLong("localAt"), it.optLong("actionRevision")) } }.getOrNull()
    }
    private fun write(account: String, id: String, liked: Boolean, count: Int?, localAt: Long, actionRevision: Long? = null) {
        val revision = preferences.getLong("revision", 0L) + 1
        val value = JSONObject().put("liked", liked).put("revision", revision).put("localAt", localAt)
            .put("actionRevision", actionRevision ?: revision)
        count?.let { value.put("count", it.coerceAtLeast(0)) }
        // apply updates the process-wide preferences immediately and writes to disk off the UI thread.
        preferences.edit().putLong("revision", revision).putString(key(account, id), value.toString()).apply()
    }
    fun apply(item: VideoItem): VideoItem = synchronized(lock) {
        val current = account
        if (item.likeStateAccount != null && item.likeStateAccount != current) item.liked = false
        item.likeStateAccount = current
        if (current.isNotBlank()) read(current, item.id)?.let { state ->
            item.liked = state.liked
            state.count?.let { item.likes = it }
        }
        item
    }

    /** Only call after the server accepted POST/DELETE. Late requests cannot undo newer actions. */
    fun confirm(snapshot: Snapshot, videoId: String, liked: Boolean, count: Int? = null): Boolean = synchronized(lock) {
        if (!isCurrent(snapshot) || snapshot.account.isBlank()) return false
        val previous = read(snapshot.account, videoId)
        if (previous != null && previous.actionRevision > snapshot.revision) {
            // The API already acknowledged this operation; the card may now supply its missing count.
            if (previous.liked == liked && previous.count == null && count != null)
                write(snapshot.account, videoId, liked, count, previous.localAt, previous.actionRevision)
            return false
        }
        val updatedCount = count ?: previous?.count?.let { it + if (previous.liked == liked) 0 else if (liked) 1 else -1 }
        write(snapshot.account, videoId, liked, updatedCount, System.currentTimeMillis())
        true
    }

    /** Lists may omit personal status. Only authenticated detail responses can replace a known state. */
    fun observe(snapshot: Snapshot, item: VideoItem, detail: Boolean, countKnown: Boolean, statusKnown: Boolean = true) = synchronized(lock) {
        if (!isCurrent(snapshot) || snapshot.account.isBlank()) return
        val previous = read(snapshot.account, item.id)
        if (previous != null) {
            if (previous.revision > snapshot.revision) return
            if (!detail) {
                // Refresh the public count without treating a list's missing/false flag as an unlike.
                if (countKnown && previous.count != item.likes && System.currentTimeMillis() - previous.localAt >= WRITE_GRACE_MS)
                    write(snapshot.account, item.id, previous.liked, item.likes, previous.localAt, previous.actionRevision)
                return
            }
            // Some server replicas briefly return the pre-write state even after a successful write.
            if (previous.liked != item.liked && System.currentTimeMillis() - previous.localAt < WRITE_GRACE_MS) return
        } else if (!detail && (!statusKnown || !item.liked)) return
        val count = if (countKnown) item.likes else previous?.count
        if (previous?.liked == item.liked && previous.count == count) return
        val localAt = if (previous?.liked == item.liked) previous.localAt else 0L
        write(snapshot.account, item.id, item.liked, count, localAt, previous?.actionRevision ?: 0L)
    }

    fun hasImportedLikes() = account.isNotBlank() && preferences.getBoolean("imported:$account", false)
    fun markImported(snapshot: Snapshot) = synchronized(lock) {
        if (isCurrent(snapshot) && snapshot.account.isNotBlank()) preferences.edit().putBoolean("imported:${snapshot.account}", true).apply()
    }

    fun listen(changed: (String?) -> Unit): java.io.Closeable {
        val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
            if (name?.startsWith("video:$account:") == true) changed(name.removePrefix("video:$account:"))
        }
        val accountListener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
            if (name == AppPrefs.KEY_ACCOUNT_ID) changed(null)
        }
        preferences.registerOnSharedPreferenceChangeListener(stateListener)
        accounts.registerOnSharedPreferenceChangeListener(accountListener)
        return java.io.Closeable {
            preferences.unregisterOnSharedPreferenceChangeListener(stateListener)
            accounts.unregisterOnSharedPreferenceChangeListener(accountListener)
        }
    }
    companion object {
        internal const val FILE = "iwara_video_likes"
        internal const val WRITE_GRACE_MS = 120_000L
        private val lock = Any()
    }
}
