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
    /** 可播放验证的通过率，只用于诊断：验了多少条、其中多少条真能播。 */
    private val inspected = java.util.concurrent.atomic.AtomicInteger()
    private val passed = java.util.concurrent.atomic.AtomicInteger()

    private val coordinator = Executors.newSingleThreadExecutor()
    private val probes = Executors.newFixedThreadPool(PROBE_THREADS)
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        // 探测是后台活，不该和正在播的视频抢带宽：同一台 CDN 上同时最多这么几个。
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = PROBE_THREADS
            maxRequestsPerHost = MAX_PROBES_PER_HOST
        })
        .build()

    fun filterPlayable(
        items: List<VideoItem>,
        quality: String,
        maxItems: Int = items.size,
        maxCandidates: Int = COLD_START_CANDIDATES,
        onFirstBatch: ((List<VideoItem>) -> Unit)? = null,
        callback: (List<VideoItem>) -> Unit
    ) {
        enqueue {
            // 冷启动只验证很小的候选窗口，先让首屏出来；用户已经在看视频时
            // （续页）传入更大的窗口，把整页候选都验证完，避免每页只剩几条。
            val window = minOf(maxItems + 2, minOf(maxCandidates, MAX_CANDIDATES))
            val candidateCount = minOf(items.size, window)
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
        val key = cacheKey(item, quality)
        cached(key)?.let { return apply(item, it) }
        val checked = probeOne(item, quality)
        remember(key, checked)
        return checked
    }

    private fun probeOne(item: VideoItem, quality: String): VideoItem {
        inspected.incrementAndGet()
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

    // ---------------------------------------------------------------- 短期结果缓存
    //
    // 同一条视频在推荐流里会被反复验证（首屏窗口、续页、重排之后再来一遍），
    // 每次都是“解析 CDN 地址 + Range 探测”两次往返。滑动时这部分请求量很可观，
    // 所以按 视频 id + 清晰度 记一小段时间：可播的记 10 分钟（CDN 地址本来就会过期，
    // 播放器有自动重解析兜底），明确不可播的（私密 / 已删除 / 无权限）记 1 小时。

    /**
     * 一次验证的结论。**分三类**，因为「不可播放」的原因差别很大：
     * - [Outcome.PLAYABLE]：能播，记 10 分钟（CDN 地址本来就会过期，播放器有兜底）；
     * - [Outcome.PERMANENT]：私密 / 已删除 / 无权限 / 仍在处理——换谁来验都一样，记 1 小时；
     * - [Outcome.TRANSIENT]：连不上、超时、连接被重置——多半是网络或代理抖了一下，
     *   只记几十秒。以前这类和“确定播不了”一样记 1 小时，弱网上闪断一次，
     *   那条视频接下来一小时都被当成坏的。
     */
    private enum class Outcome { PLAYABLE, TRANSIENT, PERMANENT }

    private class Verdict(
        val at: Long,
        val outcome: Outcome,
        val issue: String?,
        val sources: List<VideoSource>?,
        val streamUrl: String?,
        val quality: String?
    )

    private fun cacheKey(item: VideoItem, quality: String): String =
        item.id + "@" + (item.selectedQuality ?: quality)

    private fun cached(key: String): Verdict? = synchronized(verdicts) {
        val verdict = verdicts[key] ?: return null
        val ttl = when (verdict.outcome) {
            Outcome.PLAYABLE -> PLAYABLE_TTL_MS
            Outcome.PERMANENT -> ISSUE_TTL_MS
            Outcome.TRANSIENT -> TRANSIENT_TTL_MS
        }
        if (System.currentTimeMillis() - verdict.at > ttl) {
            verdicts.remove(key)
            return null
        }
        verdict
    }

    private fun remember(key: String, item: VideoItem) {
        val issue = item.playbackIssue
        if (issue == null) passed.incrementAndGet()
        val outcome = when {
            issue == null -> Outcome.PLAYABLE
            issue in PERMANENT_ISSUES -> Outcome.PERMANENT
            else -> Outcome.TRANSIENT
        }
        val verdict = Verdict(System.currentTimeMillis(), outcome, issue, item.sources, item.streamUrl, item.selectedQuality)
        synchronized(verdicts) { verdicts[key] = verdict }
    }

    private fun apply(item: VideoItem, verdict: Verdict): VideoItem {
        item.playbackIssue = verdict.issue
        if (verdict.issue == null) {
            verdict.sources?.let { item.sources = it }
            verdict.streamUrl?.let { item.streamUrl = it }
            verdict.quality?.let { item.selectedQuality = it }
        }
        return item
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

    /** 诊断用：这次会话里验了多少条候选、通过了多少条。 */
    fun verificationStats(): Pair<Int, Int> = inspected.get() to passed.get()

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
        /** 冷启动/首屏默认候选窗口。续页由调用方放大到整页。 */
        const val COLD_START_CANDIDATES = 10
        /** 单批最多验证多少条候选，以及并发探测线程数。 */
        private const val MAX_CANDIDATES = 32
        private const val PROBE_THREADS = 12
        /** 同一台 CDN 主机上同时最多几个探测请求。 */
        private const val MAX_PROBES_PER_HOST = 6
        private const val FIRST_BATCH = 3
        /** A straggling candidate is dropped from the batch instead of holding the feed forever. */
        private const val DEFAULT_BATCH_BUDGET_MS = 12_000L

        /** 验证结果缓存：可播的记这么久（CDN 地址本来就会过期，播放器有兜底）。 */
        internal const val PLAYABLE_TTL_MS = 10L * 60 * 1000
        /** 明确不可播的（私密 / 已删除 / 无权限 / 仍在处理）记久一点，别反复去撞同一堵墙。 */
        internal const val ISSUE_TTL_MS = 60L * 60 * 1000
        /** 连不上 / 超时 / 连接被重置：多半是网络抖了一下，只记几十秒。 */
        internal const val TRANSIENT_TTL_MS = 30L * 1000
        private const val VERDICT_CACHE_MAX = 500

        /** 换谁来验都一样的失败原因，见 [remember]。 */
        internal val PERMANENT_ISSUES = setOf("仅限好友观看", "视频仍在处理中", "视频已删除或不存在", "没有观看权限")

        /**
         * **进程级**的验证缓存：主页验过的视频，切到搜索页、作者页不该再验一遍。
         * 预缓存（MediaPreloadCache）本来就是进程级共享的，这里照同一个思路。
         */
        private val verdicts = object : LinkedHashMap<String, Verdict>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verdict>?): Boolean =
                size > VERDICT_CACHE_MAX
        }

        /** 测试用：清掉进程级缓存，免得用例之间互相串。 */
        internal fun clearProcessCache() = synchronized(verdicts) { verdicts.clear() }
    }
}
