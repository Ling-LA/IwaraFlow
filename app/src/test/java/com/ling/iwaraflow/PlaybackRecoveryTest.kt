package com.ling.iwaraflow

import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Iwara 的播放地址带 expires，放久了会过期，播放器报错后停在黑屏。
 * 出错时必须丢掉过期地址、重新解析后再放，而不是一直黑着等用户手动划走。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackRecoveryTest {
    private val staleSource = VideoSource("Source", "https://cdn.example/expired?expires=1", 10_000)

    private fun expiredItem() = VideoItem("expired-1", "Fixture", "作者", emptyList(), 0).apply {
        sources = listOf(staleSource)
        streamUrl = staleSource.url
    }

    private class Fixture(val adapter: VideoAdapter, val holder: VideoAdapter.Holder)

    private fun bindActiveCard(item: VideoItem): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(item))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        set(holder, "player", mock(ExoPlayer::class.java))
        set(holder, "active", true)
        return Fixture(adapter, holder)
    }

    private fun set(target: Any, name: String, value: Any?) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun reportError(holder: VideoAdapter.Holder, item: VideoItem) {
        holder.javaClass
            .getDeclaredMethod("refreshSourceAfterError", VideoItem::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(holder, item, "ERROR_CODE_IO_BAD_HTTP_STATUS")
    }

    private fun titleOf(holder: VideoAdapter.Holder): String =
        holder.itemView.findViewById<TextView>(R.id.title).text.toString()

    @Test fun aPlaybackErrorThrowsAwayTheExpiredAddress() {
        val item = expiredItem()
        val fixture = bindActiveCard(item)
        try {
            reportError(fixture.holder, item)
            assertNull("过期地址必须丢掉，否则重试还是同一个地址", item.streamUrl)
            assertNull(item.sources)
            assertEquals(1, get(fixture.holder, "recoveryAttempts"))
            assertFalse("还在重试时不该先把标题写成失败", titleOf(fixture.holder).contains("播放失败"))
        } finally { fixture.adapter.releaseAll() }
    }

    @Test fun aVideoThatKeepsFailingStopsRetryingAndSaysWhy() {
        val item = expiredItem()
        val fixture = bindActiveCard(item)
        try {
            set(fixture.holder, "recoveryAttempts", VideoAdapter.MAX_ERROR_RECOVERIES)
            reportError(fixture.holder, item)
            assertTrue("放不了的视频要给出原因：${titleOf(fixture.holder)}",
                titleOf(fixture.holder).contains("播放失败"))
            assertEquals("到上限后不再重新解析", staleSource.url, item.streamUrl)
        } finally { fixture.adapter.releaseAll() }
    }

    @Test fun rebindingACardClearsTheRetryBudget() {
        val item = expiredItem()
        val fixture = bindActiveCard(item)
        try {
            set(fixture.holder, "recoveryAttempts", VideoAdapter.MAX_ERROR_RECOVERIES)
            fixture.adapter.onBindViewHolder(fixture.holder, 0)
            assertEquals("重新绑定卡片后重试次数要清零", 0, get(fixture.holder, "recoveryAttempts"))
        } finally { fixture.adapter.releaseAll() }
    }
}
