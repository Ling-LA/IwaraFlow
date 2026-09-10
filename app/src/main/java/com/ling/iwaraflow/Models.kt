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
    val authorId: String = "",
    val authorUsername: String = "",
    var authorFollowing: Boolean = false,
    val isPrivate: Boolean = false,
    val status: String = "",
    val thumbnailUrl: String = "",
    var playbackIssue: String? = null,
    var resumePositionMs: Long = 0L
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
