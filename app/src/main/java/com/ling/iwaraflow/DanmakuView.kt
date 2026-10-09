package com.ling.iwaraflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.media3.common.Player

/** Comments move right to left in non-overlapping lanes; clock follows playback, not wall time. */
class DanmakuView(context: Context) : View(context) {
    private val prefs = AppPrefs(context)
    private val settings = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("danmaku_") == true) {
            if (!prefs.translateDanmaku || !prefs.danmakuEnabled) cancelTranslations()
            postInvalidate()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); settings.registerOnSharedPreferenceChangeListener(settingsListener); invalidate() }
    override fun onDetachedFromWindow() { settings.unregisterOnSharedPreferenceChangeListener(settingsListener); cancelTranslations(); super.onDetachedFromWindow() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; setShadowLayer(2f, 1f, 1f, 0xFF000000.toInt()) }
    var player: Player? = null
        set(value) { field = value; if (value == null) cancelTranslations() }
    private var comments = emptyList<String>()
    private var postedComments = emptyList<String>()
    private var baseline = 0L
    private val widths = HashMap<String, Float>()
    private var measuredSize = 0f
    private var translationGeneration = 0
    private var translationConfig = Translator.config
    private val translated = HashMap<String, String>()
    private val attempted = HashSet<String>()
    private val translating = HashMap<String, RequestCancellation?>()
    private fun cancelTranslations() {
        translationGeneration++
        translating.values.forEach { it?.cancel() }; translating.clear(); attempted.clear()
    }
    private fun displayText(original: String): String {
        if (!prefs.translateDanmaku || !Translator.needsTranslation(original)) return original
        if (translationConfig != Translator.config) {
            cancelTranslations(); translated.clear(); translationConfig = Translator.config
        }
        translated[original]?.let { return it }
        Translator.cached(original)?.let { translated[original] = compact(it.text); return translated.getValue(original) }
        if (isAttachedToWindow && isShown && attempted.add(original)) {
            val token = translationGeneration
            translating[original] = null
            val request = Translator.translate(original) { result ->
                if (token == translationGeneration && prefs.translateDanmaku && prefs.danmakuEnabled) {
                    translating.remove(original)
                    result.onSuccess { translated[original] = compact(it.text); widths.clear(); invalidate() }
                }
            }
            if (translating.containsKey(original)) translating[original] = request
        }
        return original
    }
    private fun compact(text: String) = android.text.Html.fromHtml(text, 0).toString()
        .replace(Regex("\\s+"), " ").trim().take(90)
    fun setComments(values: List<IwaraComment>) {
        cancelTranslations(); translated.clear()
        comments = (postedComments + values.map { compact(it.body) }).filter { it.isNotBlank() }.distinct().take(80)
        widths.clear()
        baseline = player?.currentPosition ?: 0L
        invalidate()
    }
    fun appendComment(value: IwaraComment) {
        val text = compact(value.body).takeIf { it.isNotBlank() } ?: return
        postedComments = (listOf(text) + postedComments.filter { it != text }).take(80)
        comments = (listOf(text) + comments.filter { it != text }).take(80)
        baseline = player?.currentPosition ?: 0L
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = player ?: return
        if (prefs.danmakuEnabled && comments.isNotEmpty() && width > 0) {
            paint.textSize = prefs.danmakuSize * resources.displayMetrics.scaledDensity
            if (measuredSize != paint.textSize) { measuredSize = paint.textSize; widths.clear() }
            paint.alpha = prefs.danmakuOpacity * 255 / 100
            val line = paint.textSize * 1.5f
            val available = (height - paddingTop - paddingBottom).coerceAtLeast(0)
            val area = if (prefs.danmakuRegion == 2) available else available / 2
            val lanes = (area / line).toInt().coerceIn(0, prefs.danmakuDensity)
            val top = paddingTop + if (prefs.danmakuRegion == 1) available / 2 else 0
            val duration = prefs.danmakuDuration * 1000L
            val clock = (p.currentPosition - baseline).coerceAtLeast(0L)
            canvas.save(); canvas.clipRect(0, paddingTop, width, height - paddingBottom)
            repeat(lanes) { lane ->
                val shifted = clock - lane * duration / lanes
                if (shifted >= 0) {
                    val cycle = shifted / duration
                    val text = displayText(comments[((cycle * lanes + lane) % comments.size).toInt()])
                    val w = widths.getOrPut(text) { paint.measureText(text) }
                    val fraction = shifted % duration / duration.toFloat()
                    canvas.drawText(text, horizontalPosition(width.toFloat(), w, fraction), top + line * (lane + 1), paint)
                }
            }
            canvas.restore()
        }
        if (isAttachedToWindow && isShown && prefs.danmakuEnabled && comments.isNotEmpty() && p.isPlaying)
            postInvalidateOnAnimation()
    }
    fun clear() { cancelTranslations(); translated.clear(); postedComments = emptyList(); comments = emptyList(); player = null; invalidate() }
    companion object {
        internal fun horizontalPosition(width: Float, textWidth: Float, fraction: Float) =
            width - fraction.coerceIn(0f, 1f) * (width + textWidth)
    }
}
