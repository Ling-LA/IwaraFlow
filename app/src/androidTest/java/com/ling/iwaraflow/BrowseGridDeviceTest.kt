package com.ling.iwaraflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Runs real grid layout, ViewPager touch navigation and Back against local neutral media. */
@RunWith(AndroidJUnit4::class)
class BrowseGridDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    @Test fun cardsOpenSwipePlaybackAndBackRestoresGridPosition() {
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(AppPrefs.FILE, 0)
        val prefs = AppPrefs(context)
        val oldAutoNext = prefs.autoNext
        val oldTapToPause = prefs.tapToPause
        preferences.edit().putBoolean(PlaybackGuide.key(false), true).putBoolean(PlaybackGuide.key(true), true)
            .putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).commit()
        prefs.autoNext = false
        prefs.tapToPause = false
        val video = File.createTempFile("grid-fixture-", ".mp4", context.cacheDir).apply {
            // The shared navigation fixture is portrait (320×568); this test also exercises
            // landscape fullscreen comments, so use an explicitly landscape neutral clip.
            instrumentation.context.assets.open("browse-landscape-fixture.mp4").use { input -> outputStream().use { output -> input.copyTo(output) } }
        }
        val cover = File(context.cacheDir, "grid-neutral-cover.png")
        Bitmap.createBitmap(480, 400, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.rgb(180, 218, 239))
                drawCircle(340f, 100f, 55f, Paint().apply { color = Color.rgb(250, 213, 119) })
                drawRect(0f, 250f, 480f, 400f, Paint().apply { color = Color.rgb(88, 155, 166) })
            }
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivityV3::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivityV3
        try {
            main {
                invoke(activity, "invalidateRequests")
                (field(activity, "api").get(activity) as IwaraApi).close()
                (field(activity, "recommender").get(activity) as RecommendationEngine).close()
                field(activity, "mode").set(activity, "date")
                @Suppress("UNCHECKED_CAST")
                val states = field(activity, "browseStates").get(activity) as MutableMap<String, BrowseCatalogState>
                states["date"] = BrowseCatalogState().apply {
                    accept(0, (0 until 24).map { index ->
                        VideoItem("grid-$index", "自然与色彩 · 示例 ${index + 1}", "示例创作者", listOf("landscape"), 100 + index,
                            thumbnailUrl = cover.toURI().toString(), createdAt = 1770000000000,
                            sources = listOf(VideoSource("fixture", Uri.fromFile(video).toString(), 1)))
                    }, 24, 28)
                }
                invoke(activity, "showBrowseGrid")
            }
            lateinit var grid: RecyclerView
            main {
                grid = field(activity, "browseGrid").get(activity) as RecyclerView
                (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(8, 20)
            }
            await("Category grid did not lay out fixture cards") { grid.findViewHolderForAdapterPosition(8) != null }
            ProfileUiDeviceTest().screenshot("category-cards-018")
            var originalFirst = -1
            var originalTop = 0
            main {
                val layout = grid.layoutManager as GridLayoutManager
                originalFirst = layout.findFirstVisibleItemPosition()
                originalTop = layout.findViewByPosition(originalFirst)!!.top
                assertFalse((field(activity, "adapter").get(activity) as VideoAdapter).isActivePlaying())
                assertEquals(2, layout.spanCount)
                grid.findViewHolderForAdapterPosition(8)!!.itemView.performClick()
            }
            val pager = activity.findViewById<ViewPager2>(R.id.pager)
            try {
                await("Card did not finish laying out its playable video") {
                    pager.isShown && pager.currentItem == 8 && pager.height > 0 && activity.hasWindowFocus() &&
                        (pager.adapter as VideoAdapter).activeVideoAspect()?.let { it > 1f } == true
                }
            } catch (failure: AssertionError) {
                ProfileUiDeviceTest().screenshot("category-playback-failure-018")
                var state = ""
                main {
                    val adapter = pager.adapter as VideoAdapter
                    state = "index=${pager.currentItem}, count=${adapter.itemCount}, aspect=${adapter.activeVideoAspect()}, " +
                        "playing=${adapter.isActivePlaying()}, shown=${pager.isShown}, size=${pager.width}×${pager.height}, " +
                        "focus=${activity.hasWindowFocus()}"
                }
                throw AssertionError("${failure.message}: $state", failure)
            }
            // Visibility/currentItem update before ViewPager2's next layout. Wait for the actual
            // video surface, and inject relative to its real window bounds rather than app metrics.
            ProfileUiDeviceTest().screenshot("category-card-playback-018")
            val position = IntArray(2)
            var x = 0; var startY = 0; var endY = 0
            val windowTouches = CopyOnWriteArrayList<String>()
            val recyclerTouches = CopyOnWriteArrayList<String>()
            val scrollStates = CopyOnWriteArrayList<Int>()
            main {
                pager.getLocationOnScreen(position)
                x = position[0] + pager.width / 2
                startY = position[1] + pager.height * 3 / 4
                endY = position[1] + pager.height / 4
                val original = activity.window.callback
                activity.window.callback = object : android.view.Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (windowTouches.size < 20 || event.actionMasked != MotionEvent.ACTION_MOVE)
                            windowTouches += "${event.actionMasked}@${event.x.toInt()},${event.y.toInt()}:${event.eventTime - event.downTime}ms"
                        return original.dispatchTouchEvent(event)
                    }
                }
                (pager.getChildAt(0) as RecyclerView).addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                    override fun onInterceptTouchEvent(recycler: RecyclerView, event: MotionEvent): Boolean {
                        if (recyclerTouches.size < 20 || event.actionMasked != MotionEvent.ACTION_MOVE)
                            recyclerTouches += "${event.actionMasked}@${event.x.toInt()},${event.y.toInt()}:${event.eventTime - event.downTime}ms"
                        return false
                    }
                })
                pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) { scrollStates += state }
                })
            }
            val swipeOutput = shell("input touchscreen swipe $x $startY $x $endY 220").decodeToString()
            try {
                await("Vertical swipe did not select the next category video") { pager.currentItem == 9 }
            } catch (failure: AssertionError) {
                ProfileUiDeviceTest().screenshot("category-swipe-failure-018")
                var state = ""
                main {
                    val recycler = pager.getChildAt(0) as RecyclerView
                    state = "index=${pager.currentItem}, count=${pager.adapter?.itemCount}, input=${pager.isUserInputEnabled}, " +
                        "orientation=${pager.orientation}, canScroll=${recycler.canScrollVertically(1)}, scroll=${pager.scrollState}, focus=${activity.hasWindowFocus()}, " +
                        "display=${pager.display?.displayId}, bounds=${position.toList()}/${pager.width}×${pager.height}, " +
                        "swipe=$x,$startY->$x,$endY, output=$swipeOutput, window=$windowTouches, recycler=$recyclerTouches, scrollStates=$scrollStates"
                }
                throw AssertionError("${failure.message}: $state", failure)
            }
            await("Next category video did not bind its local player") {
                (pager.adapter as VideoAdapter).activeVideoAspect()?.let { it > 1f } == true &&
                    (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9) != null
            }
            val fullscreenSelections = CopyOnWriteArrayList<String>()
            val fullscreenActiveVideos = CopyOnWriteArrayList<String?>()
            var fullscreenPhase = "enter"
            main {
                pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        val active = (pager.adapter as VideoAdapter).activeItem()?.id
                        fullscreenSelections += "$fullscreenPhase: page=$position, active=$active, guard=${FullscreenMode.isChangingViewport(pager)}"
                        fullscreenActiveVideos += active
                    }
                })
                (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)!!.itemView
                    .findViewById<View>(R.id.fullscreen).performClick()
            }
            await("Fullscreen did not finish rotating") {
                pager.width > pager.height && (pager.adapter as VideoAdapter).isFullscreen && !FullscreenMode.isChangingViewport(pager)
            }
            main {
                val card = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)!!.itemView
                assertFalse(card.findViewById<View>(R.id.fullscreenSocial).isShown)
                card.performClick()
            }
            await("Tapping fullscreen did not reveal transport and social controls together") {
                (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)?.itemView
                    ?.findViewById<View>(R.id.pauseSeekBar)?.isShown == true &&
                    (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)?.itemView
                        ?.findViewById<View>(R.id.fullscreenSocial)?.isShown == true
            }
            main {
                (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)!!.itemView.performClick()
            }
            await("Second fullscreen tap did not hide transport and social controls together") {
                val card = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)?.itemView
                card != null && !card.findViewById<View>(R.id.pauseSeekBar).isShown &&
                    !card.findViewById<View>(R.id.fullscreenSocial).isShown
            }
            main {
                (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)!!.itemView.performClick()
            }
            await("Third fullscreen tap did not restore transport and social controls together") {
                val card = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)?.itemView
                card != null && card.findViewById<View>(R.id.pauseSeekBar).isShown &&
                    card.findViewById<View>(R.id.fullscreenSocial).isShown
            }
            main {
                fullscreenPhase = "comments"
                val card = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(9)!!.itemView
                assertTrue(card.findViewById<TextView>(R.id.fullscreenLikeCount).text.toString().contains("109"))
                assertFalse(card.findViewById<View>(R.id.actionPanel).isShown)
                card.findViewById<View>(R.id.fullscreenComments).performClick()
            }
            await("Fullscreen comment button did not open the side panel") {
                val panel = activity.findViewById<View>(R.id.commentsPanel)
                panel.isShown && panel.width > 0 && panel.width < pager.width / 2
            }
            main {
                // Populate neutral comment rows locally; UI opening/layout remains the real path.
                // No request or comment is sent to Iwara by this device regression.
                val panel = (field(activity, "comments").get(activity) as CommentsHost).panel
                val comments = field(panel, "adapter").get(panel) as CommentListAdapter
                comments.translationEnabled = false
                comments.replaceAll(listOf(IwaraComment("local-comment", "这个示例的色彩很舒服，期待更多风景分享。",
                    IwaraAuthor("fixture-author", "风景记录者", "landscape"), System.currentTimeMillis())))
                field(panel, "total").setInt(panel, 1)
                invoke(panel, "hideStatus"); invoke(panel, "refreshTitle")
            }
            ProfileUiDeviceTest().screenshot("fullscreen-comments-018")
            main { activity.onBackPressedDispatcher.onBackPressed() }
            await("Back must close fullscreen comments") { !activity.findViewById<View>(R.id.commentsPanel).isShown }
            main {
                fullscreenPhase = "exit"
                if ((pager.adapter as VideoAdapter).isFullscreen) activity.onBackPressedDispatcher.onBackPressed()
            }
            await("Exiting fullscreen did not restore portrait playback") {
                pager.width < pager.height && !(pager.adapter as VideoAdapter).isFullscreen && pager.isUserInputEnabled &&
                    !(pager.getChildAt(0) as RecyclerView).isLayoutSuppressed && !FullscreenMode.isChangingViewport(pager)
            }
            main {
                assertEquals("grid-9", (pager.adapter as VideoAdapter).items[pager.currentItem].id)
                assertTrue("Rotation must never activate a neighbouring video, even during framework layout notifications: $fullscreenSelections",
                    fullscreenActiveVideos.all { it == "grid-9" })
                activity.onBackPressedDispatcher.onBackPressed()
            }
            await("Back did not restore the category grid") { grid.isShown && pager.visibility == View.GONE }
            main {
                val layout = grid.layoutManager as GridLayoutManager
                assertFalse(activity.isFinishing)
                assertEquals(originalFirst, layout.findFirstVisibleItemPosition())
                assertEquals(originalTop, layout.findViewByPosition(originalFirst)!!.top)
                assertFalse((pager.adapter as VideoAdapter).isActivePlaying())
            }
            ProfileUiDeviceTest().screenshot("category-cards-return-018")
        } finally {
            main { activity.finish(); prefs.autoNext = oldAutoNext; prefs.tapToPause = oldTapToPause }
            video.delete(); cover.delete()
        }
    }
}
