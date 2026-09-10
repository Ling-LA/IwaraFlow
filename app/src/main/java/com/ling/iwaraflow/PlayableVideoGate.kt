package com.ling.iwaraflow

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class PlayableVideoGate(
    private val api: IwaraApi,
    private val batchBudgetMs: Long = DEFAULT_BATCH_BUDGET_MS
) {
    private val coordinator = Executors.newSingleThreadExecutor()
    private val probes = Executors.newFixedThreadPool(MAX_CANDIDATES)
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun filterPlayable(
        items: List<VideoItem>,
        quality: String,
        maxItems: Int = items.size,
        onFirstBatch: ((List<VideoItem>) -> Unit)? = null,
        callback: (List<VideoItem>) -> Unit
    ) {
        enqueue {
            // Cold start used to wait for up to 16 CDN/source validations before showing the
            // first frame. Ten parallel candidates are enough to seed a swipe feed while keeping
            // the invariant that every item shown has already passed a real media-byte probe.
            val candidateCount = minOf(items.size, minOf(maxItems + 2, MAX_CANDIDATES))
            val candidates = items.take(candidateCount)
            val futures = inspectAsync(candidates, quality)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchBudgetMs)
            if (onFirstBatch != null) {
                // The feed only needs its leading cards to start playing, so a single slow CDN
                // candidate must not hold the whole cold start. Stopping at the first candidate
                // that is still running keeps this head an exact prefix of the final list.
                val head = collect(futures, FIRST_BATCH, deadline, stopOnPending = true)
                if (!closed && head.isNotEmpty()) onFirstBatch(head)
            }
            val accepted = collect(futures, maxItems, deadline, stopOnPending = false)
            if (!closed) callback(accepted)
        }
    }

    /** Author pages keep every item so unavailable works can show the server/resource reason. */
    fun inspectAll(items: List<VideoItem>, quality: String, callback: (List<VideoItem>) -> Unit) {
        enqueue {
            val futures = inspectAsync(items, quality)
            val inspected: List<VideoItem> = futures
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .sortedBy { pair -> pair.first }
                .map { pair -> pair.second }
            if (!closed) callback(inspected)
        }
    }

    private fun collect(
        futures: List<Future<Pair<Int, VideoItem>>>,
        limit: Int,
        deadlineNanos: Long,
        stopOnPending: Boolean
    ): List<VideoItem> {
        val accepted = ArrayList<VideoItem>(minOf(limit, futures.size))
        for (future in futures) {
            if (accepted.size >= limit) break
            // 已经验证完的候选不受预算影响，只有还在跑的才可能被跳过。
            val decided = if (future.isDone) runCatching { future.get() }.getOrNull() else {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) null
                else runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()
            }
            if (decided == null) {
                if (stopOnPending) break else continue
            }
            val item = decided.second
            if (item.playbackIssue == null) accepted += item
        }
        return accepted
    }

    private fun enqueue(task: () -> Unit) = synchronized(lifecycleLock) {
        if (!closed) coordinator.execute { if (!closed) task() }
    }

    private fun inspectAsync(items: List<VideoItem>, quality: String): List<Future<Pair<Int, VideoItem>>> =
        items.mapIndexedNotNull { index, item ->
            synchronized(lifecycleLock) {
                if (closed) null else probes.submit<Pair<Int, VideoItem>> { Pair(index, inspectOne(item, quality)) }
            }
        }

    private fun inspectOne(item: VideoItem, quality: String): VideoItem {
        item.playbackIssue = null
        return try {
            val sources = item.sources?.takeIf { it.isNotEmpty() } ?: api.resolveSourcesBlocking(item.id)
            val preferred = api.chooseSource(sources, item.selectedQuality ?: quality)
            val ordered = buildList {
                if (preferred != null) add(preferred)
                sources.forEach { source -> if (source.url != preferred?.url) add(source) }
            }
            val working = ordered.firstOrNull { source -> probe(source.url) }
            if (working == null) {
                item.playbackIssue = "视频资源无法连接"
            } else {
                item.sources = sources
                item.streamUrl = working.url
                if (preferred == null || working.url != preferred.url) item.selectedQuality = working.name
            }
            item
        } catch (e: Exception) {
            val raw = e.message.orEmpty()
            item.playbackIssue = when {
                item.isPrivate || raw.contains("friend", true) || raw.contains("好友") -> "仅限好友观看"
                raw.contains("processing", true) || raw.contains("处理") -> "视频仍在处理中"
                raw.contains("deleted", true) || raw.contains("不存在") || raw.contains("404") -> "视频已删除或不存在"
                raw.contains("403") || raw.contains("forbidden", true) -> "没有观看权限"
                raw.isNotBlank() -> raw.take(60)
                else -> "暂时无法播放"
            }
            item
        }
    }

    private fun probe(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-4095")
            .header("Accept", "*/*")
            .header("Referer", "https://www.iwara.tv/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) return@use false
                response.body?.source()?.request(1) == true
            }
        }.getOrDefault(false)
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            coordinator.shutdownNow()
            probes.shutdownNow()
        }
        HttpClientCleanup.close(client)
    }

    companion object {
        private const val MAX_CANDIDATES = 10
        private const val FIRST_BATCH = 3
        /** A straggling candidate is dropped from the batch instead of holding the feed forever. */
        private const val DEFAULT_BATCH_BUDGET_MS = 12_000L
    }
}
