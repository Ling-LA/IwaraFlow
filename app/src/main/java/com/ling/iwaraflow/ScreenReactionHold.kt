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

/** Shown only after long-press confirmation; the full press still completes after 2.5 seconds. */
internal class ScreenReactionHold(private val view: View, private val complete: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = view.resources.displayMetrics.density
    private var startedAt = 0L
    private var animationDuration = HoldReaction.DURATION_MS
    var running = false
        private set
    private val ring = object : Drawable() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var progress = 0f
        override fun draw(canvas: Canvas) {
            val radius = (bounds.width() - 40*density) / 4f
            val y = bounds.top + 12*density + radius
            paint.style = Paint.Style.FILL; paint.color = 0xC0182532.toInt()
            canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), 20*density, 20*density, paint)
            fun drawReaction(x: Float, color: Int, symbol: String) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4*density; paint.strokeCap = Paint.Cap.ROUND; paint.color = color
                canvas.drawArc(x-radius, y-radius, x+radius, y+radius, -90f, progress*360, false, paint)
                paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = radius*.82f
                canvas.drawText(symbol, x, y-(paint.ascent()+paint.descent())/2, paint)
            }
            drawReaction(bounds.left+12*density+radius, 0xFFFF365D.toInt(), "♥")
            drawReaction(bounds.right-12*density-radius, 0xFFFFD54F.toInt(), "★")
            paint.color = -1; paint.textSize = 12*density
            canvas.drawText("按住完成点赞＋收藏", bounds.exactCenterX(), bounds.bottom-8*density, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            ring.progress = (elapsed / animationDuration.toFloat()).coerceIn(0f, 1f); ring.invalidateSelf()
            if (elapsed >= animationDuration) { running = false; complete() }
            else handler.postDelayed(this, minOf(16L, animationDuration - elapsed))
        }
    }
    fun start(x: Float, y: Float, alreadyHeldMs: Long = 0L) {
        cancel(); running = true; startedAt = SystemClock.uptimeMillis()
        animationDuration = (HoldReaction.DURATION_MS - alreadyHeldMs.coerceAtLeast(0L)).coerceAtLeast(1L)
        val margin = 8*density
        val radius = minOf(52*density, (view.width-56*density)/4f, (view.height-64*density)/2f).coerceAtLeast(8*density)
        val width = 4*radius+40*density; val height = 2*radius+40*density
        val left = (x-width/2).coerceIn(margin, maxOf(margin, view.width-margin-width))
        val top = (y-height/2).coerceIn(margin, maxOf(margin, view.height-margin-height))
        ring.setBounds(left.toInt(), top.toInt(), (left+width).toInt(), (top+height).toInt())
        view.overlay.add(ring); handler.post(tick)
    }
    fun cancel() { running = false; handler.removeCallbacks(tick); view.overlay.remove(ring); ring.progress = 0f }
}
