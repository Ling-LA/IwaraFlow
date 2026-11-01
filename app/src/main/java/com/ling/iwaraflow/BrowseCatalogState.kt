package com.ling.iwaraflow

import android.os.Parcelable

/** Each category owns its loaded pages and grid position; playback is only a view of that list. */
internal class BrowseCatalogState {
    val items = ArrayList<VideoItem>()
    private val sourcePages = HashMap<String, Int>()
    var page = -1
        private set
    var canLoadMore = true
        private set
    var selectedIndex = 0
    var viewport: Parcelable? = null

    fun accept(requestPage: Int, videos: List<VideoItem>, rawCount: Int, pageSize: Int): Boolean {
        // A failed request never consumes its page number, and a stale continuation cannot append.
        if (requestPage != 0 && requestPage != page + 1) return false
        if (requestPage == 0) { items.clear(); sourcePages.clear(); viewport = null; selectedIndex = 0 }
        val ids = items.mapTo(HashSet()) { it.id }
        items.addAll(videos.filter { it.id.isNotBlank() && ids.add(it.id) }.onEach { sourcePages[it.id] = requestPage })
        page = requestPage
        canLoadMore = rawCount >= pageSize
        return true
    }

    fun restore(session: FeedSessionStore.Session) {
        items.clear(); items.addAll(session.items)
        sourcePages.clear(); items.forEach { sourcePages[it.id] = session.currentPage }
        selectedIndex = session.currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        page = session.currentPage; canLoadMore = session.pagingEnabled
    }

    fun rememberSourcePage(id: String, sourcePage: Int) { sourcePages[id] = sourcePage.coerceAtLeast(0) }
    fun sourcePage(id: String): Int = sourcePages[id] ?: page.coerceAtLeast(0)

    /** FeedSessionCodec keeps forty nearby cards. Re-fetch their final page if the tail is cut. */
    fun snapshot(index: Int): FeedSessionStore.Session {
        val start = (index - 2).coerceAtLeast(0)
        val window = items.drop(start).take(40)
        val cutTail = start + window.size < items.size
        val cursor = if (cutTail) (sourcePage(window.last().id) - 1).coerceAtLeast(0) else page.coerceAtLeast(0)
        return FeedSessionStore.Session(items, index, cursor, canLoadMore || cutTail)
    }

    companion object { val MODES = setOf("date", "trending", "popularity") }
}
