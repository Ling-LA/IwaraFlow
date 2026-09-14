package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
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

            // 卡片被回收复用到下一条视频：上一条点出来的控件不跟着过去。
            adapter.onBindViewHolder(holder, 0)
            assertFalse(bar.controlsPinned)
            assertFalse(button.controlsPinned)
        } finally { adapter.releaseAll() }
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
