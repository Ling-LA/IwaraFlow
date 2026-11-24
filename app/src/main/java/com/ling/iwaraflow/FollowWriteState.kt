package com.ling.iwaraflow

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** Short acknowledgement grace period shared across pages, scoped to the authenticated session. */
internal object FollowWriteState {
    data class Key(val apiRoot: String, val account: String, val session: Long, val author: String)
    data class Change(val key: Key, val following: Boolean, val username: String, val fromWrite: Boolean)
    private data class State(val change: Change, val writtenAt: Long?)
    private const val WRITE_GRACE_MS = 300_000L
    private val listeners = CopyOnWriteArraySet<(Change) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val locks = Array(32) { Any() }
    private val states = LinkedHashMap<Key, State>(32, .75f, true)
    fun lock(key: Key): Any = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
    fun known(key: Key): Boolean? = synchronized(states) { states[key]?.change?.following }
    fun recent(key: Key): Boolean? = synchronized(states) {
        val state = states[key] ?: return@synchronized null
        val at = state.writtenAt ?: return@synchronized null
        state.change.following.takeIf { SystemClock.elapsedRealtime() - at in 0L..WRITE_GRACE_MS }
    }

    fun changes(apiRoot: String, scope: VideoLikeStore.Snapshot): List<Change> = synchronized(states) {
        val now = SystemClock.elapsedRealtime()
        states.values.filter { state ->
            val key = state.change.key
            key.apiRoot == apiRoot && key.account == scope.account && key.session == scope.session &&
                state.writtenAt != null && now - state.writtenAt in 0L..WRITE_GRACE_MS
        }.map { it.change }
    }

    fun listen(listener: (Change) -> Unit): Closeable {
        listeners.add(listener)
        return Closeable { listeners.remove(listener) }
    }

    private fun save(state: State) {
        states[state.change.key] = state
        while (states.size > 512) states.remove(states.keys.first())
    }

    private fun notifyChanged(change: Change) {
        main.post {
            if (known(change.key) == change.following) listeners.forEach { it(change) }
        }
    }

    fun observe(key: Key, following: Boolean, username: String) = synchronized(states) {
        if (recent(key) == null) {
            val previous = known(key)
            val change = Change(key, following, username, fromWrite = false)
            save(State(change, null))
            if (previous != following) notifyChanged(change)
        }
    }

    fun confirm(key: Key, following: Boolean, username: String = "") {
        val change = Change(key, following, username, fromWrite = true)
        synchronized(states) { save(State(change, SystemClock.elapsedRealtime())) }
        notifyChanged(change)
    }
}
