package com.ling.iwaraflow

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullscreenGesturesTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun set(owner: Any, key: String, value: Any) = owner.javaClass.getDeclaredField(key).apply { isAccessible = true }.set(owner, value)

    @Test fun horizontalAndVerticalVideosUseTheirOwnThreeZones() {
        val expected = FullscreenGesture.Action.entries
        assertEquals(expected, listOf(100f, 450f, 800f).map { FullscreenGesture.action(it, 200f, 900, 450, true) })
        assertEquals(expected, listOf(100f, 450f, 800f).map { FullscreenGesture.action(200f, it, 450, 900, false) })
        assertEquals(FullscreenGesture.Action.DOUBLE_REACTION, FullscreenGesture.action(300f, 0f, 900, 450, true))
    }
    @Test fun screenRingCompletesOnlyAfterThreeSecondsAndOncePerHold() {
        val view = View(RuntimeEnvironment.getApplication()); view.layout(0, 0, 900, 450)
        var completed = 0
        val hold = ScreenReactionHold(view) { completed++ }
        hold.start(450f, 225f); idle(2999); assertEquals(0, completed)
        idle(1); assertEquals(1, completed); idle(4000); assertEquals(1, completed)
        hold.cancel()
    }
    @Test fun cancelledScreenRingCanRestartButNeverCompletesTheOldHold() {
        val view = View(RuntimeEnvironment.getApplication()); view.layout(0, 0, 900, 450)
        var completed = 0
        val hold = ScreenReactionHold(view) { completed++ }
        hold.start(450f, 225f); idle(2000); hold.cancel(); idle(4000); assertEquals(0, completed)
        hold.start(450f, 225f); idle(3000); assertEquals(1, completed); hold.cancel()
    }
    private fun fixture(block: (VideoAdapter, VideoAdapter.Holder, VideoItem, ExoPlayer) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val api = mock(IwaraApi::class.java); `when`(api.isLoggedIn()).thenReturn(true)
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        val item = VideoItem("fixture", "测试作品", "作者", emptyList(), 60, liked = true)
        adapter.replace(listOf(item))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0); adapter.onBindViewHolder(holder, 0)
        adapter.setFullscreen(true)
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY))
        holder.itemView.layout(0, 0, 900, 450)
        val player = mock(ExoPlayer::class.java); `when`(player.playbackState).thenReturn(Player.STATE_READY)
        set(holder, "active", true); set(holder, "player", player)
        try { block(adapter, holder, item, player) } finally { adapter.releaseAll() }
    }
    @Test fun middleHoldAddsFavoriteWithoutPausingOrTogglingAnExistingLikeOff() = fixture { _, holder, item, player ->
        touch(holder.itemView, MotionEvent.ACTION_DOWN, 450f, 180f); idle(2999)
        assertFalse(item.localFavorite); idle(1); assertTrue(item.localFavorite); assertTrue(item.liked)
        touch(holder.itemView, MotionEvent.ACTION_UP, 450f, 180f); idle(400)
        verify(player, never()).pause()
    }
    @Test fun movingOrLeavingFullscreenCancelsPendingDoubleReaction() = fixture { adapter, holder, item, _ ->
        val root = holder.itemView
        touch(root, MotionEvent.ACTION_DOWN, 450f, 180f); idle(800)
        touch(root, MotionEvent.ACTION_MOVE, 450f, 240f); idle(3000); assertFalse(item.localFavorite)
        touch(root, MotionEvent.ACTION_UP, 450f, 240f)
        touch(root, MotionEvent.ACTION_DOWN, 450f, 180f); idle(800)
        adapter.setFullscreen(false); idle(3000); assertFalse(item.localFavorite)
    }
    @Test fun rightSideSpeedsUpUntilReleaseWithoutAddingFavorite() = fixture { _, holder, item, player ->
        touch(holder.itemView, MotionEvent.ACTION_DOWN, 820f, 180f); idle(450)
        verify(player).setPlaybackSpeed(2f); assertFalse(item.localFavorite)
        touch(holder.itemView, MotionEvent.ACTION_UP, 820f, 180f)
        verify(player, atLeastOnce()).setPlaybackSpeed(1f)
    }
    @Test fun landscapePanelLeavesMostOfVideoVisibleAndPortraitIsHeightLimited() {
        val landscape = DislikeSheet.panelSize(1920, 1080, 3f)
        assertTrue(landscape.first < 1920/2); assertTrue(landscape.second < 1080)
        val portrait = DislikeSheet.panelSize(1080, 1920, 3f)
        assertTrue(portrait.first < 1080); assertTrue(portrait.second <= (1920*.7).toInt())
    }
    @Test fun guideAppearsOncePerOrientationAndCanBeOpenedAgainFromSettings() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get(); val root = activity.window.decorView as android.view.ViewGroup
        try {
            activity.getSharedPreferences(AppPrefs.FILE, 0).edit().remove(PlaybackGuide.key(true)).remove(PlaybackGuide.key(false)).commit()
            PlaybackGuide.showOnce(activity, true)
            assertNotNull(root.findViewWithTag<View>("playback_gesture_guide"))
            PlaybackGuide.dismiss(activity); PlaybackGuide.showOnce(activity, true)
            assertNull(root.findViewWithTag<View>("playback_gesture_guide"))
            PlaybackGuide.showOnce(activity, false); assertNotNull(root.findViewWithTag<View>("playback_gesture_guide"))
            PlaybackGuide.dismiss(activity); PlaybackGuide.show(activity, true)
            assertNotNull(root.findViewWithTag<View>("playback_gesture_guide"))
        } finally { PlaybackGuide.dismiss(activity); controller.pause().stop().destroy() }
    }
    @Test fun fullscreenAnimationIsLargeAndCenteredAtThePressPoint() {
        val context = RuntimeEnvironment.getApplication(); val density = context.resources.displayMetrics.density
        val view = View(context); view.layout(0, 0, (800*density).toInt(), (360*density).toInt())
        val hold = ScreenReactionHold(view) {}
        hold.start(400*density, 180*density)
        val drawable = hold.javaClass.getDeclaredField("ring").apply { isAccessible = true }.get(hold) as android.graphics.drawable.Drawable
        assertTrue(drawable.bounds.width() >= 240*density)
        assertEquals(400*density, drawable.bounds.exactCenterX(), 1f)
        assertEquals(180*density, drawable.bounds.exactCenterY(), 1f)
        hold.cancel()
    }
}
