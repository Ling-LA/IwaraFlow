package com.ling.iwaraflow

import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * 推荐候选的来源。
 *
 * 全站约 32.5 万个视频（网页端按发布时间翻到底是第 10147 页、一页 32 条），
 * 按 App 的 36 条一页算约 9000 页，最早的投稿在 2014 年 3 月。所以“没有可推荐的视频”
 * 从来不是片源不够，而是取片源的方式不对：以前每次刷新都从各个榜单的**第 0 页**开始拿，
 * 于是每次拿到的都是同一批最新的一两百条，看过一遍就真的没了。
 *
 * 现在每一轮都在整个存档里随机抽页——靠前的页抽中概率高（新内容仍然优先），
 * 但尾巴足够长，能摸到几个月甚至几年前的视频。一共有多少页不写死：列表接口本来就回传
 * 总条数，读出来算一下就是准的（见 noteTotal），服务端不给才退回用空页去试。候选来自四处：
 *
 * - **关注作者的更新**（订阅流）：首页 + 往前翻的存量；
 * - **最新投稿**：第 0 页保证新鲜，再随机抽存档里的若干页；
 * - **热门 / 流行榜**：保证质量，同样不只看第一页；
 *
 * 排序由本地口味（作者 / 标签偏好）、点赞量、播放量、新鲜度共同决定。关注的作者
 * 不参与加分——那样会整屏都是已关注的人；它们改成按固定间隔插进结果里，见 interleave。
 */
