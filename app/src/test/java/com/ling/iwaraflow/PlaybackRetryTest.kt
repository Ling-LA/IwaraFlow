package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PlaybackRetryTest {
    private fun set(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    @Test fun failedRefreshOffersSeparateRetryAndKeepsTitlePositionAndPause() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val fresh = VideoSource("720", "https://cdn.example.test/fresh", 720)
        var requests = 0; var infoOpened = 0
        lateinit var reply: (Result<List<VideoSource>>) -> Unit
        val api = mock(IwaraApi::class.java) { call -> when (call.method.name) {
            "resolveSources" -> { requests++; reply = call.getArgument(1); java.io.Closeable {} }
            "chooseSource" -> fresh
            else -> Answers.RETURNS_DEFAULTS.answer(call)
        } }
        val cache = mock(MediaPreloadCache::class.java)
        `when`(cache.createMediaSource(fresh.url)).thenReturn(mock(MediaSource::class.java))
        `when`(cache.progress(nullable(String::class.java))).thenReturn(MediaPreloadCache.Progress())
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(activity), cache,
            { _, _ -> }, {}, {}, {}, onInfo = { infoOpened++ })
        val item = VideoItem("retry", "Original title", "", emptyList(), 0, resumePlayWhenReady = false)
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        activity.setContentView(holder.itemView)
        val p = mock(ExoPlayer::class.java)
        `when`(p.mediaItemCount).thenReturn(1)
        `when`(p.currentPosition).thenReturn(13_500L)
        try {
            adapter.replace(listOf(item)); adapter.onBindViewHolder(holder, 0)
            set(holder, "player", p); set(holder, "active", true); set(adapter, "playbackEnabled", true)
            holder.javaClass.getDeclaredMethod("refreshSourceAfterError", VideoItem::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(holder, item, "network")
            reply(Result.failure(IOException("connection closed"))); shadowOf(Looper.getMainLooper()).idle()
            val retry = holder.itemView.findViewWithTag<TextView>("playback_retry")
            val title = holder.itemView.findViewById<TextView>(R.id.title)
            assertEquals(View.VISIBLE, retry.visibility); assertTrue(retry.isEnabled)
            assertTrue(retry.text.contains("点击重试")); assertEquals("Original title", title.text.toString())
            title.performClick(); assertEquals(1, infoOpened); assertEquals(1, requests)
            retry.performClick(); retry.performClick()
            assertEquals(2, requests)
            reply(Result.success(listOf(fresh))); shadowOf(Looper.getMainLooper()).idle()
            verify(p).seekTo(13_500L)
            verify(p).playWhenReady = false
            verify(p, never()).play()
            assertEquals("Original title", title.text.toString())
        } finally { adapter.releaseAll(); controller.pause().stop().destroy() }
    }
}
