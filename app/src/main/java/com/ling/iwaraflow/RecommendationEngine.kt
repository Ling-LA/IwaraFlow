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

    private fun loadBlocking(skipSeen: Boolean): List<VideoItem> {
        val sorts = listOf(
            "trending" to 3.3,
            "popularity" to 2.8,
            "likes" to 2.1,
            "views" to 1.6
        )
        val pool = Executors.newFixedThreadPool(sorts.size + 1)
        try {
            val futures = sorts.map { (sort, baseWeight) ->
                pool.submit<Pair<Double, List<VideoItem>>> {
                    baseWeight to api.getVideosBlocking(sort, page = 0, limit = 36)
                }
            }
            // Iwara 官方点赞只存在服务端，列表接口不一定回传 liked，
            // 所以刷新推荐时顺带同步一次点赞记录，让它们真正算作已看。
            val likedFuture = if (skipSeen && api.isLoggedIn()) {
                pool.submit<List<VideoItem>> { api.getFavoriteVideosBlocking() }
            } else null
            val merged = LinkedHashMap<String, Pair<VideoItem, Double>>()
            // One stalled ranking endpoint used to hold the whole cold start; a request that
            // misses the budget is treated like the failures this merge already tolerates.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(listBudgetMs)
            futures.forEach { f ->
                val remaining = deadline - System.nanoTime()
                val result = (if (remaining <= 0L) null
                else runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()) ?: return@forEach
                val (baseWeight, videos) = result
                videos.forEachIndexed { index, item ->
                    val rankBonus = (36 - index).coerceAtLeast(0) / 36.0
                    val previous = merged[item.id]
                    val sourceBoost = baseWeight + rankBonus * 0.8
                    if (previous == null) {
                        merged[item.id] = item to sourceBoost
                    } else {
                        merged[item.id] = previous.first to (previous.second + sourceBoost * 0.55)
                        if (item.likes > previous.first.likes) previous.first.likes = item.likes
                        if (item.liked) previous.first.liked = true
                    }
                }
            }
            if (merged.isEmpty()) throw IllegalStateException("所有推荐榜单请求均失败")

            likedFuture?.let { f ->
                val remaining = deadline - System.nanoTime()
                val liked = if (remaining <= 0L) null
                else runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()
                liked?.forEach { item -> history.markSeen(item.id) }
            }

            val profile = history.preferenceProfile()
            val now = System.currentTimeMillis()
            return merged.values
                .asSequence()
                .filter { (item, _) ->
                    if (!skipSeen) return@filter true
                    val liked = item.liked
                    val localFavorite = history.isLocalFavorite(item.id)
                    if (liked || localFavorite) history.markSeen(item.id)
                    !liked && !localFavorite && !history.isSeen(item.id)
                }
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
                .take(80)
                .toList()
        } finally {
            pool.shutdownNow()
        }
    }

    fun close() = io.shutdownNow()

    companion object {
        private const val DEFAULT_LIST_BUDGET_MS = 9_000L
    }
}
