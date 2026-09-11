package com.ling.iwaraflow

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 写评论的输入层：贴在键盘上方的一块输入卡片，盖在评论面板之上。
 *
 * 用独立的 Dialog 窗口而不是把输入框放在面板里：键盘只会顶起这个小窗口，
 * 后面的页面和评论面板都不动；点卡片外面的空白处就收起键盘、关掉输入层，
 * 已经打的字留作草稿，下次再点输入框还在。
 */
class CommentInputDialog(
    activity: Activity,
    replyTo: String?,
    draft: String,
    private val onSend: (String) -> Unit,
    private val onDraft: (String) -> Unit
) : Dialog(activity) {
    private val field: EditText

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.view_comment_input, null)
        setContentView(view)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.25f)
            // 键盘顶起的是这个窗口自己，页面不缩；一弹出就直接把键盘叫出来。
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        val target = view.findViewById<TextView>(R.id.inputReplyTarget)
        if (replyTo.isNullOrBlank()) target.visibility = View.GONE
        else { target.visibility = View.VISIBLE; target.text = replyTo }

        field = view.findViewById(R.id.inputField)
        field.hint = if (replyTo.isNullOrBlank()) "说点什么…" else "回复 $replyTo"
        field.setText(draft)
        field.setSelection(draft.length)
        field.requestFocus()

        view.findViewById<View>(R.id.inputSend).setOnClickListener { submit() }
        setOnDismissListener { onDraft(field.text.toString()) }
    }

    private fun submit() {
        val text = field.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(context, "先写点内容", Toast.LENGTH_SHORT).show()
            return
        }
        // 发出去的内容不再当草稿留着。
        field.setText("")
        dismiss()
        onSend(text)
    }
}
