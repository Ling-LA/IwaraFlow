package com.ling.iwaraflow
import org.junit.Assert.*
import org.junit.Test
class WarmRecommendationPolicyTest {
    @Test fun expirationSupportsSecondsAndMillisecondsAndSafetyMargin() {
        val now = 1_800_000_000_000L
        assertTrue(WarmRecommendationStore.sourceFresh("https://cdn.test/v?expires=1800000300", now, now))
        assertTrue(WarmRecommendationStore.sourceFresh("https://cdn.test/v?expires=1800000300000", now, now))
        assertFalse(WarmRecommendationStore.sourceFresh("https://cdn.test/v?expires=1800000010", now, now))
        assertFalse(WarmRecommendationStore.sourceFresh("https://cdn.test/v?expires=1", now, now))
    }
    @Test fun unsignedUrlsHaveShortLifetimeAndRejectClockRollback() {
        assertTrue(WarmRecommendationStore.sourceFresh("https://cdn.test/v", 1_000, 2_000))
        assertFalse(WarmRecommendationStore.sourceFresh("https://cdn.test/v", 1_000, 1_000_000))
        assertFalse(WarmRecommendationStore.sourceFresh("https://cdn.test/v", 2_000, 1_000))
    }
}
