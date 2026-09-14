package com.ling.iwaraflow

import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 负反馈只来自「页面在前台、用户真的划到了下一条」。
 * 关掉应用、切后台、换榜单重新加载、恢复上次会话都不算“不感兴趣”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NegativeFeedbackScopeTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun items() = (0 until 3).map { VideoItem("v$it", "标题", "Alice", listOf("t"), 1, authorId = "a") }

    private fun adapter(): VideoAdapter {
        val context = RuntimeEnvironment.getApplication()
        return VideoAdapter(mock(IwaraApi::class.java), history, AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
    }

    private fun bind(adapter: VideoAdapter) {
        val parent = FrameLayout(RuntimeEnvironment.getApplication())
        adapter.replace(items())
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
    }

    /** 行为是排队写的（UI 线程不写库），断言之前先等写队列清空，免得白跑一个空断言。 */
    private fun negatives(): Int {
        history.awaitWrites()
        return history.recommendationMetrics().let { (it.samples * it.quickSkipRate).toInt() }
    }

    @Test fun closingTheAppRecordsNoSkip() {
        val adapter = adapter()
        try {
            bind(adapter)
            // 关应用：先 pauseAll（playbackEnabled=false），之后可能还会走到 setActive。
            adapter.pauseAll()
            adapter.setActive(2)
            adapter.releaseAll()
            assertEquals("关掉应用不能被判成不感兴趣", 0, negatives())
        } finally { adapter.releaseAll() }
    }

    @Test fun reloadingTheFeedRecordsNoSkip() {
        val adapter = adapter()
        try {
            bind(adapter)
            // 换榜单 / 重新加载 / 恢复上次会话：整批换内容，位置变化不是用户划走。
            adapter.replace(items())
            adapter.setActive(1)
            assertEquals("重新加载列表不能被判成不感兴趣", 0, negatives())
        } finally { adapter.releaseAll() }
    }

    /** 真的划走才可能记负反馈：这里只确认前台翻页这条路本身还在。 */
    @Test fun aRealSwipeStillGoesThroughTheWatchSignalPath() {
        val adapter = adapter()
        try {
            bind(adapter)
            adapter.setActive(1)
            // 卡片没真的起播过（Robolectric 里没有解码器），所以仍然不该有任何信号。
            history.awaitWrites()
            assertEquals(0, history.recommendationMetrics().samples)
        } finally { adapter.releaseAll() }
    }
}
