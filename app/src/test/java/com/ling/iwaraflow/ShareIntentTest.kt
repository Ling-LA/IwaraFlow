package com.ling.iwaraflow

import android.content.Intent
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
 * 分享要由页面自己发起：直接 startActivity 的话，分享面板弹出来的一瞬间会触发
 * onUserLeaveHint，开了“自动小窗”的账号就会看着应用缩进小窗躲到面板后面去。
 * 所以 VideoShare 只造 Intent，谁来启动、启动前后怎么处理播放，交给页面决定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun item(id: String) = VideoItem(id, "片名", "作者", emptyList(), 0)

    @Test fun theChooserCarriesTheVideoLink() {
        val chooser = VideoShare.chooserFor(context, item("abc123"))
        assertNotNull("应该造得出选择器", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("选择器里要包着一个 ACTION_SEND", send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        assertEquals("片名\nhttps://www.iwara.tv/video/abc123", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun aVideoWithoutAnIdHasNothingToShare() {
        assertNull("没有 id 就没有链接可分享", VideoShare.chooserFor(context, item("")))
    }

    private fun card(onShare: ((VideoItem) -> Unit)?): Pair<VideoAdapter, VideoAdapter.Holder> {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}, onShare
        )
        adapter.replace(listOf(item("abc123")))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        return adapter to holder
    }

    @Test fun theShareButtonHandsTheVideoToThePage() {
        val shared = ArrayList<String>()
        val (adapter, holder) = card { shared += it.id }
        try {
            holder.itemView.findViewById<View>(R.id.share).performClick()
            assertEquals("分享按钮要把视频交给页面处理", listOf("abc123"), shared)
        } finally { adapter.releaseAll() }
    }

    @Test fun aPageThatDoesNotHandleSharingStillShares() {
        // 作者页、搜索页没有小窗，用不着接管，按老样子直接拉起选择器就行。
        val (adapter, holder) = card(null)
        try {
            holder.itemView.findViewById<View>(R.id.share).performClick()
            val started = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .nextStartedActivity
            assertNotNull("没有接管时应该自己拉起分享", started)
            assertEquals(Intent.ACTION_CHOOSER, started.action)
        } finally { adapter.releaseAll() }
    }

    /** 小窗里操作栏是藏起来的，所以要知道当前播的是哪一条才能给出分享按钮。 */
    @Test fun theAdapterKnowsWhichVideoIsPlaying() {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}, null
        )
        try {
            assertNull("还没有内容时没有当前视频", adapter.activeItem())
            adapter.replace(listOf(item("first"), item("second")))
            assertEquals("first", adapter.activeItem()?.id)
            adapter.setActive(1)
            assertEquals("second", adapter.activeItem()?.id)
        } finally { adapter.releaseAll() }
    }

    @Test fun anEmptyFeedHasNoVideoToShare() {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}, null
        )
        try {
            adapter.replace(emptyList())
            assertNull(adapter.activeItem())
        } finally { adapter.releaseAll() }
    }
}
