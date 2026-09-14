package com.ling.iwaraflow

data class VideoSource(
    val name: String,
    val url: String,
    val score: Int
)

data class IwaraAuthor(
    val id: String,
    val name: String,
    val username: String,
    val description: String = "",
    val avatarUrl: String = "",
    var following: Boolean = false,
    var friend: Boolean = false,
    var friendStatus: String = "none",
    /** 关注（粉丝）数；服务端没给就是 -1，搜索结果按这个排序时排在后面。 */
    val followers: Int = -1
)

/** 关注列表的一页；[total] 是服务端回报的关注总数，[hasMore] 表示后面还有整页数据。 */
data class FollowingPage(
    val users: List<IwaraAuthor>,
    val total: Int,
    val hasMore: Boolean
)

/**
 * 视频列表的一页；[total] 是服务端回报的该列表总条数，没回报时是 -1。
 * 推荐算法靠它算出列表一共有多少页，不用再去猜或者试。
 */
data class VideoListPage(
    val videos: List<VideoItem>,
    val total: Int
)

/**
 * 推荐质量的本地诊断指标，见 [HistoryStore.recommendationMetrics]。
 * 只算本机数据，不上传；设置里的「诊断信息」会把它打印出来。
 */
data class RecommendationMetrics(
    /** 最近统计了多少条观看行为（看过的 + 划走的）。 */
    val samples: Int,
    /** 快速划走率：最直接能看出推荐跑没跑偏。 */
    val quickSkipRate: Double,
    /** 平均有效播放时长。 */
    val averageWatchMs: Long,
    /** 播放完成率。 */
    val completionRate: Double,
    /** 点赞 / 收藏率：强正反馈。 */
    val reactionRate: Double,
    /** 同作者重复率：作者多样性。 */
    val authorRepeatRate: Double,
    /** 标签重复率：内容多样性。 */
    val tagRepeatRate: Double
) {
    fun summary(): String = buildString {
        if (samples == 0) { append("最近没有足够的观看记录"); return@buildString }
        fun percent(value: Double) = "%.0f%%".format(value * 100)
        append("最近 $samples 条：")
        append("快速划走 ${percent(quickSkipRate)}")
        append(" · 平均播放 ${averageWatchMs / 1000} 秒")
        append(" · 完成率 ${percent(completionRate)}")
        append(" · 点赞收藏 ${percent(reactionRate)}")
        append(" · 同作者重复 ${percent(authorRepeatRate)}")
        append(" · 同标签重复 ${percent(tagRepeatRate)}")
    }
}

/** 一批视频的本地状态，一次查库问清，见 [HistoryStore.loadStatuses]。 */
data class VideoStatuses(val seen: Set<String>, val favorites: Set<String>)

/** 一条 Iwara 官方评论。[parentId] 非空表示这是某条评论下的回复。 */
data class IwaraComment(
    val id: String,
    val body: String,
    val author: IwaraAuthor,
    val createdAt: Long,
    val replyCount: Int = 0,
    val parentId: String = ""
)

/** 评论列表的一页；[total] 是服务端回报的总条数（回复列表时是该评论的回复总数）。 */
data class CommentPage(
    val comments: List<IwaraComment>,
    val total: Int,
    val hasMore: Boolean
)

/** 官方点赞列表的一页；[total] 是服务端回报的点赞总数。 */
data class FavoritesPage(
    val videos: List<VideoItem>,
    val total: Int,
    val hasMore: Boolean
)

data class VideoItem(
    val id: String,
    val title: String,
    val author: String,
    val tags: List<String>,
    var likes: Int,
    val views: Int = 0,
    val createdAt: Long = 0L,
    var liked: Boolean = false,
    var localFavorite: Boolean = false,
    var streamUrl: String? = null,
    var sources: List<VideoSource>? = null,
    var selectedQuality: String? = null,
    /** 作者 id / 用户名。本地下载记录里没有，播放时补拉详情再填，所以是 var。 */
    var authorId: String = "",
    var authorUsername: String = "",
    var authorFollowing: Boolean = false,
    val isPrivate: Boolean = false,
    val status: String = "",
    val thumbnailUrl: String = "",
    var playbackIssue: String? = null,
    var resumePositionMs: Long = 0L,
    /** 作者写的简介（Iwara 的 `body`）；列表接口就带，本地表里存的视频为空，看时再补拉。 */
    var description: String = ""
)

data class LoginResult(
    val success: Boolean,
    val message: String
)

/**
 * 本地口味画像：作者（按名字和 id 两套键）、标签各自的权重。权重可以为负——
 * 起播后很快划走会往下压，所以既有“喜欢什么”也有“不喜欢什么”。
 */
data class PreferenceProfile(
    val authorWeights: Map<String, Double>,
    val tagWeights: Map<String, Double>,
    val authorIdWeights: Map<String, Double> = emptyMap(),
    /**
     * 视频级反馈。「不感兴趣：当前视频」只压这一条视频，不碰作者也不碰它的标签——
     * 用户说的是“我不想看这一条”，不是“我不喜欢这个作者和这十个标签”。
     */
    val videoWeights: Map<String, Double> = emptyMap(),
    /** 用户明确点过「不感兴趣：作者」的作者名。 */
    val mutedAuthors: Set<String> = emptySet(),
    val mutedAuthorIds: Set<String> = emptySet(),
    /** 用户明确点过「不感兴趣：标签」的标签。 */
    val mutedTags: Set<String> = emptySet()
) {
    fun score(item: VideoItem): Double {
        val byId = item.authorId.takeIf { it.isNotBlank() }?.let { authorIdWeights[it] }
        val author = byId ?: authorWeights[item.author.lowercase()] ?: 0.0
        return author + matchedTagScore(item) + (videoWeights[item.id] ?: 0.0)
    }

    /**
     * 只算命中最强的几个标签，而不是把所有标签的权重直接相加：
     * 标签有 15 个的视频只要沾到几个正兴趣标签，就会无脑压过只有 3 个标签的视频。
     * 按绝对值取前 [TOP_TAGS] 个，强负反馈的标签同样算得进来。
     */
    private fun matchedTagScore(item: VideoItem): Double =
        item.tags.mapNotNull { tagWeights[it.lowercase()] }
            .sortedByDescending { kotlin.math.abs(it) }
            .take(TOP_TAGS)
            .sum()

    /** 用户明确说过不想看这个作者 / 标签。这类内容连探索位都不该给。 */
    fun isMuted(item: VideoItem): Boolean =
        (item.authorId.isNotBlank() && item.authorId in mutedAuthorIds) ||
            (item.author.isNotBlank() && item.author.lowercase() in mutedAuthors) ||
            item.tags.any { it.lowercase() in mutedTags }

    /** 权重最高的几个标签（至少 [minWeight]），给个性化召回用。 */
    fun topTags(count: Int, minWeight: Double): List<String> =
        tagWeights.entries.filter { it.value >= minWeight }.sortedByDescending { it.value }.take(count).map { it.key }

    fun topAuthorIds(count: Int, minWeight: Double): List<String> =
        authorIdWeights.entries.filter { it.value >= minWeight }.sortedByDescending { it.value }.take(count).map { it.key }

    companion object {
        /** 一条视频最多按几个标签算分。 */
        const val TOP_TAGS = 4
    }
}
