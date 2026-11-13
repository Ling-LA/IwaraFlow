package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object AboutDialog {
    const val GITHUB = AppLinks.REPOSITORY
    fun show(activity: Activity) {
        val d = activity.resources.displayMetrics.density
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding((24*d).toInt(), (8*d).toInt(), (24*d).toInt(), (8*d).toInt())
        }
        @Suppress("DEPRECATION")
        val version = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: UiText.text(R.string.ui_4d8c1c5b4283, "未知")
        panel.addView(TextView(activity).apply {
            text = UiText.text(R.string.ui_42a6a6ce791f, "IwaraFlow %1\$s\n\nAndroid 原生 Iwara 视频浏览客户端\n作者：Ling-LA\n\n支持连续播放、搜索、兴趣推荐和弹幕评论。\n本应用为第三方客户端。", version)
            textSize = 15f; setTextColor(UiPalette.resolve(activity, 0xFF17324A.toInt()))
        })
        fun link(label: String, url: String, description: String) {
            panel.addView(TextView(activity).apply {
                text = "$label\n$url"; textSize = 14f
                setPadding(0, (20*d).toInt(), 0, (12*d).toInt()); setTextColor(UiPalette.resolve(activity, 0xFF285C7B.toInt()))
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                contentDescription = description
                setOnClickListener {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        .onFailure { Toast.makeText(activity, UiText.text(R.string.ui_3f7dff01b3a5, "没有可打开链接的应用"), Toast.LENGTH_SHORT).show() }
                }
            })
        }
        link("GitHub", GITHUB, UiText.text(R.string.ui_28aac9504243, "打开 IwaraFlow GitHub 项目"))
        AppLinks.NETWORK_PROMOTIONS.forEachIndexed { index, url ->
            link(UiText.text(R.string.ui_fddec6569571, "网络服务推广链接 %1\$s", index + 1), url, UiText.text(R.string.ui_1196cc854d0b, "打开网络服务推广链接 %1\$s", index + 1))
        }
        val content = ScrollView(activity).apply { addView(panel) }
        AlertDialog.Builder(activity).setTitle(UiText.text(R.string.ui_dbd93fd13287, "关于 IwaraFlow")).setView(content).setPositiveButton(UiText.text(R.string.ui_3fd47edce45b, "关闭"), null).show()
    }
}
