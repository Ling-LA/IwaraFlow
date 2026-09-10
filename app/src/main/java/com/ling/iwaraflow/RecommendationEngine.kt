package com.ling.iwaraflow

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * 推荐候选的来源。
 *
 * 以前只取 trending / popularity / likes / views 各一页：后两个是全站历史总榜，
 * 基本是一组固定不变的老视频，前两个轮换也很慢，所以候选池每次都是同一批约 100 条，
 * 看得多的账号很快就没得可推。现在候选来自三处：
 *
 * - **关注作者的更新**（订阅流）：关注了一百多个作者就该有一百多个作者的新作品；
 * - **最新投稿**：Iwara 每天几十条新视频，往后翻页等于取之不尽；
 * - **热门榜单**：仍然保留，用来保证质量和轮换。
 *
 * 排序仍然由本地口味（作者 / 标签偏好）、点赞量、播放量、新鲜度共同决定，
 * 关注的作者再额外加分。
 */
class RecommendationEngine(
    private val api: IwaraApi,
    private val history: HistoryStore,
    private val listBudgetMs: Long = DEFAULT_LIST_BUDGET_MS
) {
    private val io = Executors.newSingleThreadExecutor()
    private val followingIo = Executors.newSingleThreadExecutor()
    @Volatile private var followingIds: Set<String> = emptySet()
    @Volatile private var followingSyncedAt = 0L
    @Volatile private var followingRunning = false

    fun load(skipSeen: Boolean, callback: (Result<List<VideoItem>>) -> Unit) {
        io.execute {
            refreshFollowingInBackground()
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
        val pool = Executors.newFixedThreadPool(RANKINGS.size + 2)
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
            // 没看过的不够就继续往后翻。翻的是订阅流和最新投稿，它们是按时间排的，
            // 越往后越是没看过的内容，而不是同一批总榜老视频。
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
        pool: ExecutorService,
        merged: LinkedHashMap<String, Pair<VideoItem, Double>>,
        page: Int,
        deadline: Long
    ) {
        val sources = ArrayList<Pair<Double, java.util.concurrent.Future<List<VideoItem>>>>()
        if (api.isLoggedIn()) {
            sources += SUBSCRIBED_WEIGHT to
                pool.submit<List<VideoItem>> { api.getSubscribedVideosBlocking(page, PAGE_SIZE) }
        }
        RANKINGS.forEach { (sort, weight) ->
            // 总榜类的榜单只取第一页：往后翻还是同一批老视频，白费一次请求。
            if (page == 0 || sort in DEEP_SORTS) {
                sources += weight to
                    pool.submit<List<VideoItem>> { api.getVideosBlocking(sort, page = page, limit = PAGE_SIZE) }
            }
        }
        // One stalled ranking endpoint used to hold the whole cold start; a request that
        // misses the budget is treated like the failures this merge already tolerates.
        sources.forEach { (baseWeight, future) ->
            val remaining = deadline - System.nanoTime()
            val videos = (if (remaining <= 0L) null
            else runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()) ?: return@forEach
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
        val followed = followingIds
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
                // 关注了就是明确的口味信号，比任何榜单名次都直接。
                val followBonus = if (item.authorId.isNotBlank() && item.authorId in followed) FOLLOW_BONUS else 0.0
                val stableJitter = ((item.id.hashCode().toLong() and 0xffff) / 65535.0) * 0.15
                item to (sourceScore + likeScore + viewScore + freshness + affinity + followBonus + stableJitter)
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

    /** 关注列表只用来加分，慢一点没关系，别挡着首屏。 */
    private fun refreshFollowingInBackground() {
        if (followingRunning || !api.isLoggedIn()) return
        if (System.currentTimeMillis() - followingSyncedAt < FOLLOWING_TTL_MS && followingIds.isNotEmpty()) return
        followingRunning = true
        runCatching {
            followingIo.execute {
                try {
                    val me = runCatching { api.getCurrentUserBlocking() }.getOrNull()
                    val ids = LinkedHashSet<String>()
                    if (me != null && me.id.isNotBlank()) {
                        var page = 0
                        while (page < MAX_FOLLOWING_PAGES) {
                            val result = runCatching { api.getFollowingPageBlocking(me.id, page) }.getOrNull() ?: break
                            result.users.forEach { author -> if (author.id.isNotBlank()) ids += author.id }
                            if (!result.hasMore) break
                            page += 1
                        }
                    }
                    if (ids.isNotEmpty()) {
                        followingIds = ids
                        followingSyncedAt = System.currentTimeMillis()
                    }
                } finally {
                    followingRunning = false
                }
            }
        }.onFailure { followingRunning = false }
    }

    fun close() {
        io.shutdownNow()
        followingIo.shutdownNow()
    }

    companion object {
        /** 榜单来源。总榜类只取第一页，按时间排的可以一直往后翻。 */
        private val RANKINGS = listOf(
            "trending" to 3.0,
            "popularity" to 2.6,
            "date" to 2.4
        )
        private val DEEP_SORTS = setOf("date")
        /** 关注作者的更新权重最高：这是用户自己选的作者。 */
        private const val SUBSCRIBED_WEIGHT = 3.6
        private const val FOLLOW_BONUS = 1.4
        private const val PAGE_SIZE = 36
        private const val MAX_RESULTS = 80
        /** 低于这个数量就认为“排除已看”把候选榨干了，需要翻页或者放宽。 */
        const val MIN_FRESH_CANDIDATES = 12
        /** 最多再往后翻几页找没看过的视频。 */
        const val MAX_EXTRA_PAGES = 4
        private const val MAX_FOLLOWING_PAGES = 40
        private const val FOLLOWING_TTL_MS = 6L * 60L * 60L * 1000L
        private const val DEFAULT_LIST_BUDGET_MS = 9_000L
    }
}
