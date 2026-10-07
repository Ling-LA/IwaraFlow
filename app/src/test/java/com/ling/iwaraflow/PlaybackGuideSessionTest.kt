package com.ling.iwaraflow

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackGuideSessionTest {
    class Host : AppCompatActivity(), PlaybackGuideHost {
        override val playbackGuideAdapter = mock(VideoAdapter::class.java)
        override fun setPlaybackGuideFullscreen(enabled: Boolean) {
            FullscreenMode.apply(this, playbackGuideAdapter, emptyList(), enabled)
        }
    }
    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(texts(view.getChildAt(it))) }
    }
    @Test fun switchRequestsRealRotationButNeverDrawsLandscapeBandsInPortrait() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        try {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlaybackGuide.show(activity, false)
            val guide = activity.window.decorView.findViewWithTag<View>("playback_gesture_guide") as PlaybackGuide.GuideView
            fun measure(w: Int, h: Int) {
                guide.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                guide.layout(0, 0, w, h)
            }
            measure(400, 900)
            texts(guide).first { it.text == "查看横屏操作" }.performClick()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            measure(400, 900) // Window rotation has not completed yet.
            assertTrue(texts(guide).any { it.text == "播放画面长按指引" })
            measure(900, 400)
            assertTrue(texts(guide).any { it.text == "横屏全屏操作引导" })
            assertEquals(300f, guide.labelAreas.first().width(), 0f)
            texts(guide).first { it.text == "知道了" }.performClick()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            assertFalse(PlaybackGuide.isActive(activity))
            verify(activity.playbackGuideAdapter, atLeastOnce()).setFullscreen(false, false)
            assertFalse(activity.getSharedPreferences(AppPrefs.FILE, 0).getBoolean(PlaybackGuide.key(true), false))
        } finally { PlaybackGuide.dismiss(activity); controller.pause().stop().destroy() }
    }
    @Test fun backRestoresOriginalFullscreenAndNotifiesExactlyOnce() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        try {
            `when`(activity.playbackGuideAdapter.isFullscreen).thenReturn(true)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            var completed = 0
            PlaybackGuide.show(activity, false) { completed++ }
            activity.onBackPressedDispatcher.onBackPressed()
            PlaybackGuide.dismiss(activity)
            assertEquals(1, completed)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            verify(activity.playbackGuideAdapter).setFullscreen(true, false)
            assertFalse(PlaybackGuide.isActive(activity))
        } finally { controller.pause().stop().destroy() }
    }
}
