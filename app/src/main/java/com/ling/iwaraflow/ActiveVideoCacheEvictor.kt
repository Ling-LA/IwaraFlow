package com.ling.iwaraflow

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** Active downloads may exceed the idle budget; evict old videos before the current video. */
internal class ActiveVideoCacheEvictor(private val idleBudget: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
    private var bytes = 0L
    val owners = java.util.concurrent.ConcurrentHashMap<Int, Set<String>>()
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) { evict(cache, length.coerceAtLeast(0)) }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { if (spans.add(span)) bytes += span.length; evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) bytes -= span.length }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan); spans.add(newSpan); evict(cache, 0)
    }
    private fun evict(cache: Cache, incoming: Long) {
        val protected = owners.values.flatMap { it }.toHashSet()
        while (bytes + incoming > idleBudget) {
            val oldest = spans.firstOrNull { it.key !in protected } ?: break
            cache.removeSpan(oldest)
        }
    }
}
