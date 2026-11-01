package com.ling.iwaraflow

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Install on a sheet's handle/header only; scrolling its content must remain independent. */
internal class SheetDragToDismiss(private val sheet: View, private val dismiss: () -> Unit) : View.OnTouchListener {
    private var startY = 0f
    private var startTime = 0L
    private var dragging = false
    private val slop = ViewConfiguration.get(sheet.context).scaledTouchSlop

    init {
        sheet.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                sheet.animate().cancel()
                sheet.translationY = 0f
                dragging = false
            }
        })
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                startTime = event.eventTime
                dragging = false
                sheet.animate().cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - startY
                if (!dragging && dy > slop) dragging = true
                if (dragging) sheet.translationY = dy.coerceAtLeast(0f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = event.rawY - startY
                val elapsed = (event.eventTime - startTime).coerceAtLeast(1L)
                val fling = dy > slop * 2 && dy / elapsed > 1.2f
                val farEnough = dy > sheet.height * CommentsPanel.DISMISS_FRACTION
                if (event.actionMasked == MotionEvent.ACTION_UP && dragging && (farEnough || fling)) {
                    sheet.animate().translationY(sheet.height.toFloat()).setDuration(160L)
                        .withEndAction { dismiss() }.start()
                } else {
                    sheet.animate().translationY(0f).setDuration(160L).start()
                }
                dragging = false
                return true
            }
        }
        return false
    }
}
