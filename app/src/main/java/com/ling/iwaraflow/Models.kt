package com.ling.iwaraflow

data class VideoItem(
    val id: String,
    val title: String,
    val author: String,
    val tags: List<String>,
    val likes: Int,
    var streamUrl: String? = null
)
