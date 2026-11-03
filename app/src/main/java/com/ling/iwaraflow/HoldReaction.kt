package com.ling.iwaraflow

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/** A single cancellable gesture, with no click after a completed or cancelled hold. */
class HoldReaction(private val view: View, color: Int, private val partner: View? = null,
    partnerColor: Int = color, private val complete: () -> Unit) {
    companion object { const val DURATION_MS = 1500L }
    private inner class Ring(color: Int) : Drawable() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 3f * view.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        var progress = 0f
        override fun draw(canvas: Canvas) {
            val inset = paint.strokeWidth / 2 + 1
            val radius = minOf(bounds.width(), bounds.height()) / 2f - inset
            val x = bounds.width() / 2f; val y = bounds.height() / 2f
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -90f, progress * 360f, false, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private val ring = Ring(color)
    private val partnerRing = Ring(partnerColor)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progress = HoldProgress({ value ->
        ring.progress = value; ring.invalidateSelf()
        partnerRing.progress = value; partnerRing.invalidateSelf()
    }) { if (!cancelled && !fired) { fired = true; complete() } }
    private val begin = Runnable {
        if (!cancelled && !fired) {
            ring.setBounds(0, 0, view.width, view.height); view.overlay.add(ring)
            partner?.let { partnerRing.setBounds(0, 0, it.width, it.height); it.overlay.add(partnerRing) }
            progress.start(DURATION_MS - (android.os.SystemClock.uptimeMillis() - startedAt))
        }
    }
    private var fired = false
    private var cancelled = false
    private var startX = 0f
    private var startY = 0f
    private var startedAt = 0L
    init {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel(); fired = false; cancelled = false
                    startX = event.x; startY = event.y
                    startedAt = android.os.SystemClock.uptimeMillis()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // A tap must not create overlay layers or start rendering two rings.
                    handler.postDelayed(begin, android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(450L))
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop * 2
                    if (kotlin.math.abs(event.x - startX) > slop || kotlin.math.abs(event.y - startY) > slop) {
                        cancelled = true; cancel()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val click = !fired && !cancelled && android.os.SystemClock.uptimeMillis() - startedAt < android.view.ViewConfiguration.getLongPressTimeout()
                    cancel(); view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (click) view.performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelled = true; cancel(); view.parent?.requestDisallowInterceptTouchEvent(false) }
            }
            true
        }
    }
    fun cancel() {
        cancelled = true; handler.removeCallbacks(begin); progress.cancel()
        ring.progress = 0f; partnerRing.progress = 0f
        view.overlay.remove(ring); partner?.overlay?.remove(partnerRing)
    }
}
