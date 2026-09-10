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
 * 暂停时进度条要盖住信息栏和操作栏，否则拖动进度条会误触点赞 / 下载 / 分享。
 * 进度条本身必须挂在卡片根布局上，跟着信息栏一起被隐藏就没得拖了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PauseChromeTest {
    private fun card(): Pair<VideoAdapter, View> {
        val context = RuntimeEnvironment.getApplication()
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(VideoItem("seek-fixture", "Fixture", "Fixture", emptyList(), 0)))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        return adapter to holder.itemView
    }

    private fun hideChrome(bar: PauseSeekBar, root: View) = PauseSeekBar::class.java
        .getDeclaredMethod("hideChrome", View::class.java)
        .apply { isAccessible = true }.invoke(bar, root)

    private fun restoreChrome(bar: PauseSeekBar) = PauseSeekBar::class.java
        .getDeclaredMethod("restoreChrome")
        .apply { isAccessible = true }.invoke(bar)

    @Test fun theSeekBarLivesOnTheCardRootSoHidingTheInfoPanelKeepsIt() {
        val (adapter, root) = card()
        try {
            val bar = root.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            assertSame("进度条必须直接挂在卡片根布局上", root, bar.parent)
        } finally { adapter.releaseAll() }
    }

    @Test fun pausedChromeIsHiddenAndComesBackWhenPlaybackResumes() {
        val (adapter, root) = card()
        try {
            val bar = root.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            val info = root.findViewById<View>(R.id.infoPanel)
            val actions = root.findViewById<View>(R.id.actionPanel)

            hideChrome(bar, root)
            assertEquals(View.INVISIBLE, info.visibility)
            assertEquals(View.INVISIBLE, actions.visibility)

            restoreChrome(bar)
            assertEquals(View.VISIBLE, info.visibility)
            assertEquals(View.VISIBLE, actions.visibility)
        } finally { adapter.releaseAll() }
    }

    @Test fun aRebindThatShowsTheChromeAgainIsCoveredOnTheNextTick() {
        val (adapter, root) = card()
        try {
            val bar = root.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            val info = root.findViewById<View>(R.id.infoPanel)
            hideChrome(bar, root)
            // RecyclerView 复用卡片后适配器会重新把控件显示出来。
            info.visibility = View.VISIBLE
            hideChrome(bar, root)
            assertEquals(View.INVISIBLE, info.visibility)
            restoreChrome(bar)
            assertEquals(View.VISIBLE, info.visibility)
        } finally { adapter.releaseAll() }
    }
}
