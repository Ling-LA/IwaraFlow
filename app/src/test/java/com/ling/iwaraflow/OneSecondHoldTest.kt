package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OneSecondHoldTest {
    private fun touch(view: View, action: Int, x: Float = 5f) {
        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, 5f, 0)
        view.dispatchTouchEvent(event); event.recycle()
    }
    private fun fixture(block: (TextView, () -> Pair<Int, Int>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = TextView(controller.get())
        controller.get().setContentView(view)
        var clicks = 0; var holds = 0
        view.setOnClickListener { clicks++ }
        OneSecondHold(view) { holds++ }
        try { block(view) { clicks to holds } } finally { controller.pause().stop().destroy() }
    }
    @Test fun shortTapOnlyOpensRegularComments() = fixture { view, counts ->
        touch(view, MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        touch(view, MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1 to 0, counts())
    }
    @Test fun opensComposerExactlyOnceAfterOneSecondWithoutClickingOnRelease() = fixture { view, counts ->
        touch(view, MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(0 to 0, counts())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(0 to 1, counts())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        touch(view, MotionEvent.ACTION_UP)
        assertEquals(0 to 1, counts())
    }
    @Test fun movementAndCancellationDoNotPostOrOpenComposer() = fixture { view, counts ->
        touch(view, MotionEvent.ACTION_DOWN); touch(view, MotionEvent.ACTION_MOVE, 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); touch(view, MotionEvent.ACTION_UP)
        touch(view, MotionEvent.ACTION_DOWN); touch(view, MotionEvent.ACTION_CANCEL)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0 to 0, counts())
    }
}
