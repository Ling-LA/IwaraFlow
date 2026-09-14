package com.ling.iwaraflow

import java.util.Random
import kotlin.math.ln

/**
 * 推荐的**排序**这一半：打分、多样性、探索位、老片穿插、关注插入、最终装配。
 *
 * 召回（抽哪些页、发请求、翻页深度、关注名单）留在 [RecommendationEngine] 里。
 * 这里一个网络请求都不发、也不碰数据库，全是纯计算——照着一份候选和一份画像，
 * 算出最终该怎么排。拆开之后这半边可以单独测，改排序也不用担心动到召回。
 */
class RecommendationRanker(private val random: Random = Random()) {

    /** 这一轮的口味画像，抽候选和打分共用一份。 */
    @Volatile var profile: PreferenceProfile = PreferenceProfile(emptyMap(), emptyMap())

    /** 每多少条推荐里穿插一条老片，0 = 不穿插。页面按设置写进来。 */
    @Volatile var classicsEvery: Int = 0

    /**
     * 候选的完整信息，按视频 id 记着：来源分、命中的标签 / 作者、是不是关注 / 老片 / 探索位。
     * 实时重排靠它在**原来的完整得分**上更新口味，而不是把第一次排序的信息全扔掉；
     * 「为什么推荐给我」也读这里。
     */
    private val candidates = java.util.concurrent.ConcurrentHashMap<String, RecommendationCandidate>()

    /** 「为什么推荐给我」：这条视频是怎么被选出来的。没记录就返回 null。 */
    fun reasonFor(videoId: String): String? = candidates[videoId]?.reason()

    fun candidateFor(videoId: String): RecommendationCandidate? = candidates[videoId]

    /** 记下一条候选（老片池、官方榜单回退这些不走 [rank] 的路径用）。 */
    fun remember(item: VideoItem, configure: (RecommendationCandidate) -> Unit) {
        val candidate = candidates.getOrPut(item.id) { RecommendationCandidate(item) }
        if (candidate.baseQuality == 0.0) candidate.baseQuality = qualityScore(item.likes, item.views)
        configure(candidate)
    }

    /**
     * 打分排序。分数由来源、站方质量、新鲜度和本地画像合成，见 [scoreOf]；
     * 每条候选连同来源分一起记进 [candidates]，之后的实时重排才接得上同一套口径。
     * 关注的作者不在这里加分——加分会让他们整片霸榜，改成排完序之后按位置插入。
     */
    fun rank(merged: Map<String, RecommendationCandidate>): List<VideoItem> {
        val taste = profile
        val now = System.currentTimeMillis()
        if (candidates.size > MAX_TRACKED_CANDIDATES) candidates.clear()
        merged.values.forEach { candidate ->
            // 站方数据：平滑点赞率 + 热度，见 qualityScore。
            candidate.baseQuality = qualityScore(candidate.item.likes, candidate.item.views)
            candidates[candidate.item.id] = candidate
        }
        return merged.values.map { it.item }.sortedByDescending { scoreOf(it, taste, now) }
    }

    /**
     * 重排：和 [rank] 是同一套口径，**来源分照旧算进去**。
     * 以前重排只算质量 + 新鲜度 + 画像，于是用户点一次赞，整条队列就换了一套评分体系。
     */
    fun rerank(items: List<VideoItem>, taste: PreferenceProfile, now: Long): List<VideoItem> =
        diversify(spreadAuthors(items.sortedByDescending { scoreOf(it, taste, now) }))

    /** 一条候选此刻的得分：来源 + 质量 + 新鲜度 + 画像 + 稳定抖动。 */
    fun scoreOf(item: VideoItem, taste: PreferenceProfile, now: Long): Double {
        val ageDays = if (item.createdAt > 0L) ((now - item.createdAt).coerceAtLeast(0L) / 86_400_000.0) else 30.0
        val known = candidates[item.id]
        val quality = known?.baseQuality ?: qualityScore(item.likes, item.views)
        return (known?.sourceScore ?: 0.0) + quality + 1.6 / (1.0 + ageDays / 30.0) +
            taste.score(item).coerceIn(-6.0, 8.0) * 0.38 +
            ((item.id.hashCode().toLong() and 0xffff) / 65535.0) * 0.15
    }

