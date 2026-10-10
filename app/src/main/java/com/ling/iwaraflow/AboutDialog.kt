package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object AboutDialog {
    const val GITHUB = "https://github.com/Ling-LA/IwaraFlow"
    fun show(activity: Activity) {
        val d = activity.resources.displayMetrics.density
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding((24*d).toInt(), (8*d).toInt(), (24*d).toInt(), (8*d).toInt())
        }
        @Suppress("DEPRECATION")
        val version = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "未知"
        panel.addView(TextView(activity).apply {
            text = "IwaraFlow $version\n\nAndroid 原生 Iwara 视频浏览客户端\n作者：Ling-LA\n\n支持连续播放、搜索、兴趣推荐和弹幕评论。\n本应用为第三方客户端。"
            textSize = 15f; setTextColor(UiPalette.resolve(activity, 0xFF17324A.toInt()))
        })
        panel.addView(TextView(activity).apply {
            text = "GitHub\n$GITHUB"; textSize = 14f
            setPadding(0, (20*d).toInt(), 0, (12*d).toInt()); setTextColor(UiPalette.resolve(activity, 0xFF285C7B.toInt()))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            contentDescription = "打开 IwaraFlow GitHub 项目"
            setOnClickListener {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) }
                    .onFailure { Toast.makeText(activity, "没有可打开链接的应用", Toast.LENGTH_SHORT).show() }
            }
        })
        AlertDialog.Builder(activity).setTitle("关于 IwaraFlow").setView(panel).setPositiveButton("关闭", null).show()
    }
}