class RecommendationEngine(
    private val api: IwaraApi,
    private val history: HistoryStore,
    private val listBudgetMs: Long = DEFAULT_LIST_BUDGET_MS,
    private val random: Random = Random()
) {
    private val io = Executors.newSingleThreadExecutor()
    private val followingIo = Executors.newSingleThreadExecutor()
    @Volatile private var followingIds: Set<String> = emptySet()
    @Volatile private var followingSyncedAt = 0L
    @Volatile private var followingRunning = false
    /** 每个榜单实际能翻到第几页：优先由服务端回报的总条数算出，见 noteTotal。 */
    private val depthCeiling = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 本次刷新在存档里抽到的页码，只用于诊断。 */
    @Volatile var sampledPages: List<Int> = emptyList()
        private set

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
        // 一轮最多同时发这么多请求：卡住的那个不能把后面排队的也拖住。
        val pool = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(listBudgetMs)
            val merged = LinkedHashMap<String, Pair<VideoItem, Double>>()
            // 哪些视频来自关注作者的订阅流——最后按位置插进结果里要用。
            val subscribed = HashSet<String>()
            val likedFuture = if (skipSeen && api.isLoggedIn()) {
                // Iwara 官方点赞只存在服务端，列表接口不一定回传 liked，
                // 所以刷新推荐时顺带同步一次点赞记录，让它们真正算作已看。
                pool.submit<List<VideoItem>> { api.getFavoriteVideosBlocking() }
            } else null

            mergeRound(pool, merged, subscribed, round = 0, deadline = deadline)
            if (merged.isEmpty()) throw IllegalStateException("所有推荐榜单请求均失败")

            likedFuture?.let { f ->
                val remaining = deadline - System.nanoTime()
                val liked = if (remaining <= 0L) null
                else runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()
                liked?.forEach { item -> history.markSeen(item.id) }
            }

            if (!skipSeen) return interleave(rank(merged), subscribed).take(MAX_RESULTS)

            var buckets = split(rank(merged))
            // 没看过的不够就再抽几轮。每轮抽的都是存档里别的页，不是往后走一页——
            // 存量有好几千页，挪一页解决不了任何问题。
            var round = 1
            while (buckets.fresh.size < MIN_FRESH_CANDIDATES && round <= MAX_EXTRA_ROUNDS) {
                val extraDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(listBudgetMs)
                val before = merged.size
                mergeRound(pool, merged, subscribed, round = round, deadline = extraDeadline)
                round += 1
                if (merged.size == before) break
                buckets = split(rank(merged))
            }

            val result = when {
                buckets.fresh.size >= MIN_FRESH_CANDIDATES -> buckets.fresh
                buckets.fresh.isNotEmpty() || buckets.watched.isNotEmpty() -> buckets.fresh + buckets.watched
                else -> buckets.all()
            }
            return interleave(result, subscribed).take(MAX_RESULTS)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 一次请求：某个榜单（或订阅流）的某一页。 */
    internal data class PageRequest(val sort: String, val page: Int, val weight: Double)

    /**
     * 这一轮抓哪几页。第 0 轮保证有各榜单的首页（新内容还是要优先，冷启动也要快），
     * 每一轮都额外在存档深处随机抽几页，所以连着刷新两次拿到的不是同一批视频。
     */
    internal fun requestsFor(round: Int): List<PageRequest> {
        val requests = ArrayList<PageRequest>()
        val loggedIn = api.isLoggedIn()
        if (round == 0) sampledPages = emptyList()
        if (round == 0) {
            if (loggedIn) requests += PageRequest(SUBSCRIBED, 0, SUBSCRIBED_WEIGHT)
            RANKINGS.forEach { (sort, weight) -> requests += PageRequest(sort, 0, weight) }
        } else if (loggedIn) {
            // 关注的作者不是只有最新那一页作品，往前翻同样是没看过的更新。
            // 放在补抽轮里，免得给冷启动再加一次请求。
            requests += PageRequest(SUBSCRIBED, samplePage(SUBSCRIBED, SUBSCRIBED_DEPTH), SUBSCRIBED_WEIGHT * 0.85)
        }
        requests += PageRequest("date", samplePage("date", ARCHIVE_DEPTH), 2.2)
        requests += PageRequest("popularity", samplePage("popularity", POPULAR_DEPTH), 2.4)
        if (round > 0) requests += PageRequest("date", samplePage("date", ARCHIVE_DEPTH), 2.0)
        return requests
    }

    /**
     * 从 1..[depth] 里抽一页，概率偏向靠前（立方分布），但尾巴够长。
     *
     * 上限取“策略上限”和“服务端说的真实页数”里小的那个，见 [ceilingFor]。
     * 按 36 条一页、近期每月约六千个视频算，167 页约合一个月，1000 页约合半年。
     */
    private fun samplePage(sort: String, depth: Int): Int {
        val ceiling = ceilingFor(sort, depth)
        val u = random.nextDouble()
        val page = (1 + u * u * u * (ceiling - 1)).toInt().coerceIn(1, ceiling)
        // 诊断信息里带上抽到的页，下次再有“推荐不出视频”的报告就能一眼看出是不是又卡在首页。
        sampledPages = sampledPages + page
        return page
    }

    private fun ceilingFor(sort: String, depth: Int) = minOf(depth, depthCeiling[sort] ?: depth)

    /**
     * 列表接口本来就回传总条数，那就不用猜也不用试——直接算出最后一页。
     * 这个值是权威的，会覆盖之前靠空页探出来的上限：存量每天都在长，只降不升是不对的。
     * 每轮的第 0 页请求都会带回它，所以一次刷新之后抽页范围就是准的。
     */
    private fun noteTotal(sort: String, total: Int) {
        if (total < 0) return
        val lastPage = ((total + PAGE_SIZE - 1) / PAGE_SIZE - 1).coerceAtLeast(1)
        depthCeiling[sort] = lastPage
    }

    /**
     * 服务端没回报总条数时的退路：抽到的页请求成功却一条都没有，说明列表没那么长，
     * 把上限压到它下面一页。只有“成功但空”才算数，超时和失败不改上限。
     */
    private fun noteEmptyPage(sort: String, page: Int) {
        if (page <= 1) return
        val lowered = (page - 1).coerceAtLeast(MIN_DEPTH)
        depthCeiling.merge(sort, lowered) { old, new -> minOf(old, new) }
    }

    private fun mergeRound(
        pool: ExecutorService,
        merged: LinkedHashMap<String, Pair<VideoItem, Double>>,
        subscribed: MutableSet<String>,
        round: Int,
        deadline: Long
    ) {
        val sources = requestsFor(round).map { request ->
            request to pool.submit<VideoListPage> {
                if (request.sort == SUBSCRIBED) api.getSubscribedVideoPageBlocking(request.page, PAGE_SIZE)
                else api.getVideoListPageBlocking(request.sort, page = request.page, limit = PAGE_SIZE)
            }
        }
        // One stalled ranking endpoint used to hold the whole cold start; a request that
        // misses the budget is treated like the failures this merge already tolerates.
        sources.forEach { (request, future) ->
            val remaining = deadline - System.nanoTime()
            val response = (if (remaining <= 0L) null
            else runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()) ?: return@forEach
            noteTotal(request.sort, response.total)
            val videos = response.videos
            if (videos.isEmpty()) { noteEmptyPage(request.sort, request.page); return@forEach }
            if (request.sort == SUBSCRIBED) videos.forEach { subscribed += it.id }
            videos.forEachIndexed { index, item ->
                val rankBonus = (PAGE_SIZE - index).coerceAtLeast(0) / PAGE_SIZE.toDouble()
                // 后面几轮补进来的稍微让一让，但不按页码衰减——存档深处的页不比首页差，
                // 只是更老，老不老交给下面的新鲜度去评。
                val roundDecay = 1.0 / (1.0 + round * 0.18)
                val previous = merged[item.id]
                val sourceBoost = (request.weight + rankBonus * 0.8) * roundDecay
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
                // 新片加分，但不能是断崖：候选现在有一大半来自存档，
                // 按原来的曲线，一个月前的视频就已经被压到几乎没有分了。
                val freshness = 1.6 / (1.0 + ageDays / 30.0)
                val affinity = profile.score(item).coerceAtMost(8.0) * 0.38
                val stableJitter = ((item.id.hashCode().toLong() and 0xffff) / 65535.0) * 0.15
                // 关注的作者不在这里加分——加分会让他们整片霸榜。改成排完序之后按位置插入。
                item to (sourceScore + likeScore + viewScore + freshness + affinity + stableJitter)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    /**
     * 关注作者的更新按**位置**插进结果里，而不是靠权重往上挤。
     *
     * 以前订阅流权重最高、关注的作者还额外加分，结果整屏刷出来全是已经关注的人——
     * 推荐页就失去意义了。现在每 [DISCOVERY_RUN] 条没关注的内容配一条关注作者的，
     * 占比固定，跟打分高低无关。
     *
     * 插在这一组里的哪个位置是随机的：可能是头一条，也可能压到最后一条。固定插在
     * 组尾会形成一眼看得出来的节奏，隔几条就知道下一条是关注的作者。
     * 哪一条关注作者的更新排在前面，仍然由打分决定。
     */
    private fun interleave(ranked: List<VideoItem>, subscribed: Set<String>): List<VideoItem> {
        val followedAuthors = followingIds
        fun isFollowed(item: VideoItem) =
            item.id in subscribed || (item.authorId.isNotBlank() && item.authorId in followedAuthors)

        val followed = ArrayDeque(ranked.filter(::isFollowed))
        val discovery = ArrayDeque(ranked.filterNot(::isFollowed))
        if (followed.isEmpty() || discovery.isEmpty()) return ranked

        val out = ArrayList<VideoItem>(ranked.size)
        while (discovery.isNotEmpty() && followed.isNotEmpty()) {
            val block = ArrayList<VideoItem>(DISCOVERY_RUN + 1)
            repeat(DISCOVERY_RUN) { if (discovery.isNotEmpty()) block += discovery.removeFirst() }
            block.add(random.nextInt(block.size + 1), followed.removeFirst())
            out += block
        }
        out += discovery
        out += followed
        return out
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
        /** 首轮一定会取的榜单首页。 */
        private val RANKINGS = listOf(
            "trending" to 3.0,
            "popularity" to 2.6,
            "date" to 2.4
        )
        internal const val SUBSCRIBED = "__subscribed__"
        /** 订阅流只是候选来源之一，不再比别的榜单重——占比由插入间隔决定。 */
        private const val SUBSCRIBED_WEIGHT = 2.8
        /** 每这么多条“发现”配一条关注作者的更新，插在这一组里的随机位置。 */
        internal const val DISCOVERY_RUN = 5
        private const val PAGE_SIZE = 36
        private const val MAX_RESULTS = 80
        /** 首轮 6 个请求 + 点赞同步，留一点余量。 */
        private const val MAX_PARALLEL_REQUESTS = 8
        /**
         * 随机抽页的初始上限，给得比实际存量宽——抽空了会自动往回收。
         *
         * 真实页数由服务端回报的总条数算出（noteTotal），这里只是第一次请求回来之前的兜底值。
         * 实测：网页端按发布时间翻到底是第 10147 页、一页 32 条，即全站约 32.5 万个视频，
         * 换成 App 的 36 条一页约 9000 页；给 11000 是留出余量。
         */
        internal const val ARCHIVE_DEPTH = 11000
        /**
         * 流行榜排的是同一批视频，页数也一样多，但越往后越是没人看的作品，
         * 再深就没有质量可言了，所以不跟着放到底。
         */
        internal const val POPULAR_DEPTH = 3000
        internal const val SUBSCRIBED_DEPTH = 200
        /** 上限再怎么收也不低于这里，免得一次异常的空页把抽页缩回首页附近。 */
        internal const val MIN_DEPTH = 50
        /** 低于这个数量就认为“排除已看”把候选榨干了，需要再抽几轮或者放宽。 */
        const val MIN_FRESH_CANDIDATES = 12
        /** 最多再抽几轮找没看过的视频。 */
        const val MAX_EXTRA_ROUNDS = 3
        private const val MAX_FOLLOWING_PAGES = 40
        private const val FOLLOWING_TTL_MS = 6L * 60L * 60L * 1000L
        private const val DEFAULT_LIST_BUDGET_MS = 9_000L
    }
}
