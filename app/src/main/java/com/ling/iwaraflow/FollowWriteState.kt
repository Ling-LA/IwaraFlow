package com.ling.iwaraflow

import android.os.SystemClock

/** Short acknowledgement grace period shared across pages, scoped to the authenticated session. */
internal object FollowWriteState {
    data class Key(val apiRoot: String, val account: String, val session: Long, val author: String)
    private data class State(val following: Boolean, val at: Long)
    private val locks = Array(32) { Any() }
    private val states = LinkedHashMap<Key, State>(32, .75f, true)
    fun lock(key: Key): Any = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
    fun recent(key: Key): Boolean? = synchronized(states) {
        val state = states[key] ?: return@synchronized null
        if (SystemClock.elapsedRealtime() - state.at !in 0L..300_000L) {
            states.remove(key); null
        } else state.following
    }
    fun confirm(key: Key, following: Boolean) = synchronized(states) {
        states[key] = State(following, SystemClock.elapsedRealtime())
        while (states.size > 512) states.remove(states.keys.first())
    }
}
