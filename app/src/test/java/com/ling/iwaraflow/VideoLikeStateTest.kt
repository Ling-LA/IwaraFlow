package com.ling.iwaraflow

import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class VideoLikeStateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = AppPrefs(context)
    private fun video(liked: Boolean = false, count: Int = 10) = VideoItem("shared-video", "Example", "Creator", listOf("animation"), count, liked = liked)
    @Before fun reset() {
        context.getSharedPreferences(VideoLikeStore.FILE, 0).edit().clear().commit()
        context.getSharedPreferences(AppPrefs.FILE, 0).edit().clear().commit()
        prefs.accountId = "account-a"
    }
    @Test fun acknowledgedLikeAndUnlikeSurviveDifferentStoresAndStaleVideoCopies() {
        val first = VideoLikeStore(context); val second = VideoLikeStore(context)
        first.confirm(first.snapshot(), "shared-video", true, 11)
        assertTrue(second.apply(video()).liked)
        assertEquals(11, second.apply(video()).likes)
        second.confirm(second.snapshot(), "shared-video", false, 10)
        val reopened = VideoLikeStore(context).apply(video(true, 11))
        assertFalse(reopened.liked); assertEquals(10, reopened.likes)
    }
    @Test fun staleDetailAndFavoritesCannotUndoALocalAction() {
        val states = VideoLikeStore(context); val oldRequest = states.snapshot()
        states.confirm(states.snapshot(), "shared-video", true, 11)
        states.observe(oldRequest, video(false), detail = true, countKnown = true)
        assertTrue(states.apply(video()).liked)
        states.confirm(states.snapshot(), "shared-video", false, 10)
        states.observe(states.snapshot(), video(true, 11), detail = false, countKnown = true)
        assertFalse(states.apply(video(true)).liked)
    }
    @Test fun serverReplicaLagCannotUndoAnAcknowledgedWrite() {
        val states = VideoLikeStore(context)
        states.confirm(states.snapshot(), "shared-video", true, 11)
        states.observe(states.snapshot(), video(false, 10), detail = true, countKnown = true)
        assertTrue(states.apply(video()).liked)
    }
    @Test fun updatedCountsDoNotEndTheServerWriteGracePeriod() {
        val states = VideoLikeStore(context)
        states.confirm(states.snapshot(), "shared-video", true, 11)
        states.observe(states.snapshot(), video(true, 12), detail = true, countKnown = true)
        states.observe(states.snapshot(), video(false, 10), detail = true, countKnown = true)
        val current = states.apply(video())
        assertTrue(current.liked); assertEquals(12, current.likes)
    }
    @Test fun freshAuthoritativeDetailsCanUpdateAnOlderServerObservation() {
        val states = VideoLikeStore(context)
        states.observe(states.snapshot(), video(true, 11), detail = true, countKnown = true)
        states.observe(states.snapshot(), video(false, 10), detail = true, countKnown = true)
        assertFalse(states.apply(video(true)).liked)
        assertEquals(10, states.apply(video()).likes)
    }
    @Test fun accountSwitchAndLateRepliesDoNotLeakHearts() {
        val states = VideoLikeStore(context)
        states.confirm(states.snapshot(), "shared-video", true, 11)
        val cached = states.apply(video()); val pending = states.snapshot()
        prefs.accountId = "account-b"
        assertFalse(states.apply(cached).liked)
        assertFalse(states.confirm(pending, "shared-video", true, 11))
        assertFalse(states.apply(video()).liked)
        prefs.accountId = "account-a"
        assertTrue(states.apply(cached).liked)
        SecureSessionStore(context).clearAuthentication()
        assertFalse(states.confirm(pending, "shared-video", false, 10))
        assertTrue(states.apply(video()).liked)
    }
    @Test fun historyAndFavoritesRecoverTheSameStatusEvenInPrivateMode() {
        HistoryStore(context).use { history ->
            history.recordWatch(video(), 1000, 10000, false)
            history.setLocalFavorite(video(), true)
            prefs.privateBrowsing = true
            val states = VideoLikeStore(context)
            states.confirm(states.snapshot(), "shared-video", true, 11)
            assertTrue(history.recentHistory().first { it.id == "shared-video" }.liked)
            assertTrue(history.localFavorites().first { it.id == "shared-video" }.liked)
        }
    }
    @Test fun aReadCompletingDuringTheWriteCannotCancelASuccessfulAcknowledgement() {
        val states = VideoLikeStore(context)
        val request = states.snapshot()
        states.observe(request, video(false), detail = true, countKnown = true)
        assertTrue(states.confirm(request, "shared-video", true, 11))
        assertTrue(states.apply(video()).liked)
        assertEquals(11, states.apply(video()).likes)
    }
    @Test fun publicCountsCanRefreshWithoutClearingPersonalStatus() {
        val states = VideoLikeStore(context)
        states.observe(states.snapshot(), video(true, 11), detail = true, countKnown = true)
        states.observe(states.snapshot(), video(false, 25), detail = false, countKnown = true, statusKnown = false)
        val current = states.apply(video())
        assertTrue(current.liked); assertEquals(25, current.likes)
    }
    @Test fun APIAndCardAcknowledgementsDoNotIncrementCountsTwice() {
        val states = VideoLikeStore(context); val request = states.snapshot()
        states.confirm(request, "shared-video", true)
        states.confirm(request, "shared-video", true, 11)
        states.confirm(request, "shared-video", true, 11)
        assertEquals(11, states.apply(video()).likes)
        val unlike = states.snapshot()
        states.confirm(unlike, "shared-video", false)
        states.confirm(unlike, "shared-video", false, 10)
        assertEquals(10, states.apply(video()).likes)
    }
    @Test fun existingCardsOnOtherPagesUpdateWithoutRebindingOrResettingPlayback() {
        val histories = List(2) { HistoryStore(context) }
        val adapters = histories.map { history -> VideoAdapter(mock(IwaraApi::class.java), history, prefs,
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}) }
        try {
            val cards = adapters.map { adapter ->
                adapter.replace(listOf(video().apply { resumePositionMs = 5000; resumePlayWhenReady = false }))
                adapter.onCreateViewHolder(FrameLayout(context), 0).also { adapter.onBindViewHolder(it, 0) }
            }
            val states = VideoLikeStore(context)
            states.confirm(states.snapshot(), "shared-video", true, 11)
            shadowOf(Looper.getMainLooper()).idle()
            for (card in cards) assertEquals(R.drawable.ic_heart_rounded, card.itemView.findViewById<ImageView>(R.id.like).getTag(R.id.reaction_icon))
            states.confirm(states.snapshot(), "shared-video", false, 10)
            shadowOf(Looper.getMainLooper()).idle()
            for (card in cards) assertEquals(R.drawable.ic_heart_rounded_outline, card.itemView.findViewById<ImageView>(R.id.like).getTag(R.id.reaction_icon))
            assertTrue(adapters.all { it.items.single().resumePositionMs == 5000L && !it.items.single().resumePlayWhenReady })
        } finally { adapters.forEach { it.releaseAll() }; histories.forEach { it.close() } }
    }
    @Test fun upgradingImportsLikesEvenWhenTheOldSeenSyncTimestampIsRecent() {
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(true)
        `when`(api.getCurrentUserBlocking()).thenReturn(IwaraAuthor("account-a", "Sample", "sample", ""))
        `when`(api.getFavoritesPageBlocking(0)).thenReturn(FavoritesPage(emptyList(), 0, false))
        prefs.likedSyncAt = System.currentTimeMillis()
        HistoryStore(context).use { history ->
            val sync = LikedVideoSync(api, history, prefs)
            try {
                assertFalse(history.likeStates.hasImportedLikes())
                sync.syncIfStale()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!history.likeStates.hasImportedLikes() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(history.likeStates.hasImportedLikes())
                verify(api).getFavoritesPageBlocking(0)
            } finally { sync.close() }
        }
    }

}
