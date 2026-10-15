package com.ling.iwaraflow

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DanmakuReplayTest {
    @Test fun playerReplacementAndOrdinarySeeksKeepConsumedStateButSeekingToStartResetsIt() {
        val view = DanmakuView(RuntimeEnvironment.getApplication())
        val prefs = AppPrefs(view.context); prefs.danmakuEnabled = true; prefs.danmakuDuration = 4
        val first = mock(Player::class.java)
        view.player = first
        view.layout(0, 0, 400, 240)
        view.setComments(listOf(IwaraComment("one", "示例评论", IwaraAuthor("a", "作者", "author"), 0)))
        val bitmap = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
        val timeline = view.javaClass.getDeclaredField("timeline").apply { isAccessible = true }.get(view) as DanmakuTimeline
        try {
            view.draw(Canvas(bitmap))
            doReturn(5000L).`when`(first).currentPosition
            view.draw(Canvas(bitmap))
            assertFalse(timeline.hasRemaining())
            view.player = null
            val second = mock(Player::class.java)
            doReturn(5000L).`when`(second).currentPosition
            view.player = second
            assertFalse(timeline.hasRemaining())
            val listener = ArgumentCaptor.forClass(Player.Listener::class.java)
            verify(second).addListener(listener.capture())
            fun position(value: Long) = Player.PositionInfo(null, 0, null, null, 0, value, value, -1, -1)
            listener.value.onPositionDiscontinuity(position(5000), position(3000), Player.DISCONTINUITY_REASON_SEEK)
            assertFalse(timeline.hasRemaining())
            listener.value.onPositionDiscontinuity(position(3000), position(0), Player.DISCONTINUITY_REASON_SEEK)
            assertTrue(timeline.hasRemaining())
            doReturn(0L).`when`(second).currentPosition
            view.draw(Canvas(bitmap))
            doReturn(5000L).`when`(second).currentPosition
            view.draw(Canvas(bitmap))
            assertFalse(timeline.hasRemaining())
            view.restart()
            assertTrue(timeline.hasRemaining())
        } finally { view.clear(); bitmap.recycle() }
    }
}
