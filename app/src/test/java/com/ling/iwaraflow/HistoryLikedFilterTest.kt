package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class HistoryLikedFilterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun video(id: String) = VideoItem(id, "作品 $id", "作者", listOf("music"), 12)

    @Test fun filtersBeforePaginationAndCombinesWithSearchAndSort() {
        HistoryStore(context).use { history ->
            repeat(9) { history.recordWatch(video("v$it"), 1000, 10000, false) }
            val ids = setOf("v1", "v3", "v5", "v7")
            val first = SavedLibrary.page(history.libraryDatabase, false, 0, 2, ascending = true, likedIds = ids)
            val second = SavedLibrary.page(history.libraryDatabase, false, 2, 2, ascending = true, likedIds = ids)
            assertEquals(4, first.total)
            assertEquals(listOf("v1", "v3", "v5", "v7"), (first.items + second.items).map { it.id })
            assertTrue(first.items.all { it.liked })
            assertEquals(1, SavedLibrary.page(history.libraryDatabase, false, 0, query = "作品 v5", likedIds = ids).total)
            assertEquals(0, SavedLibrary.page(history.libraryDatabase, false, 0, likedIds = emptySet()).total)
            assertEquals(9, SavedLibrary.page(history.libraryDatabase, false, 0).total)
        }
    }

    @Test fun acknowledgesUnlikeAndKeepsAccountsSeparateWithoutSQLiteBindLimit() {
        val prefs = AppPrefs(context)
        prefs.accountId = "account-a"
        val states = VideoLikeStore(context)
        states.confirm(states.snapshot(), "v1", true, 13)
        states.confirm(states.snapshot(), "v2", true, 13)
        states.confirm(states.snapshot(), "v2", false, 12)
        assertEquals(setOf("v1"), states.likedIds())
        prefs.accountId = "account-b"
        assertTrue(states.likedIds().isEmpty())
        prefs.accountId = "account-a"
        HistoryStore(context).use { history ->
            history.recordWatch(video("v1"), 1000, 10000, false)
            val manyLikes = (1..2000).mapTo(mutableSetOf()) { "v$it" }
            assertEquals(1, SavedLibrary.page(history.libraryDatabase, false, 0, likedIds = manyLikes).total)
        }
    }
}