    /**
     * 把候选装配成最终的推荐流：**主体只放近期和中期视频，更老的只按设置的间隔穿插**。
     *
     * 抽页会抽到存档深处，候选里混着不少发布好几年的视频；它们点赞、播放量都高，
     * 打分后经常排在前面，结果整屏都是老片，设置里的“每 N 条一条老片”形同虚设。
     * 所以先按发布时间分两堆，更老的并进老片池，和专门抓的老片一起按间隔塞；
     * 关了穿插就不出现。近期的实在不够时才用老的补到 [MIN_RECENT_FEED] 条，不给空页。
     */
    fun assemble(
        ranked: List<VideoItem>,
        subscribed: Set<String>,
        followed: Set<String>,
        classics: List<VideoItem>
    ): List<VideoItem> {
        val (recent, aged) = splitByAge(ranked, System.currentTimeMillis())
        val feed = if (recent.size >= MIN_RECENT_FEED) recent else recent + aged.take(MIN_RECENT_FEED - recent.size)
        // 老片池也按“质量 × 口味”排一遍：穿插进来的应该是“你可能喜欢的经典”，不是全站经典。
        val pool = if (classicsEvery > 0) rankClassics(classics + aged) else emptyList()
        return weaveClassics(
            interleave(exploreDisliked(diversify(spreadAuthors(feed))), subscribed, followed),
            pool
        ).take(MAX_RESULTS)
    }

    /**
     * 老片的个性化排序：质量 × 画像，**不算新鲜度**（老片本来就老，算了等于按年龄再罚一次）。
     * 排完同样做一次多样性重排，免得穿插位连着几条都是同一类内容。
     */
    fun rankClassics(items: List<VideoItem>): List<VideoItem> {
        if (items.size <= 1) return items
        val taste = profile
        return diversify(items.sortedByDescending { item ->
            qualityScore(item.likes, item.views) + taste.score(item).coerceIn(-6.0, 8.0) * CLASSIC_TASTE_WEIGHT
        })
    }

    /**
     * 两条视频有多像：同一个作者直接算最像，否则看标签的 Jaccard 相似度。
     * 不用任何模型，够用来判断“又是同一种内容”。
     */
    fun similarity(a: VideoItem, b: VideoItem): Double {
        // 有 id 就按 id 认人；只有名字时才比名字（不同 id 同名的是两个人）。
        if (a.authorId.isNotBlank() && b.authorId.isNotBlank()) {
            if (a.authorId == b.authorId) return 1.0
        } else if (a.author.isNotBlank() && a.author.equals(b.author, ignoreCase = true)) return 1.0
        val x = a.tags.mapTo(HashSet()) { it.lowercase() }
        val y = b.tags.mapTo(HashSet()) { it.lowercase() }
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val overlap = x.count { it in y }.toDouble()
        val union = x.size + y.size - overlap
        return if (union <= 0.0) 0.0 else overlap / union
    }

    /**
     * 轻量 MMR 多样性重排：**最终价值 = 原来的名次 − 与最近几条的相似度惩罚**。
     *
     * 打散作者只解决了“连着五条同一个人”，解决不了“二十条不同作者、但全是同一种内容”。
     * 这里每次只在最前面的 [DIVERSITY_LOOKAHEAD] 条里挑一条和最近 [SIMILARITY_WINDOW] 条最不像的，
     * 挑不动就还是原来那条——没有相似内容时结果和原顺序完全一致。
     */
    fun diversify(items: List<VideoItem>, window: Int = SIMILARITY_WINDOW): List<VideoItem> {
        if (items.size <= 2) return items
        val pending = ArrayList(items)
        val out = ArrayList<VideoItem>(items.size)
        while (pending.isNotEmpty()) {
            val lookAhead = minOf(DIVERSITY_LOOKAHEAD, pending.size)
            var bestIndex = 0
            var bestCost = Double.MAX_VALUE
            val recent = out.takeLast(window)
            for (i in 0 until lookAhead) {
                val candidate = pending[i]
                val similar = recent.maxOfOrNull { similarity(it, candidate) } ?: 0.0
                val cost = i * POSITION_COST + similar * SIMILARITY_PENALTY
                if (cost < bestCost) { bestCost = cost; bestIndex = i }
            }
            out += pending.removeAt(bestIndex)
        }
        return out
    }

