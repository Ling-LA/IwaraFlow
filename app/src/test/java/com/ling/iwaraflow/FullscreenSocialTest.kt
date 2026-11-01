package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullscreenSocialTest {
    @Test fun fullscreenOffersCommentsAndSyncedCountWithoutReactionButtons() {
        val context = RuntimeEnvironment.getApplication()
        var opened: VideoItem? = null
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}, onComments = { opened = it })
        val video = VideoItem("social", "作品", "作者", emptyList(), 345)
        adapter.replace(listOf(video))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        try {
            val root = holder.itemView
            assertEquals(View.GONE, root.findViewById<View>(R.id.fullscreenSocial).visibility)
            adapter.setFullscreen(true)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.fullscreenSocial).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.actionPanel).visibility)
            assertFalse(root.findViewById<View>(R.id.fullscreenLikeCount).isClickable)
            assertTrue(root.findViewById<TextView>(R.id.fullscreenLikeCount).text.contains("345"))
            root.findViewById<View>(R.id.fullscreenComments).performClick()
            assertSame(video, opened)
            video.likes = 346; adapter.refreshItem(video.id)
            assertTrue(root.findViewById<TextView>(R.id.fullscreenLikeCount).text.contains("346"))
            adapter.setPipMode(true)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fullscreenSocial).visibility)
            adapter.setPipMode(false)
            adapter.setFullscreen(false)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fullscreenSocial).visibility)
        } finally { adapter.releaseAll() }
    }
}
