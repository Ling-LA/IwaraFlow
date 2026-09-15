package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/**
 * 观看反馈是连续值，不是几个固定档位：43 秒和 45 秒不该一个没信号、一个满分，
 * 播完也不该反而比看满 45 秒低。
 */
class WatchInterestTest {
    private fun interest(
        seconds: Double,
        durationSeconds: Double = 300.0,
        completed: Boolean = false,
        repeated: Boolean = false,
        rewound: Boolean = false,
        reacted: Boolean = false
    ) = WatchInteractionTracker.watchInterest(
        playedMs = (seconds * 1000).toLong(),
        durationMs = (durationSeconds * 1000).toLong(),
        completed = completed, repeated = repeated, rewound = rewound, reacted = reacted
    )

    @Test fun thereIsNoCliffAroundFortyFiveSeconds() {
        val before = interest(43.0)
        val after = interest(45.0)
        assertTrue("45 秒不该突然跳一大截：$before → $after", after - before < 0.05)
        assertTrue("看了 45 秒是正面信号", after > 0)
    }

    @Test fun watchingLongerAlwaysCountsForMore() {
        val values = listOf(1.0, 3.0, 8.0, 20.0, 60.0, 200.0).map { interest(it) }
        values.zipWithNext().forEach { (a, b) -> assertTrue("看得更久分数要更高：$values", b > a) }
    }

    @Test fun leavingRightAwayIsNegativeAndTheFasterTheWorse() {
        assertTrue(interest(0.0) < interest(2.0))
        assertTrue("起播就走是负的", interest(0.0) < -0.3)
        assertTrue("两秒就走还是负的", interest(2.0) < 0)
        assertTrue("看了十秒左右算不好不坏", kotlin.math.abs(interest(10.0)) < 0.1)
    }

    @Test fun finishingAVideoBeatsWatchingFortyFiveSecondsOfIt() {
        val partial = interest(45.0)
        val finished = interest(300.0, completed = true)
        assertTrue("播完要比看 45 秒强：$partial vs $finished", finished > partial)
    }

    @Test fun rewatchingRewindingAndReactingAllAddUp() {
        val plain = interest(60.0)
        assertTrue(interest(60.0, repeated = true) > plain)
        assertTrue(interest(60.0, rewound = true) > plain)
        assertTrue(interest(60.0, reacted = true) > plain)
    }

    /** 短视频看完一遍和长视频看一小段：比例也要算进去。 */
    @Test fun theShareOfTheVideoMatters() {
        val shortClip = interest(20.0, durationSeconds = 25.0)
        val longClip = interest(20.0, durationSeconds = 1800.0)
        assertTrue("20 秒看完一条 25 秒的短片，比在半小时长片里看 20 秒更说明问题", shortClip > longClip)
    }

    /** 接口没给时长时只按时间算，不该出错。 */
    @Test fun anUnknownDurationStillWorks() {
        assertTrue(WatchInteractionTracker.watchInterest(playedMs = 60_000L, durationMs = 0L) > 0)
        assertTrue(WatchInteractionTracker.watchInterest(playedMs = 0L, durationMs = 0L) < 0)
    }
}
