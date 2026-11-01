package com.ling.iwaraflow

import org.mockito.Mockito.*

/** UI tests inject an acknowledged server boundary; protocol/error cases live in PlaylistApiTest. */
internal fun acknowledgedFavoritesFixture(): PlaylistFavorites {
    val controller = mock(PlaylistFavorites::class.java)
    val saved = mutableSetOf<String>()
    `when`(controller.isFavorite(anyString())).thenAnswer { it.getArgument<String>(0) in saved }
    `when`(controller.listen(any<((String?) -> Unit)>() ?: {})).thenReturn(java.io.Closeable {})
    doAnswer {
        val item = it.getArgument<VideoItem>(0)
        val changed = saved.add(item.id)
        it.getArgument<(Result<PlaylistFavorites.Change>) -> Unit>(1)(Result.success(PlaylistFavorites.Change(true, changed)))
        null
    }.`when`(controller).add(any<VideoItem>() ?: VideoItem("fixture", "Fixture", "Author", emptyList(), 0),
        any<((Result<PlaylistFavorites.Change>) -> Unit)>() ?: {})
    doAnswer {
        val item = it.getArgument<VideoItem>(0)
        val changed = saved.remove(item.id)
        it.getArgument<(Result<PlaylistFavorites.Change>) -> Unit>(1)(Result.success(PlaylistFavorites.Change(false, changed)))
        null
    }.`when`(controller).remove(any<VideoItem>() ?: VideoItem("fixture", "Fixture", "Author", emptyList(), 0),
        any<((Result<PlaylistFavorites.Change>) -> Unit)>() ?: {})
    return controller
}
