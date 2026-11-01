package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class FullscreenSocialTest {
    private fun idle(ms: Long = 160) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun set(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)

    private class Fixture(val adapter: VideoAdapter, val holder: VideoAdapter.Holder,
        val video: VideoItem, val player: ExoPlayer, val prefs: AppPrefs, val parent: FrameLayout) {
        val root get() = holder.itemView
        var opened: VideoItem? = null
        fun playing(value: Boolean) {
            `when`(player.isPlaying).thenReturn(value)
            `when`(player.playWhenReady).thenReturn(value)
        }
        fun controls(visible: Boolean, fullscreen: Boolean = true) {
            val expected = if (visible) View.VISIBLE else View.GONE
            assertEquals("Transport visibility", expected, root.findViewById<View>(R.id.pauseSeekBar).visibility)
            assertEquals("Social controls must follow transport controls", if (visible && fullscreen) View.VISIBLE else View.GONE,
                root.findViewById<View>(R.id.fullscreenSocial).visibility)
            assertEquals("Bottom control row", expected, root.findViewById<View>(R.id.pauseTopRow).visibility)
        }
    }

    private fun fixture(width: Int = 450, height: Int = 900, block: (Fixture) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val context = activity.get()
        val prefs = AppPrefs(context).apply { tapToPause = true }
        val parent = FrameLayout(context)
        context.setContentView(parent)
        lateinit var f: Fixture
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java), prefs,
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}, onComments = { f.opened = it })
        val video = VideoItem("social", "作品", "作者", emptyList(), 345)
        adapter.replace(listOf(video))
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        parent.addView(holder.itemView)
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, width, height)
        val player = mock(ExoPlayer::class.java)
        `when`(player.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(player.playbackState).thenReturn(Player.STATE_READY)
        `when`(player.duration).thenReturn(60_000L)
        set(holder, "player", player)
        set(holder, "active", true)
        holder.itemView.findViewById<PlayerView>(R.id.playerView).player = player
        f = Fixture(adapter, holder, video, player, prefs, parent)
        f.playing(true)
        try { block(f) } finally {
            adapter.releaseAll()
            prefs.tapToPause = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun portraitAndLandscapeSocialFollowPauseResumeAndPlaybackAvailability() {
        for ((width, height) in listOf(450 to 900, 900 to 450)) fixture(width, height) { f ->
            f.adapter.setFullscreen(true)
            f.controls(false)
            f.playing(false); idle(); f.controls(true)
            // The play-icon preference must not hide the rest of the control set.
            f.root.findViewById<PauseIndicatorView>(R.id.pauseIndicator).indicatorEnabled = false
            idle(); f.controls(true)
            f.playing(true); idle(); f.controls(false)
            f.playing(false); idle(); f.controls(true)
            for (state in listOf(Player.STATE_IDLE, Player.STATE_ENDED)) {
                `when`(f.player.playbackState).thenReturn(state)
                idle(); f.controls(false)
            }
        }
    }

    @Test fun singleTapTogglesFullscreenSocialWithTransportWithoutChangingPausedOrPlayingIntent() {
        for ((width, height) in listOf(450 to 900, 900 to 450)) fixture(width, height) { f ->
            f.prefs.tapToPause = false
            f.adapter.setFullscreen(true)
            for (playing in listOf(true, false)) {
                f.playing(playing); idle()
                f.controls(!playing)
                f.root.performClick(); idle(350); f.controls(playing)
                f.root.performClick(); idle(350); f.controls(!playing)
            }
            verify(f.player, never()).play()
            verify(f.player, never()).pause()
        }
    }

    @Test fun fullscreenCommentsAndSyncedCountRemainAvailableOnlyWithTheControls() = fixture { f ->
        f.playing(false)
        f.adapter.setFullscreen(true)
        f.controls(true)
        assertFalse(f.root.findViewById<View>(R.id.actionPanel).isShown)
        assertFalse(f.root.findViewById<View>(R.id.fullscreenLikeCount).isClickable)
        assertTrue(f.root.findViewById<TextView>(R.id.fullscreenLikeCount).text.contains("345"))
        f.root.findViewById<View>(R.id.fullscreenComments).performClick()
        assertSame(f.video, f.opened)
        f.video.likes = 346; f.adapter.refreshItem(f.video.id)
        assertTrue(f.root.findViewById<TextView>(R.id.fullscreenLikeCount).text.contains("346"))
        f.adapter.setPipMode(true); f.controls(false)
        f.adapter.setPipMode(false); f.controls(true)
        f.adapter.setFullscreen(false, resumeOnExit = false); f.controls(true, fullscreen = false)
    }

    @Test fun recyclingAndDetachingImmediatelyHideSocialAndRebindingDoesNotCarryPinnedState() = fixture { f ->
        f.prefs.tapToPause = false
        f.adapter.setFullscreen(true)
        f.root.performClick(); idle(350); f.controls(true)
        f.parent.removeView(f.root)
        f.controls(false)
        f.parent.addView(f.root); idle(); f.controls(false)
        f.playing(false); idle(); f.controls(true)
        f.adapter.onViewRecycled(f.holder)
        f.controls(false)
        f.adapter.onBindViewHolder(f.holder, 0)
        f.controls(false)
        assertFalse(f.root.findViewById<PauseSeekBar>(R.id.pauseSeekBar).controlsPinned)
    }
}
