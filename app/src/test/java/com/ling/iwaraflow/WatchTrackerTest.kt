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
 * 观看记账本身：什么时候算数、什么时候一个字都不记。
 * 拆出 [WatchInteractionTracker] 之后这一块不用再搭一整套播放器就能测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WatchTrackerTest {
    private lateinit var history: HistoryStore
    private var streaks = 0
    private var watched = 0

    @Before fun open() {
        history = HistoryStore(RuntimeEnvironment.getApplication())
        streaks = 0; watched = 0
    }
    @After fun close() { history.close() }

    private fun tracker() = WatchInteractionTracker(history, { streaks += 1 }, { watched += 1 })

    private fun item(id: String = "v1") = VideoItem(id, id, "Alice", listOf("miku"), 1, authorId = "id-Alice")

    private fun metrics() = history.awaitWrites().let { history.recommendationMetrics() }

    /** 播放器压根没 READY 过：加载失败后划走不是态度，是网络问题。 */
    @Test fun aCardThatNeverPlayedRecordsNothing() {
        tracker().report(item(), durationMs = 60_000, playing = false, swipedAway = true, impressionSession = "")
        assertEquals(0, metrics().samples)
        assertEquals(0, streaks)
    }

    @Test fun anErrorMeansTheSwipeIsNotAnOpinion() {
        val watch = tracker()
        watch.noteReady()
        watch.noteError()
        watch.report(item(), 60_000, playing = false, swipedAway = true, impressionSession = "")
        assertEquals(0, metrics().samples)
    }

    /** 只报一次：重复调用（先切后台、再销毁）不该记两笔。 */
    @Test fun reportingTwiceOnlyCountsOnce() {
        val watch = tracker()
        watch.noteReady()
        watch.notePlaying(true)
        Thread.sleep(30)
        watch.notePlaying(false)
        watch.report(item(), 60_000, playing = false, swipedAway = true, impressionSession = "")
        watch.report(item(), 60_000, playing = false, swipedAway = true, impressionSession = "")
        assertTrue("两次 report 只能有一笔", metrics().samples <= 1)
    }

    /** 绑定新视频要清干净，上一条的播放时长不能带到下一条。 */
    @Test fun resetClearsEverythingForTheNextCard() {
        val watch = tracker()
        watch.noteReady()
        watch.notePlaying(true)
        Thread.sleep(20)
        watch.notePlaying(false)
        assertTrue(watch.playedMs > 0)
        watch.reset()
        assertEquals(0L, watch.playedMs)
        // reset 之后还能再报一次（reported 标记也清了）。
        watch.noteReady()
        watch.report(item("v2"), 60_000, playing = false, swipedAway = false, impressionSession = "")
        assertEquals("起播就走、又不是划走：只是没有正面信号，不该记负反馈", 0, streaks)
    }

    /** 播了很久：正面信号，连续划走的链子也断了。 */
    @Test fun aLongWatchIsPositiveAndBreaksTheSkipStreak() {
        val watch = tracker()
        watch.noteReady()
        watch.noteEnded()
        watch.report(item(), durationMs = 1_000, playing = false, swipedAway = true, impressionSession = "")
        assertEquals(1, watched)
        assertEquals(0, streaks)
    }
}
