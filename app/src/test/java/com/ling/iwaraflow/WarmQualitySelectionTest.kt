package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WarmQualitySelectionTest {
    @Test fun restoredVideoRefreshesChoicesAndDiscardsReplyAfterSwipingAway() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var reply: (Result<List<VideoSource>>) -> Unit
        var requested = ""
        val api = mock(IwaraApi::class.java) { invocation ->
            if (invocation.method.name == "resolveSources") {
                requested = invocation.getArgument(0)
                reply = invocation.getArgument(1)
                null
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(activity),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        val old = VideoItem("old", "cached", "author", emptyList(), 0,
            sources = listOf(VideoSource("720", "https://cdn.test/old?expires=1", 720)),
            refreshSourcesForSelection = true)
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        val root = FrameLayout(activity)
        root.addView(holder.itemView); activity.setContentView(root)
        try {
            adapter.replace(listOf(old)); adapter.onBindViewHolder(holder, 0)
            holder.itemView.findViewById<android.view.View>(R.id.quality).performClick()
            assertEquals("old", requested)
            val before = ShadowAlertDialog.getLatestAlertDialog()
            val fresh = old.copy(id = "different")
            adapter.replace(listOf(fresh)); adapter.onBindViewHolder(holder, 0)
            reply(Result.success(listOf(VideoSource("1080", "https://cdn.test/new", 1080))))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame("A stale request must not open a dialog on another video", before, ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(old.refreshSourcesForSelection)
        } finally { adapter.releaseAll(); activity.finish() }
    }
}
