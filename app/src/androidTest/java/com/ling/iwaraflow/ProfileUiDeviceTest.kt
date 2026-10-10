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
    internal fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Gradle 卸载测试应用会删除 externalFilesDir；立即用 shell 导出到独立目录。
        val export = "/sdcard/Download/IwaraFlow-ui-checks"
        require(name.matches(Regex("[a-z0-9-]+")))
        fun command(value: String): String {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(value)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        // UiAutomation executes argv directly; it does not interpret shell quotes or &&.
        command("mkdir -p $export")
        command("cp ${file.absolutePath} $export/$name.png")
        val output = command("wc -c $export/$name.png")
        assertTrue("Screenshot export failed: $output", (output.trim().substringBefore(' ').toLongOrNull() ?: 0) > 0)
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
        HistoryStore(context).use { history ->
            listOf("animation", "music", "landscape", "dance", "nature", "game", "art", "travel").forEachIndexed { i, tag ->
                history.recordInteraction(VideoItem("ui-tag-$i", "示例作品", "fixture", listOf(tag), 120), "like", 1.0)
            }
        }
        val interest = instrumentation.startActivitySync(Intent(context, InterestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            screenshot("interest-manual")
            main { texts(interest.window.decorView).first { it.text.toString() == "系统兴趣" }.performClick() }
            screenshot("interest-system")
        } finally { main { interest.finish() } }
        val search = instrumentation.startActivitySync(Intent(context, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { screenshot("search-match-controls") } finally { main { search.finish() } }
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
    @Test fun commentTabsShareTheSameBaselineAndDanmakuSettingsIncludeTranslation() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, AuthorActivity::class.java)
            .putExtra(AuthorActivity.EXTRA_ID, "fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AuthorActivity
        lateinit var panel: CommentsPanel
        try {
            main {
                val api = field(activity, "api").get(activity) as IwaraApi
                api.close()
                val page = android.widget.FrameLayout(activity).apply { setBackgroundColor(0xFF182532.toInt()) }
                val root = activity.layoutInflater.inflate(R.layout.view_comments_panel, page, false)
                page.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.BOTTOM))
                activity.setContentView(page)
                panel = CommentsPanel(root, api, {}, { _, _, _ -> })
                val item = VideoItem("fixture", "示例视频", "示例作者", listOf("animation"), 10).apply { description = "用于核对页签和弹幕设置的示例简介。" }
                panel.open(item, (activity.resources.displayMetrics.heightPixels*0.7f).toInt(), 0, CommentsPanel.Tab.INFO)
            }
            screenshot("comments-tabs-info")
            main {
                fun baseline(id: Int): Int {
                    val view = activity.findViewById<TextView>(id)
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    return xy[1] + view.baseline
                }
                assertEquals(baseline(R.id.panelTabInfoLabel), baseline(R.id.commentsTitle))
                assertEquals(baseline(R.id.panelTabInfoLabel), baseline(R.id.panelTabDanmakuLabel))
                activity.findViewById<View>(R.id.panelTabDanmaku).performClick()
            }
            screenshot("danmaku-translation-settings")
            main {
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.panelTabDanmakuLine).visibility)
                assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.panelTabCommentsLine).visibility)
                assertTrue(texts(activity.window.decorView).any { it.text.toString().contains("额外消耗 token") })
            }
        } finally { main { activity.finish() } }
    }

    @Test fun networkHintAndAboutDialogRenderAndRecover() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, AuthorActivity::class.java)
            .putExtra(AuthorActivity.EXTRA_ID, "fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AuthorActivity
        val history = HistoryStore(activity)
        val cache = MediaPreloadCache(activity)
        lateinit var adapter: VideoAdapter
        lateinit var holder: VideoAdapter.Holder
        lateinit var hint: NetworkQualityHint
        try {
            main {
                val api = field(activity, "api").get(activity) as IwaraApi; api.close()
                adapter = VideoAdapter(api, history, AppPrefs(activity), cache, { _, _ -> }, {}, {}, {})
                adapter.items += VideoItem("fixture", "示例播放画面", "示例作者", listOf("animation"), 10)
                holder = adapter.onCreateViewHolder(android.widget.FrameLayout(activity), 0)
                adapter.onBindViewHolder(holder, 0)
                activity.setContentView(holder.itemView)
                hint = field(holder, "networkHint").get(holder) as NetworkQualityHint
                hint.offer("540p") {}; holder.applyVideoInsets()
            }
            screenshot("network-quality-hint")
            main {
                assertTrue(hint.top > holder.itemView.height / 2)
                assertTrue(hint.bottom < holder.itemView.height)
                val density = activity.resources.displayMetrics.density
                assertTrue(hint.left >= (12*density).toInt()-1)
                assertTrue(hint.right <= holder.itemView.width - (84*density).toInt()+1)
                assertTrue(hint.background is android.graphics.drawable.GradientDrawable)
                hint.networkStable(true)
            }
            android.os.SystemClock.sleep(3200)
            main { assertEquals(View.GONE, hint.visibility); AboutDialog.show(activity) }
            screenshot("about-dialog")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        } finally { main { adapter.releaseAll(); cache.close(); history.close(); activity.finish() } }
    }

    @Test fun normalPlaybackGuideExcludesVisibleControls() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, AuthorActivity::class.java).putExtra(AuthorActivity.EXTRA_ID, "fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AuthorActivity
        lateinit var item: View
        try {
            main {
                (field(activity, "api").get(activity) as IwaraApi).close()
                val page = activity.layoutInflater.inflate(R.layout.activity_main, null) as android.widget.FrameLayout
                page.removeView(page.findViewById(R.id.pager))
                page.findViewById<View>(R.id.loading).visibility = View.GONE
                item = activity.layoutInflater.inflate(R.layout.item_video, page, false)
                page.addView(item, 0)
                item.setTag(R.id.chrome_mode, PauseSeekBar.MODE_NORMAL)
                item.findViewById<TextView>(R.id.author).text = "示例作者"
                item.findViewById<TextView>(R.id.title).text = "音乐与动画 · 示例作品"
                item.findViewById<TextView>(R.id.tags).text = "#animation  #music"
                item.findViewById<TextView>(R.id.likeCount).text = "128"
                activity.setContentView(page)
            }
            instrumentation.waitForIdleSync()
            main { PlaybackGuide.show(activity, false) }
            screenshot("normal-playback-guide")
            main {
                val guide = activity.window.decorView.findViewWithTag<View>("playback_gesture_guide") as PlaybackGuide.GuideView
                val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
                fun bounds(view: View): android.graphics.RectF {
                    val point = IntArray(2).also(view::getLocationOnScreen)
                    return android.graphics.RectF((point[0]-origin[0]).toFloat(), (point[1]-origin[1]).toFloat(),
                        (point[0]-origin[0]+view.width).toFloat(), (point[1]-origin[1]+view.height).toFloat())
                }
                val controls = listOf(R.id.topBar, R.id.actionPanel, R.id.infoPanel).map { bounds(activity.findViewById(it)) }
                assertTrue(controls.none { android.graphics.RectF.intersects(it, guide.portraitArea) })
                assertEquals(3, guide.labelAreas.size)
                guide.labelAreas.forEach { area ->
                    assertFalse(area.isEmpty)
                    assertTrue(controls.none { android.graphics.RectF.intersects(it, area) })
                }
                assertTrue(texts(guide).any { it.text.toString().contains("按钮区") })
            }
        } finally { main { PlaybackGuide.dismiss(activity); activity.finish() } }
    }

    @Test fun settingsAndDataPagesRemainReadableInDarkMode() {
        val context = instrumentation.targetContext
        main { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
        try {
            for ((type, name) in listOf(SettingsActivity::class.java to "settings-dark", DataManagementActivity::class.java to "data-dark", UploadTasksActivity::class.java to "upload-tasks-dark", SearchActivity::class.java to "search-dark")) {
                val activity = instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    screenshot(name)
                    main {
                        val foreground = activity.getColor(R.color.page_text)
                        val background = activity.getColor(R.color.page_surface)
                        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground, background) >= 4.5)
                        assertTrue(texts(activity.window.decorView).any { it.visibility == View.VISIBLE && it.text.isNotBlank() })
                    }
                } finally { main { activity.finish() } }
            }
        } finally { main { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) } }
    }

    @Test fun fullscreenGuideAndLandscapeDislikePanelRenderWithoutCoveringTheVideo() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, AuthorActivity::class.java).putExtra(AuthorActivity.EXTRA_ID, "fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AuthorActivity
        val history = HistoryStore(context)
        try {
            main {
                (field(activity, "api").get(activity) as IwaraApi).close()
                activity.setContentView(TextView(activity).apply { text = "示例播放画面"; textSize = 24f; setTextColor(-1); setBackgroundColor(0xFF162C3D.toInt()); gravity = android.view.Gravity.CENTER })
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(100)
            instrumentation.waitForIdleSync()
            val root = activity.window.decorView
            lateinit var fullHold: ScreenReactionHold
            main { fullHold = ScreenReactionHold(root) {}; fullHold.start(root.width/2f, root.height/2f) }
            android.os.SystemClock.sleep(900)
            screenshot("fullscreen-double-reaction")
            main { fullHold.cancel(); PlaybackGuide.show(activity, true) }
            screenshot("fullscreen-guide-landscape")
            main {
                PlaybackGuide.dismiss(activity)
                DislikeSheet.show(activity, VideoItem("fixture", "示例作品", "示例作者", listOf("animation", "music", "nature", "game", "travel", "art"), 60), history, null)
            }
            screenshot("dislike-landscape")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            main {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val portraitDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT && android.os.SystemClock.uptimeMillis() < portraitDeadline) android.os.SystemClock.sleep(100)
            main {
                DislikeSheet.show(activity, VideoItem("fixture", "示例作品", "示例作者", listOf("animation", "music", "nature", "game", "travel", "art"), 60), history, null)
            }
            screenshot("dislike-portrait")
            val sheetCapture = instrumentation.uiAutomation.takeScreenshot()
            try {
                // Sample away from the system navigation icons / gesture pill.
                val bottomColor = sheetCapture.getPixel(sheetCapture.width / 3, sheetCapture.height - 4)
                assertTrue("Sheet background must extend through the navigation area",
                    android.graphics.Color.red(bottomColor) > 220 && android.graphics.Color.green(bottomColor) > 220 && android.graphics.Color.blue(bottomColor) > 220)
            } finally { sheetCapture.recycle() }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            main { PlaybackGuide.show(activity, false) }
            screenshot("fullscreen-guide-portrait")
            lateinit var pairHold: HoldReaction
            lateinit var like: android.widget.ImageView
            main {
                PlaybackGuide.dismiss(activity)
                val density = activity.resources.displayMetrics.density
                val panel = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                    setBackgroundColor(0xFF162C3D.toInt())
                }
                like = android.widget.ImageView(activity).apply { setImageResource(R.drawable.ic_heart_rounded); setColorFilter(0xFFFF365D.toInt()); setPadding(6, 6, 6, 6) }
                val favorite = android.widget.ImageView(activity).apply { setImageResource(R.drawable.ic_star_rounded); setColorFilter(0xFFFFD54F.toInt()); setPadding(6, 6, 6, 6) }
                panel.addView(like, android.widget.LinearLayout.LayoutParams((76*density).toInt(), (36*density).toInt()))
                panel.addView(favorite, android.widget.LinearLayout.LayoutParams((76*density).toInt(), (36*density).toInt()).apply { topMargin = (20*density).toInt() })
                activity.setContentView(panel)
                pairHold = HoldReaction(like, 0xFFFF365D.toInt(), favorite, 0xFFFFD54F.toInt()) {}
            }
            instrumentation.waitForIdleSync()
            main {
                val time = android.os.SystemClock.uptimeMillis()
                android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, like.width/2f, like.height/2f, 0).also { like.dispatchTouchEvent(it); it.recycle() }
            }
            android.os.SystemClock.sleep(900)
            screenshot("double-reaction-buttons")
            main { pairHold.cancel() }
        } finally { main { PlaybackGuide.dismiss(activity); activity.finish() }; history.close() }
    }

}
