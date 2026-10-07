package com.ling.iwaraflow

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/** Three seconds from touch down. The owner handles movement, UP and lifecycle cancellation. */
internal class ScreenReactionHold(private val view: View, private val complete: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = view.resources.displayMetrics.density
    private var startedAt = 0L
    var running = false
        private set
    private val ring = object : Drawable() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var progress = 0f
        override fun draw(canvas: Canvas) {
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY(); val radius = 35 * density
            paint.style = Paint.Style.FILL; paint.color = 0xAA111820.toInt()
            canvas.drawCircle(x, y, radius + 6 * density, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFFFFD54F.toInt()
            canvas.drawArc(x-radius, y-radius, x+radius, y+radius, -90f, progress*360, false, paint)
            paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = 18 * density
            canvas.drawText("♥ + ★", x, y + 6 * density, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            ring.progress = (elapsed / 3000f).coerceIn(0f, 1f); ring.invalidateSelf()
            if (elapsed >= 3000) { running = false; complete() }
            else handler.postDelayed(this, minOf(16L, 3000L - elapsed))
        }
    }
    fun start(x: Float, y: Float) {
        cancel(); running = true; startedAt = SystemClock.uptimeMillis()
        val radius = (42*density).toInt()
        val cx = x.toInt().coerceIn(radius, maxOf(radius, view.width-radius))
        val cy = y.toInt().coerceIn(radius, maxOf(radius, view.height-radius))
        ring.setBounds(cx-radius, cy-radius, cx+radius, cy+radius)
        view.overlay.add(ring); handler.post(tick)
    }
    fun cancel() { running = false; handler.removeCallbacks(tick); view.overlay.remove(ring); ring.progress = 0f }
}
