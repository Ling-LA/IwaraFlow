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

    /**
     * 每多少条推荐里穿插一条老片，0 = 不穿插。引擎自己的默认是 0，页面按设置写进来。
     *
     * 抽页已经保证新片源源不断，但反过来也有问题：短时间刷得多，新的很快刷完，
     * 后面越刷越旧；刷得少，又一直碰不到历史上那些点赞很高的老作品。
     * 所以单独开一条“老片”流——按总点赞排序、往后翻到几十上百页去抽，
     * 那里全是有年头又受欢迎的作品——按固定间隔、随机位置塞进结果里。
     */
    @Volatile var classicsEvery: Int = 0

    /** 最近一次榜单请求失败的原因，只在全部失败时拿来充实错误信息。 */
    @Volatile private var lastFailure: Throwable? = null

    /** 本次刷新在存档里抽到的页码，只用于诊断。 */
    @Volatile var sampledPages: List<Int> = emptyList()
        private set

    /** 本次刷新用到的个性化召回（标签 / 作者）和“当月点赞榜”，只用于诊断。 */
    @Volatile var recallNote: String = ""
        private set

    /** 服务端支不支持按月过滤：第一次拿到明显是全站量的总数就关掉，不再白请求。 */
    @Volatile private var monthFilterSupported = true

    /** 这一轮的口味画像，抽候选和打分共用一份。 */
    @Volatile private var profile: PreferenceProfile = PreferenceProfile(emptyMap(), emptyMap())

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
            // 测试里的 mock 可能给 null，兜一下。
            profile = runCatching { history.preferenceProfile() }.getOrNull() ?: PreferenceProfile(emptyMap(), emptyMap())
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(listBudgetMs)
            val merged = LinkedHashMap<String, Pair<VideoItem, Double>>()
            // 哪些视频来自关注作者的订阅流——最后按位置插进结果里要用。
            val subscribed = HashSet<String>()
            val likedFuture = if (skipSeen && api.isLoggedIn()) {
                // Iwara 官方点赞只存在服务端，列表接口不一定回传 liked，
                // 所以刷新推荐时顺带同步一次点赞记录，让它们真正算作已看。
                pool.submit<List<VideoItem>> { api.getFavoriteVideosBlocking() }
            } else null

            val classicsFuture = if (classicsEvery > 0) pool.submit<List<VideoItem>> { fetchClassics() } else null

            mergeRound(pool, merged, subscribed, round = 0, deadline = deadline)
            if (merged.isEmpty()) {
                // 把底下那个真正的错误带出来。诊断里只写“全部失败”等于什么都没说：
                // 两份国内机器的报告就是靠 date 榜单单独记的 "Connection reset" 才看出是网络被掐。
                val cause = lastFailure?.message?.takeIf { it.isNotBlank() }
                throw IllegalStateException(if (cause != null) "所有推荐榜单请求均失败（$cause）" else "所有推荐榜单请求均失败")
            }

            likedFuture?.let { f ->
                val remaining = deadline - System.nanoTime()
                val liked = if (remaining <= 0L) null
                else runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()
                liked?.forEach { item -> history.markSeen(item.id) }
            }

            if (!skipSeen) {
                val classics = classicsFuture?.let { awaitClassics(it, deadline, skipSeen = false) }.orEmpty()
                return assemble(rank(merged), subscribed, classics)
            }

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
            val classics = classicsFuture?.let { awaitClassics(it, deadline, skipSeen = true) }.orEmpty()
            return assemble(result, subscribed, classics)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 把候选装配成最终的推荐流：**主体只放近期视频，老视频只按设置的间隔穿插**。
     *
     * 抽页会抽到存档深处，候选里混着不少发布好几年的视频；它们点赞、播放量都高，
     * 打分后经常排在前面，结果整屏都是老片，设置里的“每 N 条一条老片”形同虚设。
     * 所以先按发布时间分两堆：半年内的（以及接口没给时间的）留在主流里，更老的并进老片池，
     * 和专门抓的老片一起，每 [classicsEvery] 条塞一条；关了穿插就不出现。
     * 近期的实在不够（网络差、只抓到深页）时才用老的补到 [MIN_RECENT_FEED] 条，不给空页。
     */
    internal fun assemble(ranked: List<VideoItem>, subscribed: Set<String>, classics: List<VideoItem>): List<VideoItem> {
        val (recent, aged) = splitByAge(ranked, System.currentTimeMillis())
        val feed = if (recent.size >= MIN_RECENT_FEED) recent else recent + aged.take(MIN_RECENT_FEED - recent.size)
        val pool = if (classicsEvery > 0) classics + aged else emptyList()
        return weaveClassics(interleave(spreadAuthors(feed), subscribed), pool).take(MAX_RESULTS)
    }

    /**
     * 多样性：同一个作者在任意连续 [AUTHOR_WINDOW] + 1 条里最多出现一次。
     * 打分高的作者会整段霸屏，看起来像作者页；把它的其余作品往后挪，顺序尽量不动。
     */
    internal fun spreadAuthors(items: List<VideoItem>, window: Int = AUTHOR_WINDOW): List<VideoItem> {
        if (items.size <= 2) return items
        val pending = ArrayDeque(items)
        val out = ArrayList<VideoItem>(items.size)
        while (pending.isNotEmpty()) {
            val recent = out.takeLast(window)
            val pick = pending.firstOrNull { candidate ->
                recent.none { it.author.equals(candidate.author, ignoreCase = true) }
            } ?: pending.first()
            pending.remove(pick)
            out += pick
        }
        return out
    }

    /**
     * 视频质量分：**平滑点赞率**为主，绝对热度为辅。
     *
     * 只看点赞总数，老视频和常年霸榜的天然占优；只看裸点赞率，10 次播放 5 个赞会压过
     * 十万播放一万赞。所以点赞率按贝叶斯平滑：先假设每条视频有 [QUALITY_PRIOR_VIEWS] 次
     * 播放、平均点赞率 [QUALITY_PRIOR_RATE]，播放量越大真实数据占比越高。
     * 结果相对平均水平取对数，再加一点对数热度，量级和原来的“点赞 + 播放”接近。
     */
    internal fun qualityScore(likes: Int, views: Int): Double {
        val l = likes.coerceAtLeast(0).toDouble()
        val v = views.coerceAtLeast(0).toDouble()
        val rate = (l + QUALITY_PRIOR_VIEWS * QUALITY_PRIOR_RATE) / (v + QUALITY_PRIOR_VIEWS)
        val relative = ln(rate / QUALITY_PRIOR_RATE).coerceIn(-1.5, 2.5) * 1.6
        val heat = ln(l + 1.0) * 0.5 + ln(v + 1.0) * 0.12
        return relative + heat
    }

    /** 按月点赞榜要抽的月份：本月和上月（`yyyy-MM`）。 */
    internal fun monthsToSample(now: Long = System.currentTimeMillis()): List<String> {
        val format = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.ROOT)
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.ROOT)
        calendar.timeInMillis = now
        val current = format.also { it.timeZone = calendar.timeZone }.format(calendar.time)
        calendar.add(java.util.Calendar.MONTH, -1)
        return listOf(current, format.format(calendar.time))
    }

    /** 按发布时间分成（近期，老片）两堆，顺序不变；没有发布时间的算近期。 */
    internal fun splitByAge(items: List<VideoItem>, now: Long): Pair<List<VideoItem>, List<VideoItem>> {
        val cutoff = now - CLASSIC_MIN_AGE_MS
        return items.partition { it.createdAt <= 0L || it.createdAt >= cutoff }
    }

    /**
     * 老片候选：按总点赞排序的列表，跳过最前面那几页（那是常年霸榜的），
     * 在后面均匀抽一页。这一页里的视频点赞都不低，而且大多有些年头。
     */
    private fun fetchClassics(): List<VideoItem> {
        val ceiling = ceilingFor(CLASSICS_SORT, CLASSICS_DEPTH)
        val page = if (ceiling <= CLASSICS_MIN_PAGE) ceiling
        else CLASSICS_MIN_PAGE + random.nextInt(ceiling - CLASSICS_MIN_PAGE + 1)
        sampledPages = sampledPages + page
        val response = api.getVideoListPageBlocking(CLASSICS_SORT, page = page, limit = PAGE_SIZE)
        noteTotal(CLASSICS_SORT, response.total)
        if (response.videos.isEmpty()) noteEmptyPage(CLASSICS_SORT, page)
        return response.videos
    }

    /** 老片流失败或超时就当没有，不能拖住首屏。 */
    private fun awaitClassics(
        future: java.util.concurrent.Future<List<VideoItem>>,
        deadline: Long,
        skipSeen: Boolean
    ): List<VideoItem> {
        val remaining = deadline - System.nanoTime()
        val videos = (if (remaining <= 0L) null
        else runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()) ?: return emptyList()
        val cutoff = System.currentTimeMillis() - CLASSIC_MIN_AGE_MS
        return videos.filter { item ->
            // 发布时间不明（接口没给）就不按年龄筛：抽到的页本身已经够靠后了。
            val oldEnough = item.createdAt <= 0L || item.createdAt < cutoff
            oldEnough && !item.liked && !history.isLocalFavorite(item.id) && !(skipSeen && history.isSeen(item.id))
        }
    }

    /**
     * 每 [classicsEvery] 条里塞一条老片，塞在这一组里的随机位置——可能是第一条，
     * 也可能是最后一条。老片不够时有多少塞多少，塞完就照常。
     */
    internal fun weaveClassics(feed: List<VideoItem>, classics: List<VideoItem>): List<VideoItem> {
        val every = classicsEvery
        if (every <= 0 || classics.isEmpty() || feed.isEmpty()) return feed
        val taken = feed.map { it.id }.toHashSet()
        val pool = ArrayDeque(classics.filter { taken.add(it.id) })
        if (pool.isEmpty()) return feed
        val out = ArrayList<VideoItem>(feed.size + pool.size)
        val rest = ArrayDeque(feed)
        while (rest.isNotEmpty()) {
            val block = ArrayList<VideoItem>(every + 1)
            repeat(every) { if (rest.isNotEmpty()) block += rest.removeFirst() }
            if (pool.isNotEmpty()) block.add(random.nextInt(block.size + 1), pool.removeFirst())
            out += block
        }
        return out
    }

    /**
     * 一次请求：某个榜单（或订阅流 / 标签召回 / 作者召回）的某一页。
     * [sort] 以 [TAG_PREFIX] / [AUTHOR_PREFIX] 开头时是个性化召回；[month] 非空时是按月点赞榜。
     */
    internal data class PageRequest(val sort: String, val page: Int, val weight: Double, val month: String = "")

    /**
     * 这一轮抓哪几页。第 0 轮保证有各榜单的首页（新内容还是要优先，冷启动也要快），
     * 每一轮都额外在存档深处随机抽几页，所以连着刷新两次拿到的不是同一批视频。
     *
     * 第 0 轮还带上：本月 / 上月的点赞榜（近期里真正受欢迎的作品，不然近期候选大多是
     * 按时间随机抽到的、点赞很少的视频）、热门榜首页之外再抽一页，以及按画像做的
     * 个性化召回——权重最高的标签和作者各拉一页。
     */
    internal fun requestsFor(round: Int): List<PageRequest> {
        val requests = ArrayList<PageRequest>()
        val loggedIn = api.isLoggedIn()
        if (round == 0) { sampledPages = emptyList(); recallNote = "" }
        if (round == 0) {
            if (loggedIn) requests += PageRequest(SUBSCRIBED, 0, SUBSCRIBED_WEIGHT)
            RANKINGS.forEach { (sort, weight) -> requests += PageRequest(sort, 0, weight) }
            requests += PageRequest("trending", samplePage("trending", TRENDING_DEPTH), 2.6)
            if (monthFilterSupported) {
                monthsToSample().forEachIndexed { index, month ->
                    requests += PageRequest("likes", random.nextInt(MONTH_TOP_PAGES), if (index == 0) 2.8 else 2.4, month = month)
                }
            }
            val tags = profile.topTags(RECALL_TAGS, RECALL_TAG_MIN_WEIGHT)
            val authors = profile.topAuthorIds(RECALL_AUTHORS, RECALL_AUTHOR_MIN_WEIGHT)
            tags.forEach { requests += PageRequest(TAG_PREFIX + it, random.nextInt(2), 2.4) }
            authors.forEach { requests += PageRequest(AUTHOR_PREFIX + it, 0, 2.3) }
            if (tags.isNotEmpty() || authors.isNotEmpty()) {
                recallNote = "标签 ${tags.joinToString(",")} 作者 ${authors.size} 位"
            }
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
                when {
                    request.sort == SUBSCRIBED -> api.getSubscribedVideoPageBlocking(request.page, PAGE_SIZE)
                    request.month.isNotBlank() -> api.getMonthTopBlocking(request.month, request.page, PAGE_SIZE)
                    request.sort.startsWith(TAG_PREFIX) ->
                        VideoListPage(api.getVideosByTagBlocking(request.sort.removePrefix(TAG_PREFIX), request.page, PAGE_SIZE), -1)
                    request.sort.startsWith(AUTHOR_PREFIX) ->
                        VideoListPage(api.getAuthorVideosBlocking(request.sort.removePrefix(AUTHOR_PREFIX), request.page, PAGE_SIZE), -1)
                    else -> api.getVideoListPageBlocking(request.sort, page = request.page, limit = PAGE_SIZE)
                }
            }
        }
        // One stalled ranking endpoint used to hold the whole cold start; a request that
        // misses the budget is treated like the failures this merge already tolerates.
        sources.forEach { (request, future) ->
            val remaining = deadline - System.nanoTime()
            val response = (if (remaining <= 0L) null
            else runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }
                .onFailure { lastFailure = (it as? java.util.concurrent.ExecutionException)?.cause ?: it }
                .getOrNull()) ?: return@forEach
            if (request.month.isNotBlank()) {
                // 过滤没生效时回来的是全站总榜（几十万条），那批常年霸榜的老片不能混进来。
                if (response.total < 0 || response.total > MONTH_FILTER_MAX_TOTAL) { monthFilterSupported = false; return@forEach }
            } else {
                noteTotal(request.sort, response.total)
            }
            val videos = response.videos
            if (videos.isEmpty()) { if (request.month.isBlank()) noteEmptyPage(request.sort, request.page); return@forEach }
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
        val taste = profile
        val now = System.currentTimeMillis()
        return merged.values
            .asSequence()
            .map { (item, sourceScore) ->
                // 站方数据：平滑点赞率 + 热度，见 qualityScore。
                val quality = qualityScore(item.likes, item.views)
                val ageDays = if (item.createdAt > 0L) {
                    ((now - item.createdAt).coerceAtLeast(0L) / 86_400_000.0)
                } else 30.0
                // 新片加分，但不能是断崖：候选现在有一大半来自存档，
                // 按原来的曲线，一个月前的视频就已经被压到几乎没有分了。
                val freshness = 1.6 / (1.0 + ageDays / 30.0)
                // 本地画像：可正可负（很快划走的作者 / 标签会被压下去）。
                val affinity = taste.score(item).coerceIn(-6.0, 8.0) * 0.38
                val stableJitter = ((item.id.hashCode().toLong() and 0xffff) / 65535.0) * 0.15
                // 关注的作者不在这里加分——加分会让他们整片霸榜。改成排完序之后按位置插入。
                item to (sourceScore + quality + freshness + affinity + stableJitter)
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
        // 一边是空的就没得混，原样返回：没关注任何作者、关注的作者最近没更新、
        // 没登录拿不到订阅流，都会走到这里。这是正常情况，不是错误。
        if (followed.isEmpty() || discovery.isEmpty()) return ranked

        // 关注的更新不够按 1:[DISCOVERY_RUN] 铺满时，把间隔拉大，让这几条散布开，
        // 而不是全挤在开头几屏之后就再也不见。够铺满时这里就等于 DISCOVERY_RUN。
        //
        // 间隔按**最终会露面的那一段**算：结果最后要截到 MAX_RESULTS 条，
        // 按整个候选池去摊的话，只关注了一两个作者的账号会把那几条摊到列表末尾，正好被截掉。
        val window = minOf(discovery.size + followed.size, MAX_RESULTS)
        val run = maxOf(DISCOVERY_RUN, window / followed.size - 1)

        val out = ArrayList<VideoItem>(ranked.size)
        while (discovery.isNotEmpty() && followed.isNotEmpty()) {
            val block = ArrayList<VideoItem>(run + 1)
            repeat(run) { if (discovery.isNotEmpty()) block += discovery.removeFirst() }
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
        /** 个性化召回的请求：标签 / 作者 id 接在前缀后面。 */
        internal const val TAG_PREFIX = "__tag__:"
        internal const val AUTHOR_PREFIX = "__author__:"
        internal const val RECALL_TAGS = 2
        internal const val RECALL_AUTHORS = 1
        /** 标签 / 作者权重至少到这里才值得专门拉一页（一次本地点赞给作者 2.0、给每个标签 0.9）。 */
        internal const val RECALL_TAG_MIN_WEIGHT = 1.5
        internal const val RECALL_AUTHOR_MIN_WEIGHT = 2.5
        /** 热门榜除了首页再抽一页的范围。 */
        internal const val TRENDING_DEPTH = 6
        /** 按月点赞榜在前几页里抽。 */
        internal const val MONTH_TOP_PAGES = 3
        /** 按月过滤生效时总数是一个月的量（不到一万）；超过这个数就是全站总榜。 */
        internal const val MONTH_FILTER_MAX_TOTAL = 60_000
        /** 质量分的先验：假设每条视频先有 400 次播放、5% 的点赞率。 */
        internal const val QUALITY_PRIOR_VIEWS = 400.0
        internal const val QUALITY_PRIOR_RATE = 0.05
        /** 同一作者两条视频之间至少隔这么多条。 */
        internal const val AUTHOR_WINDOW = 4
        /** 订阅流只是候选来源之一，不再比别的榜单重——占比由插入间隔决定。 */
        private const val SUBSCRIBED_WEIGHT = 2.8
        /** 每这么多条“发现”配一条关注作者的更新，插在这一组里的随机位置。 */
        internal const val DISCOVERY_RUN = 5
        /** 老片流：按总点赞排序，跳过最前面常年霸榜的几页，在后面均匀抽。 */
        internal const val CLASSICS_SORT = "likes"
        internal const val CLASSICS_MIN_PAGE = 8
        internal const val CLASSICS_DEPTH = 300
        /** 发布不满半年的不算老片；反过来，超过半年的也不进推荐流主体，只按间隔穿插。 */
        internal const val CLASSIC_MIN_AGE_MS = 180L * 24 * 60 * 60 * 1000
        /** 近期视频至少凑到这么多条，不够才拿老的补。 */
        internal const val MIN_RECENT_FEED = 24
        /** 设置里的默认：每 12 条穿插一条。 */
        const val DEFAULT_CLASSICS_EVERY = 12
        private const val PAGE_SIZE = 36
        private const val MAX_RESULTS = 80
        /** 首轮榜单 + 按月榜 + 召回约 12 个请求，再加点赞同步和老片，留一点余量。 */
        private const val MAX_PARALLEL_REQUESTS = 16
        /**
         * 最新投稿的抽页上限。推荐流主体只要半年内的视频（更老的转进老片池，见 assemble），
         * 按每月约 6000 条、36 条一页算，半年约 1000 页；再往深抽回来的也只会被分流，白费一次请求。
         * 真实页数由服务端回报的总条数算出（noteTotal），取两者中小的。
         */
        internal const val ARCHIVE_DEPTH = 1100
        /**
         * 流行榜排的是同一批视频，越往后越老、越没人看；主体只要近期的，抽前几十页就够。
         */
        internal const val POPULAR_DEPTH = 60
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
