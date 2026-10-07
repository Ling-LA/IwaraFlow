package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Neutral fixtures for geometry and screenshots; never submit a real video. */
@RunWith(AndroidJUnit4::class)
class ProfileUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(texts(view.getChildAt(it))) }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun profileSectionsAndNativeUploadRenderAsFullPages() {
        val context = instrumentation.targetContext
        val my = instrumentation.startActivitySync(Intent(context, MyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MyActivity
        try {
            main {
                (field(my, "api").get(my) as IwaraApi).close()
                field(my, "more").set(my, false)
                my.bindProfile(IwaraAuthor("fixture", "示例作者", "sample_creator", "分享音乐与动画作品。"))
                @Suppress("UNCHECKED_CAST")
                val videos = field(my, "videos").get(my) as MutableList<VideoItem>
                repeat(4) { i -> videos += VideoItem("fixture-$i", "音乐与动画 · 作品 ${i + 1}", "示例作者", listOf("animation", "music"), 120,
                    views = 12000, createdAt = 1791244800000L) }
                (field(my, "listAdapter").get(my) as AuthorVideoListAdapter).notifyDataSetChanged()
                my.findViewById<TextView>(R.id.authorStatus).text = "作品 4 条"
            }
            screenshot("my-profile")
            main {
                val follow = my.findViewById<View>(R.id.followButton)
                val fans = my.findViewById<View>(R.id.friendButton)
                val saved = my.findViewById<View>(R.id.profileFavoritesButton)
                assertEquals(follow.top, fans.top); assertEquals(fans.top, saved.top)
                assertTrue(follow.right < fans.left); assertTrue(fans.right < saved.left)
                assertTrue(my.findViewById<RecyclerView>(R.id.authorVideos).height > my.resources.displayMetrics.heightPixels / 3)
            }
        } finally { main { my.finish() } }
        val interest = instrumentation.startActivitySync(Intent(context, InterestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            screenshot("interest-manual")
            main { texts(interest.window.decorView).first { it.text.toString() == "系统兴趣" }.performClick() }
            screenshot("interest-system")
        } finally { main { interest.finish() } }
        val upload = instrumentation.startActivitySync(Intent(context, UploadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            screenshot("native-upload")
            main {
                assertTrue(texts(upload.window.decorView).any { it.text.toString() == "选择 MP4 视频" })
                assertTrue(texts(upload.window.decorView).any { it.text.toString() == "创作者须知" })
                assertTrue(texts(upload.window.decorView).any { it.text.toString() == "官网规则" })
                fun webCount(v: View): Int = (if (v is android.webkit.WebView) 1 else 0) +
                    if (v is ViewGroup) (0 until v.childCount).sumOf { webCount(v.getChildAt(it)) } else 0
                assertEquals(0, webCount(upload.window.decorView))
            }
        } finally { main { upload.finish() } }
    }
}
