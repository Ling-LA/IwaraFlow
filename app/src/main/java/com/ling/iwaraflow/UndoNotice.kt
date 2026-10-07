package com.ling.iwaraflow

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object UndoNotice {
    fun show(activity: Activity, label: String, undo: () -> Unit) {
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        host.findViewWithTag<View>("undo_notice")?.let(host::removeView)
        val dp = activity.resources.displayMetrics.density
        val bar = LinearLayout(activity).apply {
            tag = "undo_notice"; gravity = Gravity.CENTER_VERTICAL; setPadding((16*dp).toInt(), 0, (8*dp).toInt(), 0)
            setBackgroundColor(0xEE17324A.toInt())
        }
        bar.addView(TextView(activity).apply { text = label; setTextColor(-1); textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(activity).apply {
            text = "撤销"; setTextColor(0xFFFFD54F.toInt()); textSize = 16f; gravity = Gravity.CENTER
            minWidth = (64*dp).toInt(); minHeight = (48*dp).toInt(); isFocusable = true
            setOnClickListener { host.removeView(bar); undo() }
        })
        host.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = (24*dp).toInt() })
        bar.postDelayed({ host.removeView(bar) }, 6000)
    }
}
