package com.ling.iwaraflow

import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PlaybackHolderCancellationTest {
    @Test fun suspendingDuringResolutionCancelsItAndReturnStartsAgain() {
        val app = RuntimeEnvironment.getApplication()
        var requests = 0; var cancellations = 0
        val api = mock(IwaraApi::class.java) { call ->
            if (call.method.name == "resolveSources") { requests++; java.io.Closeable { cancellations++ } }
            else Answers.RETURNS_DEFAULTS.answer(call)
        }
        val adapter = VideoAdapter(api, mock(HistoryStore::class.java), AppPrefs(app),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        val holder = adapter.onCreateViewHolder(FrameLayout(app), 0)
        fun enable() {
            adapter.javaClass.getDeclaredField("playbackEnabled").apply { isAccessible = true }.set(adapter, true)
            holder.setActive(true)
        }
        try {
            val item = VideoItem("waiting", "", "", emptyList(), 0).apply { resumePositionMs = 13_500L }
            adapter.replace(listOf(item))
            adapter.onBindViewHolder(holder, 0); enable()
            assertEquals(1, requests)
            adapter.suspendPlayback()
            assertEquals(1, cancellations)
            assertEquals("Backgrounding during source resolution must preserve the resume point", 13_500L, item.resumePositionMs)
            enable()
            assertEquals("Returning must resolve again instead of keeping an empty IDLE player", 2, requests)
            adapter.pauseAll()
            assertEquals(2, cancellations)
        } finally { adapter.releaseAll() }
    }
}
