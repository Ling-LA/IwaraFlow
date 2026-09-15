package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/**
 * 在顶部四个流之间来回切时，回到原来那个流应该还在原来的位置：
 * 刷了二十条之后切去看一眼热门，回来又从第一条开始，是很容易让人恼火的那种“小事”。
 */
class FeedSessionStoreTest {
    private fun items(prefix: String, count: Int) =
        (0 until count).map { VideoItem("$prefix-$it", "t", "A", emptyList(), 0) }

    private fun session(prefix: String, index: Int, page: Int = 1) =
        FeedSessionStore.Session(items(prefix, 20), currentIndex = index, currentPage = page, pagingEnabled = true)

    @Test fun eachHomeFeedKeepsItsOwnPlace() {
        val store = FeedSessionStore()
        store.save("date", session("date", 7))
        store.save("trending", session("trending", 3))

        assertEquals(7, store.restorable("date")!!.currentIndex)
        assertEquals(3, store.restorable("trending")!!.currentIndex)
        assertNull("没存过的流没有现场", store.restorable("popularity"))
    }

    /** 再点一次“推荐”是“给我一批新的”，不该回到旧位置。 */
    @Test fun forgettingAFeedMakesItReloadNextTime() {
        val store = FeedSessionStore()
        store.save("recommend", session("rec", 5))
        store.forget("recommend")
        assertNull(store.restorable("recommend"))
    }

    @Test fun anEmptySessionIsNotWorthRestoring() {
        val store = FeedSessionStore()
        store.save("date", FeedSessionStore.Session(emptyList(), 0, 0, true))
        assertNull(store.restorable("date"))
    }

    /** 搜索、作者页、收藏这些不是顶栏的流，不留现场。 */
    @Test fun onlyTheFourHomeFeedsAreRemembered() {
        val store = FeedSessionStore()
        store.save("search", session("s", 2))
        assertNull(store.restorable("search"))
        assertEquals(setOf("recommend", "date", "trending", "popularity"), FeedSessionStore.HOME_MODES)
    }
}
