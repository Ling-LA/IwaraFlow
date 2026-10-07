package com.ling.iwaraflow

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PageSurfaceTest {
    private fun labels(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) repeat(view.childCount) { addAll(labels(view.getChildAt(it))) }
    }
    @Test fun settingsAreAFullPageWithPlaybackAndInterestControls() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val labels = labels(controller.get().window.decorView)
            assertTrue(labels.contains("设置")); assertTrue(labels.contains("保存"))
            assertTrue(labels.contains("网络稳定时提前加载后续视频"))
            assertTrue(labels.contains("兴趣管理"))
            assertTrue(labels.contains("‹  返回"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun interestPageSeparatesSystemAndManualSections() {
        val controller = Robolectric.buildActivity(InterestActivity::class.java).setup()
        try {
            val labels = labels(controller.get().window.decorView)
            assertTrue(labels.contains("系统兴趣"))
            assertTrue(labels.contains("手动兴趣管理 · 主动调整兴趣"))
            assertTrue(labels.contains("已屏蔽"))
        } finally { controller.pause().stop().destroy() }
    }
}
