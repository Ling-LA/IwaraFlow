package com.ling.iwaraflow

/** Limits already-consumed metadata without removing the current or upcoming videos. */
internal object FeedWindow {
    const val MAX_ITEMS = 500
    const val KEEP_BEHIND = 80
    fun removable(size: Int, current: Int): Int =
        minOf((size - MAX_ITEMS).coerceAtLeast(0), (current - KEEP_BEHIND).coerceAtLeast(0))
}
