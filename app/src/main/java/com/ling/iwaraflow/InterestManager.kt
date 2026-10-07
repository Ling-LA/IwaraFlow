package com.ling.iwaraflow

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged

/** Fixed section tabs keep manual controls reachable even with a large learned profile. */
object InterestManager {
    fun show(host: Context, history: HistoryStore, onChanged: () -> Unit) {
        if (host !is Activity) return
        val context = android.view.ContextThemeWrapper(host, R.style.Theme_IwaraFlow_Dialog)
        val page = PageLayout(host, "兴趣管理")
        fun dp(n: Int) = page.dp(n)
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun label(value: String, size: Float = 14f) = TextView(context).apply {
            text = value; textSize = size; setTextColor(UiPalette.resolve(context, 0xFF48697F.toInt()))
            setPadding(0, dp(8), 0, dp(8))
        }
        fun button(value: String, active: Boolean = false, click: () -> Unit) = Button(context).apply {
            text = value; textSize = 13f; isAllCaps = false
            setTextColor(if (active) android.graphics.Color.WHITE else UiPalette.resolve(context, 0xFF285C7B.toInt()))
            backgroundTintList = null
            setBackgroundResource(if (active) R.drawable.bg_profile_primary else R.drawable.bg_profile_button)
            minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { click() }
        }
        fun rowButtons(parent: LinearLayout, entries: List<Pair<String, () -> Unit>>, selected: Int = -1) {
            val row = LinearLayout(context)
            entries.forEachIndexed { index, (name, click) ->
                row.addView(button(name, index == selected, click), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        fun grid(parent: LinearLayout, entries: List<Pair<String, () -> Unit>>) {
            entries.chunked(2).forEach { pair ->
                val row = LinearLayout(context)
                pair.forEachIndexed { index, (name, click) ->
                    row.addView(button(name, click = click).apply {
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        contentDescription = name
                    }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { if (index > 0) marginStart = dp(8) })
                }
                if (pair.size == 1) row.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
                parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        val tabs = column().apply { setPadding(dp(14), dp(10), dp(14), 0) }
        page.body.addView(tabs)
        val scroll = ScrollView(context)
        val panel = column().apply { setPadding(dp(14), dp(4), dp(14), dp(16)) }
        scroll.addView(panel)
        page.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var activeSection = 0
        lateinit var renderSection: () -> Unit

        fun card(): LinearLayout = page.card().apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            panel.addView(this)
        }
        fun showManual() {
            val content = card()
            content.addView(label("手动兴趣管理 · 主动调整兴趣", 18f))
            content.addView(label("仅调整推荐机会，不影响主动搜索。手动偏好有加减分上限，并保留其他题材。一个标签内可含空格，多个标签请分次添加。"))
            val input = EditText(context).apply {
                hint = "输入标签"; setSingleLine(true)
                filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            }
            content.addView(input)
            val actions = column()
            content.addView(actions)
            val rows = column()
            content.addView(rows)
            val limits = mutableMapOf(1 to 24, -1 to 24)
            lateinit var render: () -> Unit
            fun change(tag: String, preference: Int, added: Boolean = false) {
                runCatching { history.setManualTagPreference(tag, preference) }
                    .onSuccess { if (added) input.text.clear(); onChanged(); render() }
                    .onFailure { Toast.makeText(context, "保存失败，请输入一个有效标签", Toast.LENGTH_SHORT).show() }
            }
            rowButtons(actions, listOf(
                "添加感兴趣" to { change(input.text.toString(), 1, true) },
                "添加不感兴趣" to { change(input.text.toString(), -1, true) }
            ))
            render = {
                rows.removeAllViews()
                val preferences = history.manualTagPreferences()
                for ((value, title) in listOf(1 to "感兴趣 · 更多推荐", -1 to "不感兴趣 · 减少推荐")) {
                    val tags = preferences.filterValues { it == value }.keys.sorted()
                    rows.addView(label("$title（${tags.size}）", 16f))
                    if (tags.isEmpty()) rows.addView(label("暂无标签"))
                    grid(rows, tags.take(limits.getValue(value)).map { tag ->
                        "#$tag" to {
                            AlertDialog.Builder(context).setTitle("#$tag")
                                .setItems(arrayOf(if (value > 0) "改为不感兴趣" else "改为感兴趣", "移除主动偏好")) { _, which ->
                                    change(tag, if (which == 0) -value else 0)
                                }.show()
                            Unit
                        }
                    })
                    if (tags.size > limits.getValue(value)) rows.addView(button("展开更多（剩余 ${tags.size - limits.getValue(value)}）") {
                        limits[value] = limits.getValue(value) + 24; render()
                    })
                }
            }
            render()
        }
        fun showSystem() {
            val content = card()
            content.addView(label("系统兴趣管理", 18f))
            content.addView(label("系统根据观看、点赞、收藏和关注分析兴趣。分数有上限并随时间降低；调整比例不影响手动偏好。点击标签可调整，移除后可随时恢复。"))
            val search = EditText(context).apply { hint = "搜索系统标签"; setSingleLine(true) }
            content.addView(search)
            val categories = column()
            val rows = column()
            content.addView(categories); content.addView(rows)
            val scores = history.systemTagScores()
            val evidence = history.tagEvidence()
            var category = 0
            var limit = 24
            lateinit var render: () -> Unit
            render = {
                categories.removeAllViews(); rows.removeAllViews()
                val adjustments = history.systemTagMultipliers()
                val keys = (scores.keys + adjustments.keys).sortedWith(
                    compareByDescending<String> { kotlin.math.abs(scores[it] ?: 0.0) }.thenBy { it })
                rowButtons(categories, listOf("感兴趣", "不感兴趣", "已移除").mapIndexed { i, title ->
                    title to { category = i; limit = 24; render() }
                }, category)
                val selected = keys.filter { tag ->
                    val factor = adjustments[tag] ?: 1.0
                    val score = scores[tag] ?: 0.0
                    val matches = when (category) { 0 -> factor > 0 && score > 0; 1 -> factor > 0 && score < 0; else -> factor == 0.0 }
                    matches && tag.contains(search.text.toString().trim(), ignoreCase = true)
                }
                rows.addView(label("共 ${selected.size} 项 · 点击标签调整"))
                if (selected.isEmpty()) rows.addView(label(if (search.text.isBlank()) "暂无分析结果" else "没有匹配的标签"))
                grid(rows, selected.take(limit).map { tag ->
                    val factor = adjustments[tag] ?: 1.0
                    "#$tag\n${"%.2f".format(scores[tag] ?: 0.0)} · ${(factor * 100).toInt()}%" to {
                        val factors = doubleArrayOf(0.0, 0.25, 0.5, 1.0, 1.5)
                        AlertDialog.Builder(context).setTitle("调整 #$tag · 近期证据 ${(evidence.confidence(tag) * 100).toInt()}%")
                            .setItems(arrayOf("删除此项（忽略系统判断）", "25%", "50%", "100%（恢复）", "150%（受总上限约束）")) { _, index ->
                                history.setSystemTagMultiplier(tag, factors[index]); onChanged(); render()
                            }.show()
                        Unit
                    }
                })
                if (selected.size > limit) rows.addView(button("展开更多（剩余 ${selected.size - limit}）") { limit += 24; render() })
            }
            search.doAfterTextChanged { limit = 24; render() }
            render()
        }
        fun showMuted() {
            val content = card()
            content.addView(label("已屏蔽的作者和标签", 18f))
            content.addView(label("这些内容不会进入推荐。点击可恢复；主动设置标签偏好也会解除该标签的旧屏蔽。"))
            val muted = history.mutedEntities()
            val entries = (muted.authors + muted.authorIds).sorted().map { DislikeSheet.Kind.AUTHOR to it } +
                muted.tags.sorted().map { DislikeSheet.Kind.TAG to it }
            if (entries.isEmpty()) content.addView(label("暂无屏蔽"))
            val rows = column()
            content.addView(rows)
            var limit = 24
            lateinit var render: () -> Unit
            render = {
                rows.removeAllViews()
                grid(rows, entries.take(limit).map { (kind, key) ->
                    "恢复 ${if (kind == DislikeSheet.Kind.AUTHOR) "@" else "#"}$key" to {
                        runCatching { history.forgetDislike(kind, key) }
                            .onSuccess { onChanged(); renderSection() }
                            .onFailure { Toast.makeText(context, "恢复失败，请重试", Toast.LENGTH_SHORT).show() }
                        Unit
                    }
                })
                if (entries.size > limit) rows.addView(button("展开更多（剩余 ${entries.size - limit}）") { limit += 24; render() })
            }
            render()
        }
        renderSection = {
            tabs.removeAllViews(); panel.removeAllViews()
            rowButtons(tabs, listOf("手动兴趣", "系统兴趣", "已屏蔽").mapIndexed { index, title ->
                title to { activeSection = index; renderSection(); scroll.scrollTo(0, 0) }
            }, activeSection)
            when (activeSection) { 0 -> showManual(); 1 -> showSystem(); else -> showMuted() }
        }
        renderSection()
    }
}
