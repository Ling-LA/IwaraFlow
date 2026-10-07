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
            if (heading) tag = "section_heading"
            textSize = if (heading) 18f else 14f
            setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
            if (heading) setTypeface(null, android.graphics.Typeface.BOLD)
            panel.addView(this)
        }
        label("系统兴趣管理", true)
        label("系统根据观看、点赞、收藏和关注分析兴趣。分数有上限，并随时间降低；调整比例只改变系统分数，不影响手动偏好。0% 表示忽略该标签的系统判断，可随时恢复。")
        val systemRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(systemRows)
        fun renderSystem() {
            systemRows.removeAllViews()
            val scores = history.systemTagScores()
            val adjustments = history.systemTagMultipliers()
            val keys = (scores.keys + adjustments.keys).sortedByDescending { kotlin.math.abs(scores[it] ?: 0.0) }
            for ((positive, heading) in listOf(true to "感兴趣", false to "不感兴趣")) {
                systemRows.addView(TextView(context).apply { text = heading; textSize = 16f })
                val selected = keys.filter { ((scores[it] ?: 0.0) >= 0) == positive && (adjustments[it] ?: 1.0) > 0 }
                if (selected.isEmpty()) systemRows.addView(TextView(context).apply { text = "暂无分析结果" })
                selected.forEach { tag ->
                    val factor = adjustments[tag] ?: 1.0
                    systemRows.addView(Button(context).apply {
                        text = "#$tag  ${"%.2f".format(scores[tag] ?: 0.0)} · ${(factor * 100).toInt()}%"
                        isAllCaps = false
                        setOnClickListener {
                            val factors = doubleArrayOf(0.0, 0.25, 0.5, 1.0, 1.5)
                            AlertDialog.Builder(context).setTitle("调整 #$tag 系统权重")
                                .setItems(arrayOf("删除此项（忽略系统判断）", "25%", "50%", "100%（恢复）", "150%（受总上限约束）")) { _, index ->
                                    history.setSystemTagMultiplier(tag, factors[index]); onChanged(); renderSystem()
                                }.show()
                        }
                    })
                }
            }
            val ignored = adjustments.filterValues { it == 0.0 }.keys
            if (ignored.isNotEmpty()) systemRows.addView(TextView(context).apply { text = "已移除的系统判断 · 点击恢复" })
            ignored.forEach { tag ->
                systemRows.addView(Button(context).apply {
                    text = "恢复 #$tag"; isAllCaps = false
                    setOnClickListener { history.setSystemTagMultiplier(tag, 1.0); onChanged(); renderSystem() }
                })
            }
        }
        renderSystem()
        label("手动兴趣管理 · 主动调整兴趣", true)
        label("感兴趣的标签会获得更多推荐机会，同时保留其他题材；不感兴趣的标签会减少推荐。仅影响推荐，不影响主动搜索。空格连接一个标签内的单词，多个标签请分次添加。")
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
        PageLayout.styleSections(panel)
        if (host is android.app.Activity) PageLayout(host, "兴趣管理").scroll(panel)
    }
}
