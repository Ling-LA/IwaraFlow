package com.ling.iwaraflow

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExtraLikeSignalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: AppPrefs
    private lateinit var history: HistoryStore
    private fun item() = VideoItem("one", "title", "Author", listOf("specific"), 30, authorId = "a")
    @Before fun open() { prefs = AppPrefs(context); account("one-account"); history = HistoryStore(context) }
    @After fun close() { history.close() }
    private fun account(id: String) { context.getSharedPreferences(AppPrefs.FILE, 0).edit().putString(AppPrefs.KEY_ACCOUNT_ID, id).commit() }
    private fun liked(item: VideoItem) {
        prefs.likeStates.confirm(prefs.likeStates.snapshot(), item.id, true, item.likes)
        prefs.likeStates.apply(item)
    }

    @Test fun onlyAlreadyLikedVideosGetOneSmallSignalWithoutChangingPublicLikeCount() {
        val item = item()
        assertFalse(history.recordExtraLike(item))
        liked(item)
        assertTrue(history.recordExtraLike(item))
        repeat(10) { assertFalse(history.recordExtraLike(item)) }
        assertEquals(30, item.likes)
        val profile = history.preferenceProfile()
        assertEquals(HistoryStore.EXTRA_LIKE_WEIGHT, profile.authorWeights.getValue("author"), 0.001)
        assertTrue(profile.tagWeights.getValue("specific") in 0.0..HistoryStore.EXTRA_LIKE_WEIGHT)
        val later = history.preferenceProfileAt(System.currentTimeMillis() + 30L * 86400000)
        assertEquals(HistoryStore.EXTRA_LIKE_WEIGHT / 2, later.authorWeights.getValue("author"), 0.001)
    }

    // Robolectric includes the method name in its temporary path; keep it short enough
    // for native SQLite on Windows when the isolated database adds its account hash.
    @Test fun onceAcrossReopenAndScopes() {
        val item = item(); liked(item)
        assertTrue(history.recordExtraLike(item))
        history.writableDatabase.delete("interactions", null, null)
        history.close(); history = HistoryStore(context)
        assertFalse(history.recordExtraLike(item))
        prefs.isolateInterests = true
        assertFalse(history.recordExtraLike(item))
        assertTrue(history.preferenceProfile().authorWeights.getValue("author") > 0)
        history.clearExtraLikeSignals()
        assertFalse(history.recordExtraLike(item))
        assertNull(history.preferenceProfile().authorWeights["author"])
    }

    @Test fun accountsHaveIndependentAllowanceAndNeverInheritEachOthersExtraSignal() {
        val item = item(); liked(item); assertTrue(history.recordExtraLike(item))
        account("two-account"); history.accountId = "two-account"
        assertNull(history.preferenceProfile().authorWeights["author"])
        assertFalse(history.recordExtraLike(item))
        liked(item); assertTrue(history.recordExtraLike(item))
        assertEquals(HistoryStore.EXTRA_LIKE_WEIGHT, history.preferenceProfile().authorWeights.getValue("author"), 0.001)
        account("one-account"); history.accountId = "one-account"
        assertFalse(history.recordExtraLike(item))
    }

    @Test fun privateBrowsingNeitherStoresSignalNorConsumesAllowance() {
        val item = item(); liked(item); prefs.privateBrowsing = true
        assertFalse(history.recordExtraLike(item))
        prefs.privateBrowsing = false
        assertTrue(history.recordExtraLike(item))
    }

    @Test fun versionElevenUpgradePreservesHistoryAndAddsOnceOnlyTable() {
        val item = item(); liked(item)
        history.recordWatch(item, 2000, 10000, false)
        history.writableDatabase.execSQL("DROP TABLE extra_like_signals")
        history.writableDatabase.version = 11
        history.close(); history = HistoryStore(context)
        assertEquals("one", history.recentHistory().single().id)
        assertTrue(history.recordExtraLike(item))
        assertEquals(12, history.writableDatabase.version)
    }
}
