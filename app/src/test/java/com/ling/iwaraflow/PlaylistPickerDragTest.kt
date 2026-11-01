package com.ling.iwaraflow

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistPickerDragTest {
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).accountId = "picker-drag"
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private inner class Fixture : Closeable {
        private val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_IwaraFlow) }
        private val store = PlaylistStore(activity)
        var writes = 0
        var results = 0
        var reply: ((Result<PlaylistFavorites.Change>) -> Unit)? = null
        private val favorites = mock(PlaylistFavorites::class.java) { call ->
            when (call.method.name.substringBefore('$')) {
                "getStore" -> store
                "loadPicker" -> {
                    call.getArgument<(Result<List<PlaylistFolder>>) -> Unit>(1)(Result.success(
                        List(12) { PlaylistFolder("folder-$it", "收藏夹 $it", 1, added = it == 0) }
                    ))
                    null
                }
                "applySelection" -> { writes++; reply = call.getArgument(2); null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val dialog: Dialog
        val panel: View
        val handle: View
        init {
            controller.setup()
            PlaylistPicker.show(activity, favorites, VideoItem("fixture", "风景", "示例作者", emptyList(), 1)) { results++ }
            shadowOf(Looper.getMainLooper()).idle()
            dialog = ShadowDialog.getLatestDialog()
            panel = dialog.window!!.decorView.findViewWithTag("playlist_picker_panel")
            handle = panel.findViewWithTag("playlist_drag_handle")
            val density = activity.resources.displayMetrics.density
            val width = (360 * density).toInt(); val height = (600 * density).toInt()
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, width, height)
            assertTrue(dialog.isShowing)
        }
        fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        fun drag(target: View = handle, deltaY: Float, cancelled: Boolean = false, duration: Long = 500L): Float {
            val start = SystemClock.uptimeMillis()
            val x = target.width / 2f
            val y = target.height / 2f
            fun send(action: Int, offset: Long, dy: Float) {
                MotionEvent.obtain(start, start + offset, action, x, y + dy, 0).let { event ->
                    target.dispatchTouchEvent(event); event.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, 0, 0f)
            send(MotionEvent.ACTION_MOVE, duration / 2, deltaY / 2)
            send(MotionEvent.ACTION_MOVE, duration - 1, deltaY)
            val followed = panel.translationY
            send(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, duration, deltaY)
            settle()
            return followed
        }
        override fun close() { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun handleFollowsDownwardDragAndDismissesWithoutSavingDraftSelection() {
        Fixture().use { f ->
            views(f.panel).filterIsInstance<CheckBox>().first().isChecked = false
            val distance = f.panel.height * .4f
            assertEquals(distance, f.drag(deltaY = distance), .1f)
            assertFalse(f.dialog.isShowing)
            assertEquals(0, f.writes); assertEquals(0, f.results)
        }
    }

    @Test fun shortDragAndCancelledLongDragReturnToTheOriginalPosition() {
        Fixture().use { f ->
            assertTrue(f.drag(deltaY = f.panel.height * .1f) > 0)
            assertTrue(f.dialog.isShowing); assertEquals(0f, f.panel.translationY, .1f)
            assertTrue(f.drag(deltaY = f.panel.height * .5f, cancelled = true) > 0)
            assertTrue(f.dialog.isShowing); assertEquals(0f, f.panel.translationY, .1f)
            assertEquals(0, f.writes)
        }
    }

    @Test fun upwardsGestureDoesNotMoveOrCloseTheSheet() {
        Fixture().use { f ->
            assertEquals(0f, f.drag(deltaY = -f.panel.height * .4f), .1f)
            assertTrue(f.dialog.isShowing); assertEquals(0f, f.panel.translationY, .1f)
        }
    }

    @Test fun scrollingTheFolderListDoesNotDragTheSheetEvenAtItsTop() {
        Fixture().use { f ->
            val scroll = views(f.panel).filterIsInstance<ScrollView>().single()
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            f.drag(scroll, -scroll.height * .4f)
            assertTrue("The gesture must actually scroll the folder contents", scroll.scrollY > 0)
            assertTrue(f.dialog.isShowing); assertEquals(0f, f.panel.translationY, .1f)
            scroll.scrollTo(0, 0)
            f.drag(scroll, f.panel.height * .4f)
            assertTrue(f.dialog.isShowing); assertEquals(0f, f.panel.translationY, .1f)
            assertEquals(0, f.writes)
        }
    }

    @Test fun savingPreventsDragDismissalAndFailureRestoresIt() {
        Fixture().use { f ->
            views(f.panel).filterIsInstance<TextView>().first { it.text.toString() == "完成" }.performClick()
            assertEquals(1, f.writes)
            assertFalse(f.handle.isEnabled)
            assertFalse(views(f.panel).single { it.contentDescription == "关闭收藏夹" }.isEnabled)
            f.drag(deltaY = f.panel.height * .4f)
            assertTrue(f.dialog.isShowing); assertEquals(0f, f.panel.translationY, .1f)
            assertEquals(0, f.results)
            f.reply!!(Result.failure(IllegalStateException("fixture network failure")))
            assertTrue(f.handle.isEnabled); assertEquals(1, f.results)
            f.drag(deltaY = f.panel.height * .4f)
            assertFalse(f.dialog.isShowing); assertEquals(1, f.writes)
        }
    }
}
