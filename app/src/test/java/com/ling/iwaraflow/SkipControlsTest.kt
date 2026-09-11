package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 暂停时左下角的控制行：后退 N 秒 / 播放三角 / 前进 N 秒。N 在设置里改。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkipControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Suppress("UNCHECKED_CAST")
    private fun holderOf(adapter: VideoAdapter): VideoAdapter.Holder =
        (VideoAdapter::class.java.getDeclaredField("holders")
            .apply { isAccessible = true }.get(adapter) as Set<VideoAdapter.Holder>).first()

    private fun card(prefs: AppPrefs): Triple<VideoAdapter, View, ExoPlayer> {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), prefs,
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}
        )
        adapter.replace(listOf(VideoItem("v1", "Fixture", "作者", emptyList(), 0)))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        val player = mock(ExoPlayer::class.java)
        holderOf(adapter).javaClass.getDeclaredField("player").apply { isAccessible = true }.set(holder, player)
        return Triple(adapter, holder.itemView, player)
    }

    @Test fun theDefaultSkipIsFifteenSeconds() {
        assertEquals(15, AppPrefs(context).skipSeconds)
        assertEquals(15, AppPrefs.DEFAULT_SKIP_SECONDS)
    }

    @Test fun theButtonsShowTheConfiguredSeconds() {
        val prefs = AppPrefs(context)
        prefs.skipSeconds = 30
        val (adapter, root, _) = card(prefs)
        try {
            assertEquals("30", root.findViewById<TextView>(R.id.skipBack).text.toString())
            assertEquals("30", root.findViewById<TextView>(R.id.skipForward).text.toString())
            prefs.skipSeconds = 5
            adapter.applyDisplayPrefs()
            assertEquals("改了设置要立刻反映到已绑定的卡片上", "5", root.findViewById<TextView>(R.id.skipForward).text.toString())
        } finally { adapter.releaseAll(); prefs.skipSeconds = 15 }
    }

    @Test fun forwardSeeksAheadByTheConfiguredAmount() {
        val prefs = AppPrefs(context)
        val (adapter, root, player) = card(prefs)
        try {
            `when`(player.currentPosition).thenReturn(30_000L)
            `when`(player.duration).thenReturn(100_000L)
            root.findViewById<View>(R.id.skipForward).performClick()
            verify(player).seekTo(45_000L)
        } finally { adapter.releaseAll() }
    }

    @Test fun backwardsNeverGoesBeforeTheStart() {
        val prefs = AppPrefs(context)
        val (adapter, root, player) = card(prefs)
        try {
            `when`(player.currentPosition).thenReturn(10_000L)
            `when`(player.duration).thenReturn(100_000L)
            root.findViewById<View>(R.id.skipBack).performClick()
            verify(player).seekTo(0L)
        } finally { adapter.releaseAll() }
    }

    @Test fun forwardNeverGoesPastTheEnd() {
        val prefs = AppPrefs(context)
        val (adapter, root, player) = card(prefs)
        try {
            `when`(player.currentPosition).thenReturn(95_000L)
            `when`(player.duration).thenReturn(100_000L)
            root.findViewById<View>(R.id.skipForward).performClick()
            verify(player).seekTo(100_000L)
        } finally { adapter.releaseAll() }
    }

    @Test fun theControlsRowSitsOnTheCardRootNextToTheSeekBar() {
        val (adapter, root, _) = card(AppPrefs(context))
        try {
            val row = root.findViewById<View>(R.id.pauseControls)
            assertSame("控制行要挂在卡片根布局上，进度条才能给它定位", root, row.parent)
            assertEquals("默认收起", View.GONE, row.visibility)
            assertNotNull(root.findViewById<PauseIndicatorView>(R.id.pauseIndicator))
        } finally { adapter.releaseAll() }
    }
}
