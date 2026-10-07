package com.ling.iwaraflow

import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class FloatingControlsTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Test fun inactivityHidesAllControlsAndTapRestoresThem() {
        val buttons = List(3) { View(RuntimeEnvironment.getApplication()) }
        val controls = FloatingControls(buttons)
        controls.show(); idle(2999); assertTrue(controls.visible)
        idle(1); assertTrue(buttons.all { it.visibility == View.INVISIBLE })
        controls.toggle(); assertTrue(buttons.all { it.visibility == View.VISIBLE })
        controls.toggle(); assertFalse(controls.visible)
        controls.close()
    }
    @Test fun interactionRestartsTimerAndDraggingSuspendsIt() {
        val controls = FloatingControls(listOf(View(RuntimeEnvironment.getApplication())))
        controls.show(); idle(2000); controls.show(); idle(2000); assertTrue(controls.visible)
        controls.suspend(); idle(6000); assertTrue(controls.visible)
        controls.resume(); idle(3000); assertFalse(controls.visible)
        controls.show(); controls.close(); idle(4000); assertTrue(controls.visible)
    }
}
