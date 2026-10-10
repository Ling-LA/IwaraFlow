package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DislikeLayoutTest {
    @Test fun shortPanelFitsContentAndMeetsBottomInBothOrientationsWithoutAFullWidthFooter() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get(); val history = HistoryStore(activity)
        try {
            DislikeSheet.show(activity, VideoItem("fixture", "Example", "", emptyList(), 1), history, null)
            val dialog = ShadowDialog.getLatestDialog()
            val panel = dialog.window!!.decorView.findViewWithTag<View>("dislike_panel")
            val root = panel.parent as FrameLayout
            val density = activity.resources.displayMetrics.density
            val inset = (24*density).toInt()
            for ((w, h) in listOf(360 to 800, 800 to 360)) {
                val width = (w*density).toInt(); val height = (h*density).toInt()
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, inset)).build())
                repeat(3) {
                    root.forceLayout()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                assertEquals(height, panel.bottom)
                assertEquals(inset, panel.paddingBottom)
                assertTrue("panel=${panel.width}x${panel.height}, root=${root.width}x${root.height}, density=$density", panel.height < height * .7)
                assertTrue(panel.width < width)
                assertEquals(1, root.childCount) // The panel alone paints the navigation safe area.
            }
            dialog.dismiss()
        } finally { history.close(); controller.pause().stop().destroy() }
    }
    @Test fun manyOptionsStopAtSixtyPercentAndScrollAboveTheSafeArea() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get(); val history = HistoryStore(activity)
        try {
            DislikeSheet.show(activity, VideoItem("fixture", "Example", "Creator", List(8) { "tag-$it" }, 1), history, null)
            val dialog = ShadowDialog.getLatestDialog()
            val panel = dialog.window!!.decorView.findViewWithTag<android.widget.LinearLayout>("dislike_panel")
            val root = panel.parent as FrameLayout
            val density = activity.resources.displayMetrics.density
            val width = (360*density).toInt(); val height = (640*density).toInt()
            repeat(3) {
                root.forceLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            assertEquals((height*.6f).toInt(), panel.height)
            val scroll = panel.getChildAt(3) as android.widget.ScrollView
            assertTrue(scroll.isVerticalScrollBarEnabled)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            scroll.isSmoothScrollingEnabled = false
            scroll.fullScroll(View.FOCUS_DOWN)
            assertTrue(scroll.scrollY > 0)
            dialog.dismiss()
        } finally { history.close(); controller.pause().stop().destroy() }
    }

}
