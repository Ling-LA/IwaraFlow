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
}
