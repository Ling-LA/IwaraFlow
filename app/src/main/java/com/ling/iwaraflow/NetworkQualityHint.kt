package com.ling.iwaraflow

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

internal class NetworkQualityHint(context: Context) : AppCompatTextView(context) {
    var onVisibilityChange: (() -> Unit)? = null
    private var stable = false
    private val dismiss = Runnable { if (stable) hide() }
    init {
        val d = resources.displayMetrics.density
        textSize = 14f; setTextColor(0xFF285C7B.toInt()); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(0xEEE4E9ED.toInt()); cornerRadius = 18*d }
        setPadding((14*d).toInt(), (10*d).toInt(), (14*d).toInt(), (10*d).toInt())
        visibility = View.GONE; tag = "network_quality_hint"
    }
    fun offer(quality: String, action: () -> Unit) {
        val label = "网络不佳 · 点击切换至 $quality"
        text = SpannableString(label).apply {
            val start = label.indexOf("点击切换")
            setSpan(UnderlineSpan(), start, start+4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val wasHidden = visibility != View.VISIBLE
        visibility = View.VISIBLE; setOnClickListener { action() }
        if (wasHidden) { onVisibilityChange?.invoke(); if (stable) postDelayed(dismiss, 3_000L) }
    }
    fun networkStable(value: Boolean) {
        if (stable == value) return
        stable = value; removeCallbacks(dismiss)
        if (stable && visibility == View.VISIBLE) postDelayed(dismiss, 3_000L)
    }
    fun hide() {
        removeCallbacks(dismiss)
        if (visibility != View.GONE) { visibility = View.GONE; onVisibilityChange?.invoke() }
    }
    fun reset() { stable = false; hide() }
    override fun onDetachedFromWindow() { reset(); super.onDetachedFromWindow() }
}
