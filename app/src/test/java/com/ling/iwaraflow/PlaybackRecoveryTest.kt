package com.ling.iwaraflow

import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Iwara 的播放地址带 expires，放久了会过期，播放器报错后停在黑屏。
 * 出错时必须重新解析地址再放，而不是一直黑着等用户手动划走。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackRecoveryTest {
    private val staleSource = VideoSource("Source", "https://cdn.example/expired?expires=1", 10_000)
    private val freshSource = VideoSource("Source", "https://cdn.example/fresh?expires=2", 10_000)

    private fun expiredItem() = VideoItem("expired-1", "Fixture", "作者", emptyList(), 0).apply {
        sources = listOf(staleSource)
        streamUrl = staleSource.url
    }

    private class Fixture(
        val adapter: VideoAdapter,
        val holder: VideoAdapter.Holder,
        val player: ExoPlayer
    )

    private fun bindActiveCard(item: VideoItem, freshSources: List<VideoSource>): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val api = mock(IwaraApi::class.java)
        doAnswer { invocation ->
            invocation.getArgument<(Result<List<VideoSource>>) -> Unit>(1).invoke(Result.success(freshSources))
            null
        }.`when`(api).resolveSources(anyString(), any())
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(item))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        val player = mock(ExoPlayer::class.java)
        set(holder, "player", player)
        set(holder, "active", true)
        return Fixture(adapter, holder, player)
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
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aPlaybackErrorAsksForAFreshAddressAndPlaysItAgain() {
        val item = expiredItem()
        val fixture = bindActiveCard(item, listOf(freshSource))
        try {
            reportError(fixture.holder, item)
            assertEquals(listOf(freshSource), item.sources)
            assertEquals("必须换成新解析出来的地址", freshSource.url, item.streamUrl)
            verify(fixture.player, atLeastOnce()).prepare()
        } finally { fixture.adapter.releaseAll() }
    }

    @Test fun aVideoThatKeepsFailingStopsRetryingAndSaysWhy() {
        val item = expiredItem()
        val fixture = bindActiveCard(item, listOf(freshSource))
        try {
            set(fixture.holder, "recoveryAttempts", VideoAdapter.MAX_ERROR_RECOVERIES)
            reportError(fixture.holder, item)
            val title = fixture.holder.itemView.findViewById<TextView>(R.id.title)
            assertTrue("放不了的视频要给出原因：${title.text}", title.text.contains("播放失败"))
        } finally { fixture.adapter.releaseAll() }
    }

    @Test fun rebindingACardClearsTheRetryBudget() {
        val item = expiredItem()
        val fixture = bindActiveCard(item, listOf(freshSource))
        try {
            set(fixture.holder, "recoveryAttempts", VideoAdapter.MAX_ERROR_RECOVERIES)
            fixture.adapter.onBindViewHolder(fixture.holder, 0)
            assertEquals("重新绑定卡片后重试次数要清零", 0, get(fixture.holder, "recoveryAttempts"))
        } finally { fixture.adapter.releaseAll() }
    }
}
