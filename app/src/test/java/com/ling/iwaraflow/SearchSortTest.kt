package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/** 搜索结果页的排序：默认最新优先，可换播放量/点赞数，可切顺序倒序，作者按关注数。 */
class SearchSortTest {
    private fun video(id: String, likes: Int = 0, views: Int = 0, createdAt: Long = 0L) =
        VideoItem(id, "标题 $id", "作者", emptyList(), likes, views, createdAt)

    private val items = listOf(
        video("old", likes = 900, views = 5000, createdAt = 1_000L),
        video("new", likes = 10, views = 20, createdAt = 9_000L),
        video("mid", likes = 300, views = 40_000, createdAt = 5_000L)
    )

    @Test fun theDefaultIsNewestFirst() {
        assertEquals(SearchSort.Key.DATE, SearchSort.DEFAULT_KEY)
        assertTrue(SearchSort.DEFAULT_DESCENDING)
        val sorted = SearchSort.sorted(items, SearchSort.DEFAULT_KEY, SearchSort.DEFAULT_DESCENDING)
        assertEquals(listOf("new", "mid", "old"), sorted.map { it.id })
    }

    @Test fun viewsAndLikesEachHaveTheirOwnOrder() {
        assertEquals(
            listOf("mid", "old", "new"),
            SearchSort.sorted(items, SearchSort.Key.VIEWS, true).map { it.id }
        )
        assertEquals(
            listOf("old", "mid", "new"),
            SearchSort.sorted(items, SearchSort.Key.LIKES, true).map { it.id }
        )
    }

    @Test fun ascendingOrderIsTheOtherDirection() {
        assertEquals(
            listOf("old", "mid", "new"),
            SearchSort.sorted(items, SearchSort.Key.DATE, false).map { it.id }
        )
        assertEquals(
            listOf("new", "old", "mid"),
            SearchSort.sorted(items, SearchSort.Key.VIEWS, false).map { it.id }
        )
    }

    /** 接口偶尔不给 createdAt：这些结果不能因为时间是 0 就跑到升序的最前面。 */
    @Test fun resultsWithoutAnUploadTimeStayAtTheEnd() {
        val mixed = items + video("undated", likes = 7, views = 7)
        assertEquals(
            listOf("new", "mid", "old", "undated"),
            SearchSort.sorted(mixed, SearchSort.Key.DATE, true).map { it.id }
        )
        assertEquals(
            listOf("old", "mid", "new", "undated"),
            SearchSort.sorted(mixed, SearchSort.Key.DATE, false).map { it.id }
        )
    }

    @Test fun equalValuesKeepAStableOrderAcrossPages() {
        val tied = listOf(video("b", likes = 5), video("a", likes = 5), video("c", likes = 5))
        assertEquals(
            SearchSort.sorted(tied, SearchSort.Key.LIKES, true).map { it.id },
            SearchSort.sorted(tied.reversed(), SearchSort.Key.LIKES, true).map { it.id }
        )
    }

    @Test fun authorsAreOrderedByFollowersWithUnknownCountsLast() {
        val authors = listOf(
            IwaraAuthor("1", "小号", "small", followers = 12),
            IwaraAuthor("2", "没数据", "unknown"),
            IwaraAuthor("3", "大手", "big", followers = 5_400)
        )
        assertEquals(listOf("big", "small", "unknown"), SearchSort.sortedAuthors(authors).map { it.username })
    }

    @Test fun theStatusLineSaysWhichWayTheResultsRun() {
        assertEquals("上传时间 ↓", SearchSort.label(SearchSort.Key.DATE, true))
        assertEquals("播放量 ↑", SearchSort.label(SearchSort.Key.VIEWS, false))
    }
}
