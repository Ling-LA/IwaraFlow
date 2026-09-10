package com.ling.iwaraflow

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln

class RecommendationEngine(
    private val api: IwaraApi,
    private val history: HistoryStore,
    private val listBudgetMs: Long = DEFAULT_LIST_BUDGET_MS
) {
    private val io = Executors.newSingleThreadExecutor()

    fun load(skipSeen: Boolean, callback: (Result<List<VideoItem>>) -> Unit) {
        io.execute {
            callback(runCatching { loadBlocking(skipSeen) })
        }
    }

    /** 候选按“没看过 / 看过但没点赞 / 已点赞收藏”分三档，前面不够时才用后面的。 */
    private class Buckets {
        val fresh = ArrayList<VideoItem>()
        val watched = ArrayList<VideoItem>()
        val liked = ArrayList<VideoItem>()
        fun all(): List<VideoItem> = fresh + watched + liked
    }

    private fun loadBlocking(skipSeen: Boolean): List<VideoItem> {
        val pool = Executors.newFixedThreadPool(SORTS.size + 1)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(listBudgetMs)
            val merged = LinkedHashMap<String, Pair<VideoItem, Double>>()
            val likedFuture = if (skipSeen && api.isLoggedIn()) {
                // Iwara 官方点赞只存在服务端，列表接口不一定回传 liked，
                // 所以刷新推荐时顺带同步一次点赞记录，让它们真正算作已看。
                pool.submit<List<VideoItem>> { api.getFavoriteVideosBlocking() }
            } else null

            mergePage(pool, merged, page = 0, deadline = deadline)
            if (merged.isEmpty()) throw IllegalStateException("所有推荐榜单请求均失败")

            likedFuture?.let { f ->
                val remaining = deadline - System.nanoTime()
                val liked = if (remaining <= 0L) null
                else runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()
                liked?.forEach { item -> history.markSeen(item.id) }
            }

            if (!skipSeen) return rank(merged).take(MAX_RESULTS)

            var buckets = split(rank(merged))
            // 看得多、点赞多的账号很容易把一页候选全过滤掉。先往后翻几页找新视频，
            // 再不够就退回看过但没点赞的，最后才是全部——总之不能给一个空推荐页。
            var page = 1
            while (buckets.fresh.size < MIN_FRESH_CANDIDATES && page <= MAX_EXTRA_PAGES) {
                val extraDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(listBudgetMs)
                val before = merged.size
                mergePage(pool, merged, page = page, deadline = extraDeadline)
                page += 1
                if (merged.size == before) break
                buckets = split(rank(merged))
            }

            val result = when {
                buckets.fresh.size >= MIN_FRESH_CANDIDATES -> buckets.fresh
                buckets.fresh.isNotEmpty() || buckets.watched.isNotEmpty() -> buckets.fresh + buckets.watched
                else -> buckets.all()
            }
            return result.take(MAX_RESULTS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun mergePage(
        pool: java.util.concurrent.ExecutorService,
        merged: LinkedHashMap<String, Pair<VideoItem, Double>>,
        page: Int,
        deadline: Long
    ) {
        val futures = SORTS.map { (sort, baseWeight) ->
            pool.submit<Pair<Double, List<VideoItem>>> {
                baseWeight to api.getVideosBlocking(sort, page = page, limit = PAGE_SIZE)
            }
        }
        // One stalled ranking endpoint used to hold the whole cold start; a request that
        // misses the budget is treated like the failures this merge already tolerates.
        futures.forEach { f ->
            val remaining = deadline - System.nanoTime()
            val result = (if (remaining <= 0L) null
            else runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()) ?: return@forEach
            val (baseWeight, videos) = result
            videos.forEachIndexed { index, item ->
                val rankBonus = (PAGE_SIZE - index).coerceAtLeast(0) / PAGE_SIZE.toDouble()
                // 越靠后的页排名加成越低，但仍然按同一套分数参与排序。
                val pageDecay = 1.0 / (1.0 + page * 0.35)
                val previous = merged[item.id]
                val sourceBoost = (baseWeight + rankBonus * 0.8) * pageDecay
                if (previous == null) {
                    merged[item.id] = item to sourceBoost
                } else {
                    merged[item.id] = previous.first to (previous.second + sourceBoost * 0.55)
                    if (item.likes > previous.first.likes) previous.first.likes = item.likes
                    if (item.liked) previous.first.liked = true
                }
            }
        }
    }

    private fun rank(merged: Map<String, Pair<VideoItem, Double>>): List<VideoItem> {
        val profile = history.preferenceProfile()
        val now = System.currentTimeMillis()
        return merged.values
            .asSequence()
            .map { (item, sourceScore) ->
                val likeScore = ln(item.likes + 1.0) * 0.72
                val viewScore = ln(item.views + 1.0) * 0.24
                val ageDays = if (item.createdAt > 0L) {
                    ((now - item.createdAt).coerceAtLeast(0L) / 86_400_000.0)
                } else 30.0
                val freshness = 1.9 / (1.0 + ageDays / 12.0)
                val affinity = profile.score(item).coerceAtMost(8.0) * 0.38
                val stableJitter = ((item.id.hashCode().toLong() and 0xffff) / 65535.0) * 0.15
                item to (sourceScore + likeScore + viewScore + freshness + affinity + stableJitter)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    private fun split(ranked: List<VideoItem>): Buckets {
        val buckets = Buckets()
        ranked.forEach { item ->
            val favourite = item.liked || history.isLocalFavorite(item.id)
            if (favourite) history.markSeen(item.id)
            when {
                favourite -> buckets.liked += item
                history.isSeen(item.id) -> buckets.watched += item
                else -> buckets.fresh += item
            }
        }
        return buckets
    }

    fun close() = io.shutdownNow()

    companion object {
        private val SORTS = listOf(
            "trending" to 3.3,
            "popularity" to 2.8,
            "likes" to 2.1,
            "views" to 1.6
        )
        private const val PAGE_SIZE = 36
        private const val MAX_RESULTS = 80
        /** 低于这个数量就认为“排除已看”把候选榨干了，需要翻页或者放宽。 */
        const val MIN_FRESH_CANDIDATES = 12
        /** 最多再往后翻几页找没看过的视频。 */
        const val MAX_EXTRA_PAGES = 3
        private const val DEFAULT_LIST_BUDGET_MS = 9_000L
    }
}
