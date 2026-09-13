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
            setPadding(dp(12), dp(4), dp(12), dp(12))
            background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_comments_panel)
        }
        val sheet = ScrollView(activity).apply { addView(list) }
        // 拖拽把手：整块 28dp 高都能按到，按住往下拖过面板高度的四分之一（或者甩得够快）就收起。
        val handle = android.widget.FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
            addView(View(activity).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER)
                setBackgroundColor(0xFFD5DEE6.toInt())
            })
            setOnTouchListener(DragToDismiss(sheet) { dialog.dismiss() })
        }
        list.addView(handle)
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

        dialog.setContentView(sheet)
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

    /** 和评论面板同一套手势：面板跟着手指走，松手时拖得够远或够快就收起，否则弹回。 */
    private class DragToDismiss(private val sheet: View, private val dismiss: () -> Unit) : View.OnTouchListener {
        private var startY = 0f
        private var startTime = 0L
        private var dragging = false
        private val slop = android.view.ViewConfiguration.get(sheet.context).scaledTouchSlop

        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY; startTime = event.eventTime; dragging = false
                    sheet.animate().cancel()
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    if (!dragging && dy > slop) dragging = true
                    if (dragging) sheet.translationY = dy.coerceAtLeast(0f)
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dy = event.rawY - startY
                    val elapsed = (event.eventTime - startTime).coerceAtLeast(1L)
                    val fling = dy > slop * 2 && dy / elapsed > 1.2f
                    val farEnough = dy > sheet.height * CommentsPanel.DISMISS_FRACTION
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP && dragging && (farEnough || fling)) {
                        sheet.animate().translationY(sheet.height.toFloat()).setDuration(160L).withEndAction { dismiss() }.start()
                    } else {
                        sheet.animate().translationY(0f).setDuration(160L).start()
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }
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
