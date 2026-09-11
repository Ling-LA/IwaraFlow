package com.ling.iwaraflow

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 应用内的分享面板：底部一排目标应用的图标，点谁就直接拉起谁。
 *
 * 不走系统选择器：直接以显式 Intent 启动目标应用的分享入口，QQ 这类应用会以自己的
 * 小窗卡片盖在本应用上面（和哔哩哔哩分享到 QQ 的效果一样），视频在后面继续播。
 * 面板本身是本页面的 Dialog，弹出时不触发 Activity 生命周期，播放不受影响。
 */
object SharePanel {
    class Target(val label: String, val packageName: String, val icon: Drawable?, val intent: Intent)

    /** 常用的社交应用排在前面；其余按名字排。 */
    internal val PREFERRED = listOf(
        "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.mm", "com.qzone",
        "com.sina.weibo", "org.telegram.messenger", "com.twitter.android", "com.discord"
    )

    internal fun rank(packageName: String): Int =
        PREFERRED.indexOf(packageName).let { if (it < 0) PREFERRED.size else it }

    internal fun sendIntent(text: String, subject: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }

    /** 能接收纯文本分享的应用，每个一条显式 Intent；本应用自己除外。 */
    fun targets(context: Context, text: String, subject: String): List<Target> {
        val send = sendIntent(text, subject)
        val pm = context.packageManager
        val resolved = runCatching { pm.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrDefault(emptyList())
        return resolved
            .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
            .map { info ->
                val activity = info.activityInfo
                Target(
                    label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull().orEmpty().ifBlank { activity.packageName },
                    packageName = activity.packageName,
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    intent = Intent(send).setComponent(ComponentName(activity.packageName, activity.name))
                )
            }
            .sortedWith(compareBy<Target> { rank(it.packageName) }.thenBy { it.label })
    }

    /**
     * 弹出面板。[launch] 负责真正启动目标应用——播放页要用自己的 launcher，
     * 这样返回时能接上状态。
     */
    fun show(activity: Activity, text: String, subject: String, chooserTitle: String, launch: (Intent) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.view_share_panel, null)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.3f)
        }

        val row = view.findViewById<LinearLayout>(R.id.shareTargets)
        val targets = targets(activity, text, subject)
        val density = activity.resources.displayMetrics.density
        targets.forEach { target ->
            row.addView(targetView(activity, target.label, target.icon, density) {
                dialog.dismiss()
                runCatching { launch(target.intent) }
                    .onFailure { Toast.makeText(activity, "打不开 ${target.label}", Toast.LENGTH_SHORT).show() }
            })
        }
        if (targets.isEmpty()) view.findViewById<View>(R.id.shareEmpty).visibility = View.VISIBLE

        view.findViewById<View>(R.id.shareCopy).setOnClickListener {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText(subject, text))
            Toast.makeText(activity, "链接已复制", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.shareMore).setOnClickListener {
            dialog.dismiss()
            runCatching { launch(Intent.createChooser(sendIntent(text, subject), chooserTitle)) }
                .onFailure { Toast.makeText(activity, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
        }
        view.findViewById<View>(R.id.shareCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun targetView(context: Context, label: String, icon: Drawable?, density: Float, onClick: () -> Unit): View {
        val dp = { v: Int -> (v * density).toInt() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) }
                text = label
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(0xFF17324A.toInt())
            })
            setOnClickListener { onClick() }
        }
    }
}
