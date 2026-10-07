package com.ling.iwaraflow

import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A view on the current window; every tap is consumed, so dismissing cannot like or pause a video. */
object PlaybackGuide {
    private const val TAG = "playback_gesture_guide"
    internal fun key(horizontal: Boolean) = if (horizontal) "fullscreen_guide_v1_landscape" else "playback_guide_v2_portrait"
    fun showOnce(activity: Activity, horizontal: Boolean, onDismiss: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, 0)
        if (prefs.getBoolean(key(horizontal), false)) { onDismiss(); return }
        if (activity.isFinishing || activity.isDestroyed) return
        prefs.edit().putBoolean(key(horizontal), true).apply()
        show(activity, horizontal, onDismiss)
    }
    fun dismiss(activity: Activity) {
        val root = activity.window.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }
    fun show(activity: Activity, horizontal: Boolean = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE, onDismiss: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss(activity)
        val root = activity.window.decorView as? ViewGroup ?: return
        root.addView(GuideView(activity, horizontal) { dismiss(activity); onDismiss() }.apply { tag = TAG }, ViewGroup.LayoutParams(-1, -1))
    }

    internal class GuideView(activity: Activity, private var horizontal: Boolean, close: () -> Unit) : FrameLayout(activity) {
        private val density = resources.displayMetrics.density
        private fun dp(value: Int) = (value*density).toInt()
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); strokeWidth = 2*density; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(8*density, 7*density), 0f)
        }
        private val heading = TextView(activity).apply {
            text = "播放操作引导"; textSize = 20f; setTextColor(-1); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        private val zones = List(3) { TextView(activity).apply {
            textSize = 15f; setTextColor(-1); gravity = Gravity.CENTER; setLineSpacing(5*density, 1f)
            setPadding(dp(10), dp(4), dp(10), dp(4)); typeface = Typeface.DEFAULT_BOLD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } }
        private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        private val switch = TextView(activity).apply { textSize = 12f; setTextColor(-1); gravity = Gravity.CENTER; minHeight = dp(40) }
        init {
            setWillNotDraw(false); setBackgroundColor(0xD0182532.toInt()); isClickable = true; isFocusable = true
            addView(heading); zones.forEach { addView(it) }
            footer.addView(TextView(activity).apply {
                text = "手势用于播放画面，控件各自响应操作\n单击暂停 / 显示控件 · 左右滑动调进度 · 双击点赞"; textSize = 12f; setTextColor(-1); gravity = Gravity.CENTER
            })
            val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
            actions.addView(switch, LinearLayout.LayoutParams(dp(148), dp(44)))
            actions.addView(TextView(activity).apply {
                text = "知道了"; contentDescription = "关闭操作引导"; textSize = 15f; setTextColor(0xFF17324A.toInt()); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(-1); cornerRadius = 24*density }
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(100), dp(44)))
            footer.addView(actions); addView(footer)
            switch.setOnClickListener { horizontal = !horizontal; updateLabels(); requestLayout(); invalidate() }
            updateLabels()
        }
        private fun updateLabels() {
            heading.text = if (horizontal) "横屏全屏操作引导" else "竖屏与普通播放操作引导"
            zones[0].text = "⊘\n长按${if (horizontal) "左侧" else "上方"}\n不感兴趣\n选择视频、作者或标签"
            zones[1].text = "♡ + ☆\n按住中间 2.5 秒\n点赞＋收藏\n提前松开即可取消"
            zones[2].text = "≫\n长按${if (horizontal) "右侧" else "下方"}\n2× 加速\n松开恢复正常速度"
            switch.text = if (horizontal) "查看竖屏操作" else "查看横屏操作"
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            heading.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY))
            footer.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(88), MeasureSpec.EXACTLY))
            val zoneWidth = if (horizontal) w/3 else w
            val zoneHeight = if (horizontal) (h-dp(138)).coerceAtLeast(dp(80)) else ((h-dp(138))/3).coerceAtLeast(dp(72))
            zones.forEach {
                val compact = !horizontal && h/density < 560
                it.textSize = if (compact) 12f else 15f
                it.setLineSpacing((if (compact) 2 else 5)*density, 1f)
                it.measure(MeasureSpec.makeMeasureSpec(zoneWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(zoneHeight, MeasureSpec.EXACTLY))
            }
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            heading.layout(0, dp(8), width, dp(58)); footer.layout(0, height-dp(88), width, height)
            zones.forEachIndexed { i, zone ->
                val x = if (horizontal) i*width/3 else 0
                val y = if (horizontal) dp(58) else dp(58) + i*zone.measuredHeight
                zone.layout(x, y, x+zone.measuredWidth, y+zone.measuredHeight)
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (i in 1..2) {
                if (horizontal) canvas.drawLine(width*i/3f, dp(62).toFloat(), width*i/3f, height-dp(96).toFloat(), pen)
                else {
                    val y = height*i/3f
                    canvas.drawLine(dp(20).toFloat(), y, width-dp(20).toFloat(), y, pen)
                }
            }
        }
    }
}
