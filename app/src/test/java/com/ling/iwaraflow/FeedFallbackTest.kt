package com.ling.iwaraflow

import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 有的设备（例如运营商拦截 CDN 时）根本做不了可播放预检，之前整页候选都被判为
 * 不可播放，首页就永远只有“没有可播放视频”。预检失败不等于视频放不了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class FeedFallbackTest {
    private fun candidates(issue: String?): List<VideoItem> = (0 until 3).map { index ->
        VideoItem("fallback-$index", "标题 $index", "作者", emptyList(), 0).apply { playbackIssue = issue }
    }

    private fun showFeedReset(main: MainActivityV3, candidates: List<VideoItem>) {
        val serial = MainActivityV3::class.java.getDeclaredField("requestSerial")
            .apply { isAccessible = true }.getInt(main)
        MainActivityV3::class.java
            .getDeclaredMethod("showFeedReset", Int::class.java, List::class.java, String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(main, serial, emptyList<VideoItem>(), "这一页没有可播放视频", candidates)
    }

    private fun adapterOf(main: MainActivityV3): VideoAdapter =
        MainActivityV3::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(main) as VideoAdapter

    @Test fun candidatesThatOnlyFailedTheProbeStillReachTheFeed() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val main = controller.get()
                try {
                    main.findViewById<TextView>(R.id.error).visibility = View.GONE
                    showFeedReset(main, candidates("视频资源无法连接"))
                    val items = adapterOf(main).items
                    assertEquals(3, items.size)
                    assertTrue("回退的视频必须清掉预检失败标记", items.all { it.playbackIssue == null })
                    assertEquals(View.GONE, main.findViewById<TextView>(R.id.error).visibility)
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        }
    }

    @Test fun candidatesWithAPermanentReasonAreNotForcedIntoTheFeed() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val main = controller.get()
                try {
                    main.findViewById<TextView>(R.id.error).visibility = View.GONE
                    showFeedReset(main, candidates("仅限好友观看"))
                    assertEquals(0, adapterOf(main).items.size)
                    assertEquals(View.VISIBLE, main.findViewById<TextView>(R.id.error).visibility)
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        }
    }
}
