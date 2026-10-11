package com.ling.iwaraflow

import android.content.Context
import android.content.SharedPreferences

/** Shared daily budget for speculative transfers, never a limit on normal playback. */
internal class PreloadBudget(context: Context) {
    private val prefs = AppPrefs(context)
    private val store = context.getSharedPreferences("preload_budget", Context.MODE_PRIVATE)
    private fun today(): String = java.time.LocalDate.now().toString()
    private fun current(): Usage {
        val day = today()
        val old = usage[store]
        if (old?.day == day) return old
        return Usage(day, if (store.getString("day", "") == day) store.getLong("bytes", 0) else 0)
            .also { usage[store] = it }
    }
    fun allowed(): Boolean = synchronized(lock) {
        val limit = prefs.meteredPreloadMb.toLong() * 1024 * 1024
        limit == 0L || current().bytes < limit
    }
    fun record(bytes: Long) = synchronized(lock) {
        val now = current()
        now.bytes += bytes.coerceAtLeast(0)
        now.pending += bytes.coerceAtLeast(0)
        // The in-memory counter is exact across players; batch disk writes to avoid per-packet I/O.
        if (now.pending >= 1024 * 1024 || !allowed()) flush()
    }
    fun flush() = synchronized(lock) {
        val now = current()
        store.edit().putString("day", now.day).putLong("bytes", now.bytes).apply()
        now.pending = 0
    }
    private data class Usage(val day: String, var bytes: Long, var pending: Long = 0)
    companion object {
        private val lock = Any()
        private val usage = java.util.WeakHashMap<SharedPreferences, Usage>()
    }
}
