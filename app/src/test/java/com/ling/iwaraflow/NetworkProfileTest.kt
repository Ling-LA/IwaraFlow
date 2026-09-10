package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/** 弱网/计量网络下少提前下点东西，画质不受影响。 */
class NetworkProfileTest {
    @Test fun wifiKeepsPrefetchingTwoVideosAhead() {
        assertEquals(2, NetworkProfile.prefetchCount(metered = false, downstreamKbps = 40_000))
    }

    @Test fun meteredConnectionsOnlyPrefetchTheNextVideo() {
        assertEquals(1, NetworkProfile.prefetchCount(metered = true, downstreamKbps = 40_000))
    }

    @Test fun slowLinksOnlyPrefetchTheNextVideo() {
        assertEquals(1, NetworkProfile.prefetchCount(metered = false, downstreamKbps = 1_500))
    }

    @Test fun anUnknownBandwidthIsTreatedAsRoomy() {
        assertEquals(2, NetworkProfile.prefetchCount(metered = false, downstreamKbps = 0))
        assertEquals(PlayableVideoGate.COLD_START_CANDIDATES,
            NetworkProfile.coldStartCandidates(metered = false, downstreamKbps = 0))
    }

    @Test fun aSlowColdStartChecksFewerCandidates() {
        val frugal = NetworkProfile.coldStartCandidates(metered = true, downstreamKbps = 0)
        assertEquals(NetworkProfile.FRUGAL_COLD_START_CANDIDATES, frugal)
        assertTrue("弱网候选窗口必须小于默认窗口", frugal < PlayableVideoGate.COLD_START_CANDIDATES)
    }

    @Test fun theFeedNeverBuffersTheDefaultFiftySecondsAhead() {
        // 画质由视频源码率决定，这里只限制“提前缓冲多少秒”。
        assertTrue(VideoAdapter.MAX_BUFFER_MS < 50_000)
        assertTrue(VideoAdapter.BUFFER_FOR_PLAYBACK_MS < 2_500)
        assertTrue(VideoAdapter.MIN_BUFFER_MS <= VideoAdapter.MAX_BUFFER_MS)
        assertTrue(VideoAdapter.BUFFER_FOR_PLAYBACK_MS <= VideoAdapter.MIN_BUFFER_MS)
        assertTrue(VideoAdapter.BUFFER_AFTER_REBUFFER_MS <= VideoAdapter.MIN_BUFFER_MS)
    }
}
