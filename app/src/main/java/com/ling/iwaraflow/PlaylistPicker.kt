package com.ling.iwaraflow

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

internal object PlaylistPicker {
    fun show(activity: Activity, favorites: PlaylistFavorites, item: VideoItem, callback: (Result<PlaylistFavorites.Change>) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        fun text(value: String, size: Float = 16f) = TextView(activity).apply {
            text = value; textSize = size; setTextColor(UiPalette.resolve(context, 0xFF17324A.toInt()))
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(44); setPadding(dp(16), 0, dp(16), 0)
        }
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(UiPalette.resolve(activity, Color.WHITE)); cornerRadii = floatArrayOf(dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),0f,0f,0f,0f) }
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(View(activity).apply { background = GradientDrawable().apply { setColor(0xFFD3DCE2.toInt()); cornerRadius = dp(2).toFloat() } },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        val title = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(text("选择收藏夹", 19f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        title.addView(text("×", 25f).apply { contentDescription = "关闭收藏夹"; setOnClickListener { dialog.dismiss() } })
        root.addView(title)
        root.addView(text("与 Iwara 官方播放列表同步 · 公开", 12f).apply { minHeight = dp(28) })
        val create = text("＋ 新建收藏夹").apply { setTextColor(UiPalette.resolve(activity, 0xFF285D78.toInt())) }
        root.addView(create)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val status = text("正在读取收藏夹…", 14f)
        list.addView(status)
        root.addView(ScrollView(activity).apply { addView(list); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        val done = text("完成", 18f).apply {
            gravity = Gravity.CENTER; isEnabled = false
            background = GradientDrawable().apply { setColor(UiPalette.resolve(activity, 0xFFDCF0FC.toInt())); cornerRadius = dp(22).toFloat() }
        }
        root.addView(done, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), dp(8), dp(16), 0) })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.45f)
        dialog.show()
        val metrics = activity.resources.displayMetrics
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            val landscape = metrics.widthPixels > metrics.heightPixels
            setLayout(if (landscape) (metrics.widthPixels * .55).toInt() else -1, (metrics.heightPixels * if (landscape) .9 else .6).toInt())
        }
        val selected = LinkedHashSet<String>()
        var busy = false
        var loadGeneration = 0
        fun enableRows(enabled: Boolean) {
            for (index in 0 until list.childCount) list.getChildAt(index).isEnabled = enabled
        }
        fun load(selectNew: String? = null, preserveDraft: Boolean = false) {
            val generation = ++loadGeneration
            val draft = selected.toSet()
            done.isEnabled = false; create.isEnabled = false; enableRows(false)
            favorites.loadPicker(item) { result ->
                if (!dialog.isShowing || activity.isDestroyed || generation != loadGeneration) return@loadPicker
                result.onSuccess { rows ->
                    selected.clear()
                    selected.addAll(if (preserveDraft) draft.intersect(rows.map { it.id }.toSet()) else rows.filter { it.added }.map { it.id })
                    selectNew?.let(selected::add)
                    list.removeAllViews()
                    if (rows.isEmpty()) list.addView(text("还没有收藏夹，点击上方新建", 14f))
                    rows.forEach { folder ->
                        list.addView(CheckBox(activity).apply {
                            text = "${folder.title}\n${folder.count} 个视频 · 公开"; textSize = 16f
                            setTextColor(UiPalette.resolve(context, 0xFF17324A.toInt()))
                            minHeight = dp(70); setPadding(dp(16), dp(8), dp(16), dp(8)); isChecked = folder.id in selected
                            contentDescription = "收藏夹 ${folder.title}"
                            setOnCheckedChangeListener { _, checked -> if (!busy) { if (checked) selected.add(folder.id) else selected.remove(folder.id) } }
                        })
                        list.addView(View(activity).apply { setBackgroundColor(UiPalette.resolve(context, 0xFFEAF0F5.toInt())) }, LinearLayout.LayoutParams(-1, dp(1)))
                    }
                    done.isEnabled = true; create.isEnabled = true
                }.onFailure {
                    create.isEnabled = true
                    list.removeAllViews(); list.addView(text("读取失败：${it.message}\n点此重试", 14f).apply { setOnClickListener { load(selectNew, preserveDraft) } })
                }
            }
        }
        create.setOnClickListener {
            if (!busy) createDialog(activity, favorites) { folder -> load(folder.id, preserveDraft = true) }
        }
        done.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true; done.isEnabled = false; create.isEnabled = false; enableRows(false); done.text = "正在保存…"
            favorites.applySelection(item, selected.toSet()) { result ->
                busy = false
                if (!dialog.isShowing || activity.isDestroyed) return@applySelection
                result.onSuccess { dialog.dismiss(); callback(Result.success(it)) }.onFailure {
                    callback(Result.failure(it)); Toast.makeText(activity, "保存未完成，将重新读取官网已保存的选择", Toast.LENGTH_LONG).show()
                    done.text = "完成"; create.isEnabled = true; load()
                }
            }
        }
        load()
    }

    fun createDialog(activity: Activity, favorites: PlaylistFavorites, created: (PlaylistFolder) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((20*density).toInt(), 0, (20*density).toInt(), 0) }
        val input = EditText(activity).apply { hint = "收藏夹名称"; setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(100)) }
        content.addView(input)
        content.addView(TextView(activity).apply { text = "创建 Iwara 官方公开播放列表，可在官网查看。"; textSize = 13f })
        val dialog = AlertDialog.Builder(activity).setTitle("新建收藏夹").setView(content).setNegativeButton("取消", null).setPositiveButton("创建", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().trim().isBlank()) { input.error = "请输入收藏夹名称"; return@setOnClickListener }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                favorites.createFolder(input.text.toString()) { result ->
                    if (!dialog.isShowing || activity.isDestroyed) return@createFolder
                    result.onSuccess { dialog.dismiss(); created(it) }.onFailure {
                        button.isEnabled = true; input.error = it.message
                    }
                }
            }
        }
        dialog.show()
    }
}
