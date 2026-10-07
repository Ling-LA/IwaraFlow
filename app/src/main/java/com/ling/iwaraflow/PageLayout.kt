package com.ling.iwaraflow

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Shared page shell matching the author profile. */
class PageLayout(val activity: Activity, title: String) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPalette.resolve(context, 0xFFEEF8FE.toInt()))
    }
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            setBackgroundColor(UiPalette.resolve(context, 0xFFDDF1FC.toInt()))
            addView(text("‹  返回", 16f).apply {
                minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { activity.finish() }
            })
            addView(text(title, 25f).apply { setTypeface(null, Typeface.BOLD) })
        }
        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        activity.setContentView(root)
    }
    fun text(value: String, size: Float = 15f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(UiPalette.resolve(context, 0xFF17324A.toInt()))
    }
    fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(UiPalette.resolve(activity, Color.WHITE)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), UiPalette.resolve(activity, 0xFFD7E9F4.toInt()))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(8), dp(14), dp(8)) }
    }
    fun action(label: String, onClick: () -> Unit): View = text(label).apply {
        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0); isFocusable = true
        setOnClickListener { onClick() }
    }
    fun scroll(content: View) { body.addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1)) }

    companion object {
        /** Group section children into the same white cards used by profile pages. */
        fun styleSections(panel: LinearLayout) {
            val children = (0 until panel.childCount).map { panel.getChildAt(it) }
            panel.removeAllViews()
            val dp = panel.resources.displayMetrics.density
            panel.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            var card: LinearLayout? = null
            children.forEach { child ->
                if (card == null || child.tag == "section_heading") {
                    card = LinearLayout(panel.context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                        background = GradientDrawable().apply {
                            setColor(UiPalette.resolve(panel.context, Color.WHITE)); cornerRadius = 18 * dp; setStroke(dp.toInt().coerceAtLeast(1), UiPalette.resolve(panel.context, 0xFFD7E9F4.toInt()))
                        }
                        panel.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * dp).toInt() })
                    }
                }
                card!!.addView(child)
            }
        }
    }
}
