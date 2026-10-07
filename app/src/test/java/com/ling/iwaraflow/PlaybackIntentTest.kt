package com.ling.iwaraflow

import android.os.Looper
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise real Player callbacks so explicit pauses differ from temporary lifecycle pauses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackIntentTest {
    private fun set(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun player(holder: VideoAdapter.Holder) = holder.javaClass.getDeclaredField("player")
        .apply { isAccessible = true }.get(holder) as ExoPlayer
    private fun fixture(block: (VideoAdapter, VideoAdapter.Holder, VideoItem) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val source = VideoSource("fixture", "file:///fixture.mp4", 1)
        val item = VideoItem("paused-fixture", "Fixture", "Author", emptyList(), 0, sources = listOf(source))
        val cache = mock(MediaPreloadCache::class.java)
        `when`(cache.progress(nullable(String::class.java))).thenReturn(MediaPreloadCache.Progress())
        `when`(cache.createMediaSource(anyString())).thenAnswer {
            SilenceMediaSource.Factory().setDurationUs(60_000_000L).createMediaSource()
        }
        val api = mock(IwaraApi::class.java)
        `when`(api.chooseSource(anyList(), anyString())).thenReturn(source)
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(context), cache, { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(item))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        set(adapter, "playbackEnabled", true)
        holder.setActive(true)
        try { block(adapter, holder, item) } finally { adapter.releaseAll() }
    }
    private fun resume(adapter: VideoAdapter, holder: VideoAdapter.Holder) {
        set(adapter, "playbackEnabled", true)
        holder.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun explicitPauseSurvivesRepeatedSuspensionWithTheSamePlayer() = fixture { adapter, holder, item ->
        val p = player(holder)
        p.pause()
        assertFalse(item.resumePlayWhenReady)
        repeat(3) {
            adapter.suspendPlayback(); adapter.suspendPlayback()
            resume(adapter, holder)
            assertSame(p, player(holder))
            assertFalse(p.playWhenReady)
        }
        p.play(); assertTrue(item.resumePlayWhenReady)
        adapter.suspendPlayback(); resume(adapter, holder)
        assertTrue(p.playWhenReady)
    }
    @Test fun pauseSurvivesBackgroundReleaseAndRecyclerRebind() = fixture { adapter, holder, item ->
        val old = player(holder)
        old.pause()
        adapter.suspendPlayback(); adapter.pauseAll()
        adapter.onBindViewHolder(holder, 0)
        resume(adapter, holder)
        assertNotSame(old, player(holder))
        assertFalse(player(holder).playWhenReady)
        assertFalse(item.resumePlayWhenReady)
    }
    @Test fun temporaryStopDoesNotTurnPlayingVideoIntoUserPausedVideo() = fixture { adapter, holder, item ->
        assertTrue(player(holder).playWhenReady)
        adapter.suspendPlayback(); adapter.pauseAll()
        assertTrue(item.resumePlayWhenReady)
        resume(adapter, holder)
        assertTrue(player(holder).playWhenReady)
    }
    @Test fun guideAndRecreationPreservePausedIntent() = fixture { adapter, holder, item ->
        player(holder).pause()
        adapter.setGuideVisible(true)
        adapter.suspendPlayback(); adapter.pauseAll()
        resume(adapter, holder)
        adapter.setGuideVisible(false)
        assertFalse(player(holder).playWhenReady)
        assertFalse(item.resumePlayWhenReady)
    }
    @Test fun sessionSnapshotRetainsPauseAndOldSnapshotsStillAutoplay() {
        val paused = VideoItem("snapshot", "Fixture", "Author", emptyList(), 0, resumePlayWhenReady = false)
        val text = FeedSessionCodec.encode(FeedSessionStore.Session(listOf(paused), 0, 0, false))
        assertFalse(FeedSessionCodec.decode(text)!!.items.single().resumePlayWhenReady)
        val old = org.json.JSONObject(text)
        old.getJSONArray("items").getJSONObject(0).remove("playWhenReady")
        assertTrue(FeedSessionCodec.decode(old.toString())!!.items.single().resumePlayWhenReady)
    }
}
