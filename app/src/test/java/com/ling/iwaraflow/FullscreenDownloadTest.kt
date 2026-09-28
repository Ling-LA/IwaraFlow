package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullscreenDownloadTest {
    @Test fun downloadFollowsPipAndOnlyAppearsInFullscreenControls() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(VideoItem("v", "title", "author", emptyList(), 0)))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        try {
            val root = holder.itemView
            val row = root.findViewById<LinearLayout>(R.id.pauseTopRow)
            val download = root.findViewById<View>(R.id.pauseDownload)
            assertEquals(row.indexOfChild(root.findViewById(R.id.pausePip)) + 1, row.indexOfChild(download))
            assertTrue(download.hasOnClickListeners())
            val place = PauseSeekBar::class.java.getDeclaredMethod("placeTopRow", View::class.java,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            val bar = root.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
            root.setTag(R.id.chrome_mode, PauseSeekBar.MODE_FULLSCREEN)
            place.invoke(bar, root, 0L, 1000L)
            assertEquals(View.VISIBLE, download.visibility)
            root.setTag(R.id.chrome_mode, PauseSeekBar.MODE_NORMAL)
            place.invoke(bar, root, 0L, 1000L)
            assertEquals(View.GONE, download.visibility)
        } finally { adapter.releaseAll() }
    }
}
