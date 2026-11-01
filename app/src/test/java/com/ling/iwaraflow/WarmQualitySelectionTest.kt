package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WarmQualitySelectionTest {
    private fun set(target: Any, name: String, value: Any?) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    @Test fun restoredVideoRefreshesChoicesAndDiscardsReplyAfterSwipingAway() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var reply: (Result<List<VideoSource>>) -> Unit
        var requested = ""
        var cancelled = false
        val api = mock(IwaraApi::class.java) { invocation ->
            if (invocation.method.name == "resolveSources") {
                requested = invocation.getArgument(0)
                reply = invocation.getArgument(1)
                java.io.Closeable { cancelled = true }
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
            set(adapter, "playbackEnabled", true); set(holder, "active", true)
            holder.itemView.findViewById<android.view.View>(R.id.quality).performClick()
            assertEquals("old", requested)
            val before = ShadowAlertDialog.getLatestAlertDialog()
            val fresh = old.copy(id = "different")
            adapter.replace(listOf(fresh)); adapter.onBindViewHolder(holder, 0)
            assertTrue("Leaving the card must cancel its pending quality request", cancelled)
            reply(Result.success(listOf(VideoSource("1080", "https://cdn.test/new", 1080))))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame("A stale request must not open a dialog on another video", before, ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(old.refreshSourcesForSelection)
        } finally { adapter.releaseAll(); activity.finish() }
    }

    @Test fun queuedQualityReplyCannotReopenDialogAfterSuspendAndSamePlayerResume() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val replies = mutableListOf<(Result<List<VideoSource>>) -> Unit>()
        var cancellations = 0
        val api = mock(IwaraApi::class.java) { call ->
            if (call.method.name == "resolveSources") {
                replies += call.getArgument<(Result<List<VideoSource>>) -> Unit>(1)
                java.io.Closeable { cancellations++ }
            } else Answers.RETURNS_DEFAULTS.answer(call)
        }
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(activity),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        val cached = listOf(VideoSource("720", "https://cdn.test/stale", 720))
        val item = VideoItem("same", "fixture", "author", emptyList(), 0,
            sources = cached, refreshSourcesForSelection = true)
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        val root = FrameLayout(activity).apply { addView(holder.itemView) }
        activity.setContentView(root)
        val player = mock(ExoPlayer::class.java)
        `when`(player.playbackState).thenReturn(Player.STATE_READY)
        try {
            adapter.replace(listOf(item)); adapter.onBindViewHolder(holder, 0)
            set(holder, "player", player); set(holder, "active", true); set(adapter, "playbackEnabled", true)
            val quality = holder.itemView.findViewById<android.view.View>(R.id.quality)
            val previousDialog = ShadowAlertDialog.getLatestAlertDialog()
            quality.performClick()
            // The network reply has already queued UI work, so closing the request alone
            // cannot stop it. Suspend and resume the same player before that work executes.
            replies[0](Result.success(listOf(VideoSource("1080", "https://cdn.test/obsolete", 1080))))
            adapter.suspendPlayback()
            assertEquals(1, cancellations)
            set(holder, "active", true); set(adapter, "playbackEnabled", true)
            quality.performClick()
            assertEquals(2, replies.size)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("An obsolete callback must not replace the current choices", cached, item.sources)
            assertTrue(item.refreshSourcesForSelection)
            assertSame(previousDialog, ShadowAlertDialog.getLatestAlertDialog())
            val current = listOf(VideoSource("Source", "https://cdn.test/current", 10_000))
            replies[1](Result.success(current))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(current, item.sources)
            assertFalse(item.refreshSourcesForSelection)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame("The current request still opens its chooser", previousDialog, dialog)
            assertTrue(dialog.isShowing)
            dialog.dismiss()
        } finally { adapter.releaseAll(); activity.finish() }
    }
}
