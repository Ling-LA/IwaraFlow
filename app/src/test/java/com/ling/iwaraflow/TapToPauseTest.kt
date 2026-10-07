package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「点一下画面暂停播放」可以关掉：关掉后点一下只在信息栏和播放控件之间切换，
 * 视频照常播。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TapToPauseTest {
    private fun adapter(): VideoAdapter {
        val context = RuntimeEnvironment.getApplication()
        return VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
    }

    private fun pin(holder: Any, pinned: Boolean) = holder.javaClass
        .getDeclaredMethod("setControlsPinned", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(holder, pinned)

    @Test fun tappingStillPausesUnlessTheSettingIsTurnedOff() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        assertTrue("默认行为不能变", prefs.tapToPause)
        prefs.tapToPause = false
        assertFalse(AppPrefs(RuntimeEnvironment.getApplication()).tapToPause)
        prefs.tapToPause = true
        assertTrue(AppPrefs(RuntimeEnvironment.getApplication()).tapToPause)
    }

    @Test fun theTapToggleReachesTheSeekBarAndThePlayButton() {
        val adapter = adapter()
        try {
            adapter.replace(listOf(VideoItem("tap-fixture", "Fixture", "Fixture", emptyList(), 0)))
            val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), 0)
            adapter.onBindViewHolder(holder, 0)
            val bar = holder.itemView.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            val button = holder.itemView.findViewById<PauseIndicatorView>(R.id.pauseIndicator)

            pin(holder, true)
            assertTrue(bar.controlsPinned)
            assertTrue(button.controlsPinned)

            holder.javaClass.getDeclaredMethod("setPausedControlsHidden", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(holder, true)
            // 卡片被回收复用到下一条视频：上一条点出来或隐藏的控件不跟着过去。
            adapter.onBindViewHolder(holder, 0)
            assertFalse(bar.hidePausedControls); assertFalse(button.hidePausedControls)
            assertFalse(bar.controlsPinned)
            assertFalse(button.controlsPinned)
        } finally { adapter.releaseAll() }
    }

    private fun withPlayer(paused: Boolean, block: (VideoAdapter.Holder, androidx.media3.exoplayer.ExoPlayer) -> Unit) {
        val adapter = adapter()
        try {
            adapter.replace(listOf(VideoItem("tap-fixture", "Fixture", "Fixture", emptyList(), 60, liked = true)))
            val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), 0)
            adapter.onBindViewHolder(holder, 0)
            val player = mock(androidx.media3.exoplayer.ExoPlayer::class.java)
            `when`(player.applicationLooper).thenReturn(android.os.Looper.getMainLooper())
            `when`(player.playbackState).thenReturn(androidx.media3.common.Player.STATE_READY)
            `when`(player.duration).thenReturn(60000L)
            `when`(player.playWhenReady).thenReturn(!paused)
            `when`(player.isPlaying).thenReturn(!paused)
            holder.javaClass.getDeclaredField("player").apply { isAccessible = true }.set(holder, player)
            holder.javaClass.getDeclaredField("active").apply { isAccessible = true }.set(holder, true)
            holder.itemView.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView).player = player
            val bar = holder.itemView.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            PauseSeekBar::class.java.getDeclaredMethod("refreshFromPlayer").apply { isAccessible = true }.invoke(bar)
            block(holder, player)
        } finally { adapter.releaseAll() }
    }
    private fun tap(holder: VideoAdapter.Holder) {
        holder.itemView.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
    }
    @Test fun disabledTapToPauseCanSwitchBothPanelsWhileStayingPaused() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication()); prefs.tapToPause = false
        try { withPlayer(true) { holder, player ->
            val root = holder.itemView
            val bar = root.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            assertEquals(View.VISIBLE, bar.visibility)
            tap(holder)
            assertEquals(View.GONE, bar.visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.infoPanel).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.actionPanel).visibility)
            tap(holder)
            assertEquals(View.VISIBLE, bar.visibility)
            assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.infoPanel).visibility)
            verify(player, never()).play(); verify(player, never()).pause()
        } } finally { prefs.tapToPause = true }
    }
    @Test fun disabledTapToPauseStillSwitchesPanelsWhilePlaying() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication()); prefs.tapToPause = false
        try { withPlayer(false) { holder, player ->
            val bar = holder.itemView.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            tap(holder); assertEquals(View.VISIBLE, bar.visibility)
            tap(holder); assertEquals(View.GONE, bar.visibility)
            verify(player, never()).play(); verify(player, never()).pause()
        } } finally { prefs.tapToPause = true }
    }
    @Test fun enabledTapToPauseKeepsSingleTapResumeBehavior() {
        AppPrefs(RuntimeEnvironment.getApplication()).tapToPause = true
        withPlayer(true) { holder, player -> tap(holder); verify(player).play() }
    }

    /** 没有播放器（卡片还没开始播）时点出来的控件不能冒出来，也不能崩。 */
    @Test fun pinnedControlsStayHiddenWithoutAPlayer() {
        val adapter = adapter()
        try {
            adapter.replace(listOf(VideoItem("tap-fixture", "Fixture", "Fixture", emptyList(), 0)))
            val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), 0)
            adapter.onBindViewHolder(holder, 0)
            val root = holder.itemView
            val bar = root.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            val button = root.findViewById<PauseIndicatorView>(R.id.pauseIndicator)

            pin(holder, true)
            assertEquals(View.GONE, bar.visibility)
            assertEquals(View.GONE, button.visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.pauseControls).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.infoPanel).visibility)
        } finally { adapter.releaseAll() }
    }
}
