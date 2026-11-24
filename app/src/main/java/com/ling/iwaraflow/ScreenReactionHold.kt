package com.ling.iwaraflow

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import androidx.appcompat.content.res.AppCompatResources

/** One compact, transparent thumbs-up emblem, shown only after long-press confirmation. */
internal class ScreenReactionHold(private val view: View, complete: () -> Unit) {
    private val density = view.resources.displayMetrics.density
    // Inflate once while binding the card, never on the first animation frame.
    private val icon = AppCompatResources.getDrawable(view.context, R.drawable.ic_triple_reaction)?.mutate()
    private val ring = object : Drawable() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * density; strokeCap = Paint.Cap.ROUND
        }
        var progress = 0f
        var finishing = false
        override fun draw(canvas: Canvas) {
            if (finishing) {
                val scale = 1f + .16f * kotlin.math.sin(progress * Math.PI).toFloat()
                canvas.save()
                canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY())
                icon?.alpha = (255 * (1f - progress)).toInt()
                icon?.draw(canvas)
                canvas.restore()
                return
            }
            val inset = 4 * density
            paint.color = 0x66FFFFFF
            canvas.drawOval(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset, paint)
            paint.color = 0xFFFF557A.toInt()
            canvas.drawArc(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset,
                -90f, progress * 360f, false, paint)
            icon?.draw(canvas)
        }
        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val inset = (bounds.width() * .18f).toInt()
            icon?.setBounds(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        }
        override fun setAlpha(alpha: Int) { icon?.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { icon?.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private fun drawProgress(value: Float) { ring.progress = value; ring.invalidateSelf() }
    private val pulse = HoldProgress(::drawProgress) { view.overlay.remove(ring) }
    private val progress = HoldProgress(::drawProgress) {
        ring.finishing = true
        pulse.start(220L)
        complete()
    }
    val running get() = progress.running

    fun start(x: Float, y: Float, alreadyHeldMs: Long = 0L) {
        cancel()
        val margin = 8 * density
        val size = minOf(96 * density, view.width - 2 * margin, view.height - 2 * margin).coerceAtLeast(1f)
        val left = (x - size / 2).coerceIn(0f, maxOf(0f, view.width - size))
        val top = (y - size / 2).coerceIn(0f, maxOf(0f, view.height - size))
        ring.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        view.overlay.add(ring)
        progress.start(HoldReaction.DURATION_MS - alreadyHeldMs.coerceAtLeast(0L))
    }
    fun releaseTouch() { if (progress.running) cancel() }
    fun cancel() {
        progress.cancel()
        pulse.cancel()
        view.overlay.remove(ring)
        ring.finishing = false
        ring.progress = 0f
        icon?.alpha = 255
    }
}
