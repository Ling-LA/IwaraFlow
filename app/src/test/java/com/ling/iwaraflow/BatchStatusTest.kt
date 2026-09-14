package com.ling.iwaraflow

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 推荐候选分桶、首页装点原来是每条视频各查一次「看过没」「收藏没」，
 * 几百条候选就是上千次 SQLite 查询。改成一次问清一批。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatchStatusTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String) = VideoItem(id, id, "Alice", listOf("t"), 1)

    @Test fun theBatchAnswerMatchesTheOneByOneAnswer() {
        history.markSeen("seen-1")
        history.recordWatch(video("watched-1"), 1000L, 5000L, false)
        history.setLocalFavorite(video("fav-1"), true)

        val ids = listOf("seen-1", "watched-1", "fav-1", "unknown-1")
        val statuses = history.loadStatuses(ids)

        ids.forEach { id ->
            assertEquals("已看判断要和单条查询一致：$id", history.isSeen(id), id in statuses.seen)
            assertEquals("收藏判断要和单条查询一致：$id", history.isLocalFavorite(id), id in statuses.favorites)
        }
        assertTrue("收藏的同时也算看过", "fav-1" in statuses.seen)
        assertFalse("没记录过的两样都不是", "unknown-1" in statuses.seen)
    }

    @Test fun emptyAndBlankInputIsHarmless() {
        val statuses = history.loadStatuses(listOf("", "  ".trim()))
        assertTrue(statuses.seen.isEmpty())
        assertTrue(statuses.favorites.isEmpty())
        assertTrue(history.loadStatuses(emptyList()).seen.isEmpty())
    }

    /** 超过一次能问的变量个数时要分批，而不是报错。 */
    @Test fun aLotOfIdsAreAskedInBatches() {
        val many = (0 until HistoryStore.STATUS_BATCH * 2 + 7).map { "v$it" }
        history.markSeen(many.take(5))
        val statuses = history.loadStatuses(many)
        assertEquals(5, statuses.seen.size)
        assertTrue(statuses.favorites.isEmpty())
    }
}
