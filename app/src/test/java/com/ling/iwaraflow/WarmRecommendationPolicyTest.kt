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
    @Test fun malformedExpiryNeverMakesAnOldAuthorizationFresh() {
        val now = 1_800_000_000_000L
        listOf("", "invalid", "-1", "99999999999999999999999").forEach { expiry ->
            assertFalse(WarmRecommendationStore.sourceFresh("https://cdn.test/v?expires=$expiry", now, now))
        }
        assertFalse(WarmRecommendationStore.sourceFresh("https://cdn.test/v?expires=1800000300", now + 1, now))
    }
    @Test fun restoredQueueRequiresActualUsefulBytesRatherThanMetadataAge() {
        assertFalse(WarmRecommendationStore.hasReusablePrefix(0, 512_000))
        assertFalse(WarmRecommendationStore.hasReusablePrefix(1024, 512_000))
        assertTrue(WarmRecommendationStore.hasReusablePrefix(64 * 1024, -1))
        assertTrue("Even a short complete clip is reusable", WarmRecommendationStore.hasReusablePrefix(1024, 1024))
        assertFalse("Unknown length does not make empty cache complete", WarmRecommendationStore.hasReusablePrefix(0, -1))
    }
}
