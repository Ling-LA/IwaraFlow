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
 * 暂停时画面中央那个播放图标可以在设置里关掉——有人嫌它挡画面。
 * 关掉之后不管播放器是什么状态都不该冒出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PauseIndicatorTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Suppress("UNCHECKED_CAST")
    private fun holderOf(adapter: VideoAdapter): VideoAdapter.Holder =
        (VideoAdapter::class.java.getDeclaredField("holders")
            .apply { isAccessible = true }.get(adapter) as Set<VideoAdapter.Holder>).first()

    private fun card(prefs: AppPrefs): Pair<VideoAdapter, View> {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), prefs,
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}
        )
        adapter.replace(listOf(VideoItem("v1", "Fixture", "作者", emptyList(), 0)))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        return adapter to holder.itemView
    }

    private fun refresh(view: PauseIndicatorView) = PauseIndicatorView::class.java
        .getDeclaredMethod("refreshState")
        .apply { isAccessible = true }.invoke(view)

    @Test fun theIndicatorIsOnByDefault() {
        assertTrue("默认行为不能变", AppPrefs(context).showPauseIndicator)
    }

    @Test fun theSettingSurvivesBeingWrittenAndReadBack() {
        val prefs = AppPrefs(context)
        prefs.showPauseIndicator = false
        assertFalse(AppPrefs(context).showPauseIndicator)
        prefs.showPauseIndicator = true
        assertTrue(AppPrefs(context).showPauseIndicator)
    }

    @Test fun aCardPicksUpTheSettingWhenItBinds() {
        val prefs = AppPrefs(context)
        prefs.showPauseIndicator = false
        val (adapter, root) = card(prefs)
        try {
            val indicator = root.findViewById<PauseIndicatorView>(R.id.pauseIndicator)
            assertFalse("关掉之后卡片也要照办", indicator.indicatorEnabled)
        } finally {
            adapter.releaseAll()
            prefs.showPauseIndicator = true
        }
    }

    @Test fun switchingItOffHidesTheIndicatorImmediately() {
        val prefs = AppPrefs(context)
        val (adapter, root) = card(prefs)
        try {
            val indicator = root.findViewById<PauseIndicatorView>(R.id.pauseIndicator)
            assertTrue("默认是开着的", indicator.indicatorEnabled)
            indicator.visibility = View.VISIBLE
            indicator.indicatorEnabled = false
            assertEquals("关掉就该立刻收起来，不用等下一次轮询", View.GONE, indicator.visibility)
        } finally { adapter.releaseAll() }
    }

    /**
     * 保存设置不该把正在看的视频重新加载一遍。只影响显示的设置直接推给已绑定的卡片，
     * 既不用重新绑定（那会 release 掉播放器、重新缓冲），更不用重拉整个列表。
     */
    @Test fun applyingDisplayPrefsUpdatesBoundCardsWithoutTouchingThePlayer() {
        val prefs = AppPrefs(context)
        val (adapter, root) = card(prefs)
        try {
            val indicator = root.findViewById<PauseIndicatorView>(R.id.pauseIndicator)
            val holder = holderOf(adapter)
            val player = mock(androidx.media3.exoplayer.ExoPlayer::class.java)
            holder.javaClass.getDeclaredField("player")
                .apply { isAccessible = true }.set(holder, player)

            prefs.showPauseIndicator = false
            adapter.applyDisplayPrefs()

            assertFalse("设置要推到已经绑好的卡片上", indicator.indicatorEnabled)
            assertSame("不该动播放器——那会顿一下还得重新缓冲", player,
                holder.javaClass.getDeclaredField("player")
                    .apply { isAccessible = true }.get(holder))
        } finally {
            adapter.releaseAll()
            prefs.showPauseIndicator = true
        }
    }

    @Test fun whileOffItStaysHiddenNoMatterWhatThePlayerDoes() {
        val prefs = AppPrefs(context)
        val (adapter, root) = card(prefs)
        try {
            val indicator = root.findViewById<PauseIndicatorView>(R.id.pauseIndicator)
            indicator.indicatorEnabled = false
            repeat(3) {
                indicator.visibility = View.VISIBLE
                refresh(indicator)
                assertEquals(View.GONE, indicator.visibility)
            }
        } finally { adapter.releaseAll() }
    }
}