    /**
     * 负反馈只降权，不永久屏蔽。画像明显不喜欢的（合计低于 [EXPLORE_NEGATIVE_THRESHOLD]）
     * 打分后沉在候选底部，正常情况下永远露不出来；这里从中挑几条作为**探索位**，
     * 每 [EXPLORE_EVERY] 条塞一条到随机位置——口味变了还有机会被重新发现。
     *
     * 探索的是“还不知道你喜不喜欢”，不是“你已经明说不喜欢”：点过
     * 「不感兴趣：作者 / 标签」的不进探索位（在兴趣管理里恢复之前都不进），
     * 剩下的按离 0 最近的优先——弱负反馈比重负反馈更值得再试一次。
     */
    fun exploreDisliked(feed: List<VideoItem>): List<VideoItem> {
        val taste = profile
        val (open, buried) = feed.partition { taste.score(it) >= EXPLORE_NEGATIVE_THRESHOLD }
        if (buried.isEmpty() || open.isEmpty()) return feed
        val retryable = buried.filterNot { taste.isMuted(it) }.sortedByDescending { taste.score(it) }
        if (retryable.isEmpty()) return feed
        val slots = (open.size / EXPLORE_EVERY).coerceAtLeast(1).coerceAtMost(retryable.size)
        val explore = ArrayDeque(retryable.take(slots))
        explore.forEach { candidates[it.id]?.exploration = true }
        val rest = ArrayDeque(open)
        val out = ArrayList<VideoItem>(feed.size)
        while (rest.isNotEmpty()) {
            val block = ArrayList<VideoItem>(EXPLORE_EVERY + 1)
            repeat(EXPLORE_EVERY) { if (rest.isNotEmpty()) block += rest.removeFirst() }
            if (explore.isNotEmpty()) block.add(random.nextInt(block.size + 1), explore.removeFirst())
            out += block
        }
        // 没被选去探索的（包括明确拉黑的）照旧沉在底部，顺序不变。
        val used = retryable.take(slots).mapTo(HashSet()) { it.id }
        out += buried.filterNot { it.id in used }
        return out
    }

