package com.ling.iwaraflow

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * “不感兴趣”底部面板：长按画面上半区弹出，像哔哩哔哩那样按类型选——
 * 只是这条视频、这个作者，还是某个标签。选完直接写进口味画像。
 */
object DislikeSheet {
    enum class Kind { VIDEO, AUTHOR, TAG }

    /** 作者 / 标签的负反馈强度；标签在画像里按 0.45 折算，所以给得更重。 */
    const val VIDEO_WEIGHT = -0.4
    const val AUTHOR_WEIGHT = -2.5
    const val TAG_WEIGHT = -4.0
    const val MAX_TAGS = 8

    fun show(context: Context, item: VideoItem, history: HistoryStore, onApplied: ((VideoItem, Kind) -> Unit)?) {
        val activity = hostActivity(context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_comments_panel)
        }
        list.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) }
            setBackgroundColor(0xFFD5DEE6.toInt())
        })
        fun row(label: String, strong: Boolean = false, onClick: () -> Unit) {
            list.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(if (strong) 0xFF17324A.toInt() else 0xFF285C7B.toInt())
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(50)
                setPadding(dp(18), 0, dp(18), 0)
                background = with(android.util.TypedValue()) {
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                    androidx.core.content.ContextCompat.getDrawable(activity, resourceId)
                }
                setOnClickListener { dialog.dismiss(); onClick() }
            })
        }
        fun divider() {
            list.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(0xFFEEF2F6.toInt())
            })
        }

        row("不感兴趣：当前视频", strong = true) { apply(activity, item, history, Kind.VIDEO, "", onApplied) }
        if (item.author.isNotBlank()) {
            divider()
            row("不感兴趣：作者 @${item.author}") { apply(activity, item, history, Kind.AUTHOR, "", onApplied) }
        }
        item.tags.take(MAX_TAGS).forEach { tag ->
            divider()
            row("不感兴趣：标签 #$tag") { apply(activity, item, history, Kind.TAG, tag, onApplied) }
        }
        divider()
        row("取消") { }

        dialog.setContentView(ScrollView(activity).apply { addView(list) })
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.3f)
        }
        dialog.show()
    }

    /** 只把选中的那一维写进画像：作者不带标签，标签不带作者。 */
    internal fun apply(context: Context, item: VideoItem, history: HistoryStore, kind: Kind, tag: String, onApplied: ((VideoItem, Kind) -> Unit)?) {
        when (kind) {
            Kind.VIDEO -> {
                history.recordInteraction(item, "dislike_video", VIDEO_WEIGHT)
                Toast.makeText(context, "已跳过这条视频", Toast.LENGTH_SHORT).show()
            }
            Kind.AUTHOR -> {
                history.recordInteraction(VideoItem(item.id, item.title, item.author, emptyList(), 0, authorId = item.authorId), "dislike_author", AUTHOR_WEIGHT)
                Toast.makeText(context, "会减少 @${item.author} 的推荐", Toast.LENGTH_SHORT).show()
            }
            Kind.TAG -> {
                history.recordInteraction(VideoItem(item.id, item.title, "", listOf(tag), 0), "dislike_tag", TAG_WEIGHT)
                Toast.makeText(context, "会减少 #$tag 的推荐", Toast.LENGTH_SHORT).show()
            }
        }
        history.markSeen(item.id)
        onApplied?.invoke(item, kind)
    }

    private fun hostActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
