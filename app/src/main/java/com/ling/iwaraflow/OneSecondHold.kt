package com.ling.iwaraflow

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Consume the touch so Android's shorter default long-click cannot fire first. */
internal class OneSecondHold(private val view: View, private val action: () -> Unit) : View.OnTouchListener {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var x = 0f
    private var y = 0f
    private var down = false
    private var fired = false
    private val hold = Runnable {
        if (down && view.isAttachedToWindow && view.isShown && view.isEnabled) {
            fired = true; view.isPressed = false
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            action()
        }
    }
    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = cancel()
        })
        view.setOnTouchListener(this)
        view.setOnLongClickListener { action(); true } // Accessibility long-click action.
    }
    fun cancel() { down = false; view.isPressed = false; view.removeCallbacks(hold) }
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel(); down = true; fired = false; x = event.x; y = event.y
                v.isPressed = true; v.postDelayed(hold, 1_000L)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x-x) > slop || abs(event.y-y) > slop) cancel()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancel()
            MotionEvent.ACTION_UP -> {
                val click = down && !fired
                cancel()
                if (click) v.performClick()
            }
        }
        return true
    }
}
