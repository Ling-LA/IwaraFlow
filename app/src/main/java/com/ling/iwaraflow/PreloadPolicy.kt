package com.ling.iwaraflow

/** Number of lookahead videos; the current transfer is never governed by this switch. */
object PreloadPolicy {
    fun enoughBandwidth(bytesPerSecond: Long, mediaBytes: Long, durationMs: Long, bufferedMs: Long): Boolean {
        if (mediaBytes <= 0 || durationMs <= 0 || bytesPerSecond <= 0) return false
        val consumption = mediaBytes * 1000.0 / durationMs
        return bufferedMs >= 15_000 && bytesPerSecond >= consumption * 1.6
    }
    fun ahead(enabled: Boolean, stable: Boolean, current: Double, next: Double): Int = when {
        !enabled || !stable || current <= 0.4 -> 0
        current >= 1.0 && next > 0.4 -> 2
        else -> 1
    }
}
