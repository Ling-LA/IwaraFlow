package com.ling.iwaraflow

import android.content.Context
import android.widget.*

object DanmakuSettings {
    fun create(context: Context): ScrollView {
        val prefs = AppPrefs(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        }
        panel.addView(CheckBox(context).apply {
            text = "显示评论弹幕"; isChecked = prefs.danmakuEnabled
            setOnCheckedChangeListener { _, value -> prefs.danmakuEnabled = value }
        })
        panel.addView(TextView(context).apply { text = "当前视频评论从右向左滚动，设置即时保存。" })
        panel.addView(CheckBox(context).apply {
            text = "将弹幕翻译为中文"; isChecked = prefs.translateDanmaku
            setOnCheckedChangeListener { _, value -> prefs.translateDanmaku = value }
        })
        panel.addView(TextView(context).apply {
            text = "复用设置中的翻译服务，按需翻译正在显示的弹幕。使用付费 AI 模型可能额外消耗 token 并产生费用；翻译失败时保留原文。"
            textSize = 12f; setTextColor(UiPalette.resolve(context, 0xFF607D93.toInt()))
        })
        fun slider(title: String, min: Int, max: Int, value: Int, save: (Int) -> Unit) {
            val label = TextView(context).apply { text = "$title：$value"; setPadding(0, 20, 0, 0) }
            panel.addView(label)
            panel.addView(SeekBar(context).apply {
                this.max = max - min; progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) {
                        if (user) { save(progress + min); label.text = "$title：${progress + min}" }
                    }
                })
            })
        }
        slider("同时显示行数", 1, 6, prefs.danmakuDensity) { prefs.danmakuDensity = it }
        slider("字体大小", 12, 32, prefs.danmakuSize) { prefs.danmakuSize = it }
        slider("不透明度 %", 20, 100, prefs.danmakuOpacity) { prefs.danmakuOpacity = it }
        slider("穿过画面的秒数（越小越快）", 4, 20, prefs.danmakuDuration) { prefs.danmakuDuration = it }
        panel.addView(TextView(context).apply { text = "显示位置" })
        panel.addView(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, arrayOf("上半屏", "下半屏", "全屏"))
            setSelection(prefs.danmakuRegion)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { prefs.danmakuRegion = position }
            }
        })
        return ScrollView(context).apply { addView(panel) }
    }
}