    /**
     * 多样性：同一个作者在任意连续 [AUTHOR_WINDOW] + 1 条里最多出现一次。
     * 打分高的作者会整段霸屏，看起来像作者页；把它的其余作品往后挪，顺序尽量不动。
     */
    fun spreadAuthors(items: List<VideoItem>, window: Int = AUTHOR_WINDOW): List<VideoItem> {
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
     */
    fun qualityScore(likes: Int, views: Int): Double {
        val l = likes.coerceAtLeast(0).toDouble()
        val v = views.coerceAtLeast(0).toDouble()
        val rate = (l + QUALITY_PRIOR_VIEWS * QUALITY_PRIOR_RATE) / (v + QUALITY_PRIOR_VIEWS)
        val relative = ln(rate / QUALITY_PRIOR_RATE).coerceIn(-1.5, 2.5) * 1.6
        val heat = ln(l + 1.0) * 0.5 + ln(v + 1.0) * 0.12
        return relative + heat
    }

    /**
     * 按发布时间分成（主体，经典池）两堆，顺序不变；没有发布时间的算主体。
     *
     * 年龄不是一刀切：**近期**（[RECENT_MAX_AGE_MS] 以内）和**中期**（到
     * [CLASSIC_MIN_AGE_MS] 为止）都留在主体里，只是新鲜度按连续曲线自然衰减；
     * 超过一年的才进经典池按间隔穿插。
     */
    fun splitByAge(items: List<VideoItem>, now: Long): Pair<List<VideoItem>, List<VideoItem>> {
        val cutoff = now - CLASSIC_MIN_AGE_MS
        return items.partition { it.createdAt <= 0L || it.createdAt >= cutoff }
    }

    /** 年龄桶，只用于诊断和文档口径：近期 / 中期 / 经典。 */
    fun ageBucket(item: VideoItem, now: Long): String {
        if (item.createdAt <= 0L) return "近期"
        val age = (now - item.createdAt).coerceAtLeast(0L)
        return when {
            age <= RECENT_MAX_AGE_MS -> "近期"
            age < CLASSIC_MIN_AGE_MS -> "中期"
            else -> "经典"
        }
    }

    /**
     * 每 [classicsEvery] 条里塞一条老片，塞在这一组里的随机位置——可能是第一条，
     * 也可能是最后一条。老片不够时有多少塞多少，塞完就照常。
     */
    fun weaveClassics(feed: List<VideoItem>, classics: List<VideoItem>): List<VideoItem> {
        val every = classicsEvery
        if (every <= 0 || classics.isEmpty() || feed.isEmpty()) return feed
        val taken = feed.map { it.id }.toHashSet()
        val pool = ArrayDeque(classics.filter { taken.add(it.id) })
        if (pool.isEmpty()) return feed
        pool.forEach { item ->
            remember(item) {
                it.classic = true
                it.sources += RecommendationCandidate.Source.CLASSICS
            }
        }
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
     * 关注作者的更新按**位置**插进结果里，而不是靠权重往上挤。
     *
     * 以前订阅流权重最高、关注的作者还额外加分，结果整屏刷出来全是已经关注的人。
     * 现在每 [DISCOVERY_RUN] 条没关注的内容配一条关注作者的，占比固定，跟打分高低无关；
     * 插在这一组里的哪个位置是随机的，固定插在组尾会形成一眼看得出来的节奏。
     */
    fun interleave(ranked: List<VideoItem>, subscribed: Set<String>, followed: Set<String>): List<VideoItem> {
        fun isFollowed(item: VideoItem) =
            item.id in subscribed || (item.authorId.isNotBlank() && item.authorId in followed)

        val followedItems = ArrayDeque(ranked.filter(::isFollowed))
        val discovery = ArrayDeque(ranked.filterNot(::isFollowed))
        // 一边是空的就没得混，原样返回：没关注任何作者、关注的作者最近没更新、
        // 没登录拿不到订阅流，都会走到这里。这是正常情况，不是错误。
        if (followedItems.isEmpty() || discovery.isEmpty()) return ranked

        // 关注的更新不够按 1:[DISCOVERY_RUN] 铺满时，把间隔拉大，让这几条散布开。
        // 间隔按最终会露面的那一段算：结果最后要截到 MAX_RESULTS 条。
        val window = minOf(discovery.size + followedItems.size, MAX_RESULTS)
        val run = maxOf(DISCOVERY_RUN, window / followedItems.size - 1)

        val out = ArrayList<VideoItem>(ranked.size)
        while (discovery.isNotEmpty() && followedItems.isNotEmpty()) {
            val block = ArrayList<VideoItem>(run + 1)
            repeat(run) { if (discovery.isNotEmpty()) block += discovery.removeFirst() }
            block.add(random.nextInt(block.size + 1), followedItems.removeFirst())
            out += block
        }
        out += discovery
        out += followedItems
        return out
    }

    companion object {
        /** 质量分的先验：假设每条视频先有 400 次播放、5% 的点赞率。 */
        const val QUALITY_PRIOR_VIEWS = 400.0
        const val QUALITY_PRIOR_RATE = 0.05
        /** 同一作者两条视频之间至少隔这么多条。 */
        const val AUTHOR_WINDOW = 4
        /** 画像分低于这个值算“明显不喜欢”，只以探索位的形式偶尔出现。 */
        const val EXPLORE_NEGATIVE_THRESHOLD = -1.0
        /** 每这么多条正常推荐配一条探索位。 */
        const val EXPLORE_EVERY = 30
        /** 每这么多条“发现”配一条关注作者的更新，插在这一组里的随机位置。 */
        const val DISCOVERY_RUN = 5
        /** 年龄软分桶：90 天内近期，一年内中期（都在主体里），超过一年才是经典。 */
        const val RECENT_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
        const val CLASSIC_MIN_AGE_MS = 365L * 24 * 60 * 60 * 1000
        /** 老片排序里口味占的比重：比主流里的 0.38 更重，老片本来就该挑合口味的。 */
        const val CLASSIC_TASTE_WEIGHT = 0.5
        /** 多样性重排：和最近几条比、最多往后看几条、名次代价、相似度惩罚。 */
        const val SIMILARITY_WINDOW = 3
        const val DIVERSITY_LOOKAHEAD = 16
        const val POSITION_COST = 0.06
        const val SIMILARITY_PENALTY = 1.2
        /** 近期视频至少凑到这么多条，不够才拿老的补。 */
        const val MIN_RECENT_FEED = 24
        /** 一次最多产出多少条推荐。 */
        const val MAX_RESULTS = 80
        /** 候选表最多记这么多条；超了整张清掉（只是少了推荐理由，不影响推荐本身）。 */
        const val MAX_TRACKED_CANDIDATES = 2000
    }
}
