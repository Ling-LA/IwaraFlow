package com.ling.iwaraflow

import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Explicit tag controls and the existing hard-mute recovery list share one screen. */
object InterestManager {
    fun show(host: Context, history: HistoryStore, onChanged: () -> Unit) {
        val context = android.view.ContextThemeWrapper(host, R.style.Theme_IwaraFlow_Dialog)
        val dp = context.resources.displayMetrics.density
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        fun label(text: String, heading: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = if (heading) 18f else 14f
            setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
            if (heading) setTypeface(null, android.graphics.Typeface.BOLD)
            panel.addView(this)
        }
        label("主动调整兴趣", true)
        label("感兴趣的标签会获得更多推荐机会；不感兴趣的标签会减少推荐。仅影响推荐，不影响主动搜索。空格连接一个标签内的单词，多个标签请分次添加。")
        val input = EditText(context).apply {
            hint = "输入标签，例如 mmd / hatsune_miku"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
        }
        panel.addView(input)
        val buttons = LinearLayout(context)
        panel.addView(buttons)
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rows)
        var refreshMutes: () -> Unit = {}
        fun change(tag: String, preference: Int, after: () -> Unit) {
            runCatching { history.setManualTagPreference(tag, preference) }
                .onSuccess { onChanged(); after(); refreshMutes() }
                .onFailure { Toast.makeText(context, "保存失败，请输入一个有效标签", Toast.LENGTH_SHORT).show() }
        }
        fun render() {
            rows.removeAllViews()
            val preferences = history.manualTagPreferences()
            for ((value, title) in listOf(1 to "感兴趣 · 更多推荐", -1 to "不感兴趣 · 减少推荐")) {
                rows.addView(TextView(context).apply { text = title; textSize = 16f })
                val tags = preferences.filterValues { it == value }.keys
                if (tags.isEmpty()) rows.addView(TextView(context).apply { text = "暂无标签" })
                tags.forEach { tag ->
                    rows.addView(Button(context).apply {
                        text = "#$tag"
                        isAllCaps = false
                        setOnClickListener {
                            AlertDialog.Builder(context).setTitle("#$tag")
                                .setItems(arrayOf(if (value > 0) "改为不感兴趣" else "改为感兴趣", "移除主动偏好")) { _, which ->
                                    change(tag, if (which == 0) -value else 0) { render() }
                                }.show()
                        }
                    })
                }
            }
        }
        for ((value, title) in listOf(1 to "添加感兴趣", -1 to "添加不感兴趣")) {
            buttons.addView(Button(context).apply {
                text = title
                setOnClickListener {
                    change(input.text.toString(), value) { input.text.clear(); render() }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        render()
        label("已屏蔽的作者和标签", true)
        label("此前通过长按屏蔽的内容不会进入推荐。点击可恢复；主动设置标签偏好也会解除该标签的旧屏蔽。")
        val muteRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(muteRows)
        fun renderMutes() {
            muteRows.removeAllViews()
            val muted = history.mutedEntities()
            val entries = (muted.authors + muted.authorIds).sorted().map { DislikeSheet.Kind.AUTHOR to it } +
                muted.tags.sorted().map { DislikeSheet.Kind.TAG to it }
            if (entries.isEmpty()) muteRows.addView(TextView(context).apply { text = "暂无屏蔽" })
            entries.forEach { (kind, key) ->
                muteRows.addView(Button(context).apply {
                    text = "恢复 ${if (kind == DislikeSheet.Kind.AUTHOR) "作者 @" else "标签 #"}$key"
                    isAllCaps = false
                    setOnClickListener {
                        runCatching { history.forgetDislike(kind, key) }
                            .onSuccess { onChanged(); renderMutes() }
                            .onFailure { Toast.makeText(context, "恢复失败，请重试", Toast.LENGTH_SHORT).show() }
                    }
                })
            }
        }
        refreshMutes = ::renderMutes
        renderMutes()
        AlertDialog.Builder(context).setTitle("兴趣管理")
            .setView(ScrollView(context).apply { addView(panel) })
            .setPositiveButton("完成", null).show()
    }
}
