package com.ling.iwaraflow

/**
 * 一条推荐候选连同它是怎么来的。
 *
 * 以前候选一进队列就只剩下 [VideoItem]，来源和第一次排序时算出来的分全丢了：
 * 用户点个赞触发实时重排时，只能拿质量 + 新鲜度 + 画像重算，和第一次排序不是一套口径，
 * 整条队列的评分体系说变就变。现在来源分和匹配信息一路带着，重排是在原来的完整分上
 * 更新口味，而不是把第一次排序的信息全扔掉。
 *
 * 顺带就能回答「为什么推荐给我」，见 [reason]。
 */
data class RecommendationCandidate(
    val item: VideoItem,
    /** 来源权重 + 榜单位置，见 RecommendationEngine.mergeRound。 */
    var sourceScore: Double = 0.0,
    /** 这条候选在哪些召回里出现过，见 [Source]。 */
    val sources: MutableSet<String> = LinkedHashSet(),
    /** 因为画像里的哪个标签被召回的。 */
    val matchedTags: MutableSet<String> = LinkedHashSet(),
    /** 因为画像里的哪个作者被召回的。 */
    var matchedAuthorId: String = "",
    /** 站方数据算出来的质量分，见 RecommendationEngine.qualityScore。 */
    var baseQuality: Double = 0.0,
    /**
     * 每个**来源分组**上拿到的最高来源分，以及这一组里命中了几路，见 [sourceGroup]。
     * 来源分由它们算出来（[recomputeSourceScore]），而不是每命中一路就往上加。
     */
    val groupScores: MutableMap<String, Double> = LinkedHashMap(),
    val groupHits: MutableMap<String, Int> = LinkedHashMap(),
    /** 来自关注作者的更新。 */
    var subscribed: Boolean = false,
    /** 老片穿插位。 */
    var classic: Boolean = false,
    /** 探索位：画像还不知道用户喜不喜欢的内容。 */
    var exploration: Boolean = false,
    /**
     * 这条候选属于哪一轮推荐（每次完整刷新 +1）。
     *
     * 同一个视频可能在好几轮里被召回，来源却完全不同：这一轮是标签召回来的，
     * 下一轮可能只是官方榜单的回退结果。以前 `remember()` 是 `getOrPut`，
     * 于是新一轮会沿用上一轮的来源分、命中标签、“探索位 / 老片”标记，
     * 推荐理由和评分都跟着串。轮次对不上就重建一条，不再接着用旧的。
     */
    var generation: Int = 0
) {
    /** 「为什么推荐给我」：挑最能说明问题的那一条理由。 */
    fun reason(): String = when {
        subscribed -> "你关注的作者更新了"
        exploration -> "为你探索的新内容"
        matchedTags.isNotEmpty() -> "你最近常看 #${matchedTags.first()}"
        matchedAuthorId.isNotBlank() -> "你喜欢这位作者的作品"
        classic -> "历史上的高赞作品"
        Source.MONTH_TOP in sources -> "近期的高赞作品"
        Source.TRENDING in sources -> "热门榜上的作品"
        Source.POPULARITY in sources -> "流行榜上的作品"
        Source.DATE in sources -> "最近的新投稿"
        else -> "综合推荐"
    }

    /**
     * 记一路召回命中：同一分组里只留最高分，不叠加。
     *
     * 热门、流行、月高赞本来就高度相关（都是“很多人点赞”的不同说法），而点赞数和
     * 播放量又已经算进了质量分。以前每多命中一路就 `+0.55 ×`，同时上三个榜的作品
     * 等于把“热门”这一件事奖励了三次还多。现在同组取最高，多命中只给一点小加成。
     */
    fun noteSource(label: String, boost: Double) {
        sources += label
        val group = sourceGroup(label)
        groupScores[group] = maxOf(groupScores[group] ?: 0.0, boost)
        groupHits[group] = (groupHits[group] ?: 0) + 1
        recomputeSourceScore()
    }

    private fun recomputeSourceScore() {
        if (groupScores.isEmpty()) return
        val sorted = groupScores.values.sortedDescending()
        // 跨分组是真正不同的证据（“很多人喜欢” vs “你喜欢的标签” vs “你关注的人”），
        // 照旧按折扣叠加；同组内多命中只给一点小加成。
        val base = sorted.first() + sorted.drop(1).sumOf { it * CROSS_GROUP_SHARE }
        val extraHits = groupHits.values.sumOf { (it - 1).coerceAtLeast(0) }.coerceAtMost(MULTI_HIT_CAP)
        sourceScore = base + extraHits * MULTI_HIT_BONUS
    }

    object Source {
        const val SUBSCRIBED = "subscribed"
        const val TRENDING = "trending"
        const val POPULARITY = "popularity"
        const val DATE = "date"
        const val MONTH_TOP = "month_top"
        const val TAG = "tag"
        const val AUTHOR = "author"
        const val CLASSICS = "classics"
        const val OFFICIAL = "official"
    }

    /** 来源分组：同一组里的几路来源说的其实是同一件事。 */
    object Group {
        /** 站方热度：热门 / 流行 / 月高赞 / 老片。 */
        const val QUALITY = "quality"
        /** 个性化召回：按你的标签、你的作者拉的页。 */
        const val PERSONAL = "personal"
        /** 社交：你关注的作者的更新。 */
        const val SOCIAL = "social"
        /** 新鲜：最新投稿。 */
        const val FRESH = "fresh"
    }

    companion object {
        /** 跨分组叠加时后面几组打的折。 */
        const val CROSS_GROUP_SHARE = 0.55
        /** 同一分组里多命中一路给的小加成，以及最多给几次。 */
        const val MULTI_HIT_BONUS = 0.2
        const val MULTI_HIT_CAP = 3

        fun sourceGroup(label: String): String = when (label) {
            Source.TAG, Source.AUTHOR -> Group.PERSONAL
            Source.SUBSCRIBED -> Group.SOCIAL
            Source.DATE -> Group.FRESH
            // 热门 / 流行 / 月高赞 / 老片 / 官方回退：说的都是“很多人喜欢”。
            else -> Group.QUALITY
        }
    }
}
