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
    var following: Boolean = false,
    var friend: Boolean = false,
    var friendStatus: String = "none"
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
    val isPrivate: Boolean = false,
    val status: String = "",
    val thumbnailUrl: String = "",
    var playbackIssue: String? = null
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
