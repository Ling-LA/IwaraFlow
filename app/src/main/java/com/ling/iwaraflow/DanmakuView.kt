package com.ling.iwaraflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.media3.common.Player

/** Comments move left to right in non-overlapping lanes; clock follows playback, not wall time. */
class DanmakuView(context: Context) : View(context) {
    private val prefs = AppPrefs(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; setShadowLayer(2f, 1f, 1f, 0xFF000000.toInt()) }
    var player: Player? = null
    private var comments = emptyList<String>()
    private var baseline = 0L
    fun setComments(values: List<IwaraComment>) {
        comments = values.map { android.text.Html.fromHtml(it.body, 0).toString().replace(Regex("\\s+"), " ").take(160) }
            .filter { it.isNotBlank() }.distinct().take(80)
        baseline = player?.currentPosition ?: 0L
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = player ?: return
        if (prefs.danmakuEnabled && comments.isNotEmpty() && width > 0) {
            paint.textSize = prefs.danmakuSize * resources.displayMetrics.scaledDensity
            paint.alpha = prefs.danmakuOpacity * 255 / 100
            val line = paint.textSize * 1.5f
            val available = (height - paddingTop - paddingBottom).coerceAtLeast(0)
            val area = if (prefs.danmakuRegion == 2) available else available / 2
            val lanes = (area / line).toInt().coerceIn(1, 6)
            val top = paddingTop + if (prefs.danmakuRegion == 1) available / 2 else 0
            val duration = prefs.danmakuDuration * 1000L
            val clock = (p.currentPosition - baseline).coerceAtLeast(0L)
            canvas.save(); canvas.clipRect(0, paddingTop, width, height - paddingBottom)
            repeat(lanes) { lane ->
                val shifted = clock - lane * duration / lanes
                if (shifted >= 0) {
                    val cycle = shifted / duration
                    val text = comments[((cycle * lanes + lane) % comments.size).toInt()]
                    val w = paint.measureText(text)
                    val fraction = shifted % duration / duration.toFloat()
                    canvas.drawText(text, -w + fraction * (width + w), top + line * (lane + 1), paint)
                }
            }
            canvas.restore()
        }
        if (isAttachedToWindow) postInvalidateDelayed(if (p.isPlaying) 33L else 250L)
    }
    fun clear() { comments = emptyList(); player = null; invalidate() }
}
