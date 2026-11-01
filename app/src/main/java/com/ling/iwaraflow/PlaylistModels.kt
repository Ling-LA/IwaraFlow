package com.ling.iwaraflow

class PlaylistHttpException(val status: Int, message: String) : java.io.IOException(message)

data class PlaylistFolder(
    val id: String,
    val title: String,
    val count: Int = 0,
    val thumbnail: String = "",
    val added: Boolean = false
)

data class PlaylistFolderPage(val folders: List<PlaylistFolder>, val total: Int, val hasMore: Boolean)
data class PlaylistVideoPage(
    val folder: PlaylistFolder,
    val items: List<VideoItem>,
    val total: Int,
    val hasMore: Boolean,
    val offline: Boolean = false
)
