package com.ling.iwaraflow

/** Number of lookahead videos; the current transfer is never governed by this switch. */
object PreloadPolicy {
    fun ahead(enabled: Boolean, stable: Boolean, current: Double, next: Double): Int = when {
        !enabled || !stable || current <= 0.4 -> 0
        current >= 1.0 && next > 0.4 -> 2
        else -> 1
    }
}
