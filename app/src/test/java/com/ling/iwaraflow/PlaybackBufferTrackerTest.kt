package com.ling.iwaraflow

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferTrackerTest {
    @Test fun initialBufferAndPausedTimeDoNotBecomePlaybackStalls() {
        var now = 0L
        val samples = mutableListOf<Pair<Long, Boolean>>()
        val tracker = PlaybackBufferTracker({ now }) { ms, seek -> samples += ms to seek }
        tracker.update(Player.STATE_BUFFERING, true)
        now = 5000; tracker.update(Player.STATE_READY, true); tracker.firstFrame()
        assertTrue(samples.isEmpty())
        tracker.update(Player.STATE_BUFFERING, true)
        now = 5300; tracker.update(Player.STATE_BUFFERING, false)
        now = 15000; tracker.update(Player.STATE_READY, false)
        assertEquals(listOf(300L to false), samples)
    }

    @Test fun seekAndSourceSwitchWaitsAreSeparateFromNetworkWaiting() {
        var now = 0L
        val samples = mutableListOf<Pair<Long, Boolean>>()
        val tracker = PlaybackBufferTracker({ now }) { ms, seek -> samples += ms to seek }
        tracker.firstFrame()
        // Media3 can report BUFFERING before the seek-discontinuity callback.
        tracker.update(Player.STATE_BUFFERING, true); tracker.intentionalChange()
        now = 200; tracker.update(Player.STATE_READY, true)
        tracker.intentionalChange(); tracker.update(Player.STATE_IDLE, true)
        tracker.update(Player.STATE_BUFFERING, true)
        now = 500; tracker.update(Player.STATE_READY, true)
        tracker.update(Player.STATE_BUFFERING, true)
        now = 800; tracker.finish()
        assertEquals(listOf(200L to true, 300L to true, 300L to false), samples)
    }
}
