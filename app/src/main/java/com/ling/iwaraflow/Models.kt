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
    var friendStatus: String = "none"
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

data class PreferenceProfile(
    val authorWeights: Map<String, Double>,
    val tagWeights: Map<String, Double>
) {
    fun score(item: VideoItem): Double {
        val author = authorWeights[item.author.lowercase()] ?: 0.0
        val tags = item.tags.sumOf { tagWeights[it.lowercase()] ?: 0.0 }
        return author + tags
    }
}
