package com.ling.iwaraflow

import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 把视频链接分享到其它应用；所有播放页面共用同一套分享文案。 */
object VideoShare {
    fun linkFor(item: VideoItem): String = "https://www.iwara.tv/video/${item.id}"

    fun shareText(item: VideoItem): String {
        val link = linkFor(item)
        val title = item.title.trim()
        return if (title.isBlank()) link else "$title\n$link"
    }

    fun share(context: Context, item: VideoItem) {
        if (item.id.isBlank()) {
            Toast.makeText(context, "这个视频没有可分享的链接", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText(item))
            putExtra(Intent.EXTRA_SUBJECT, item.title)
        }
        val chooser = Intent.createChooser(send, "分享视频链接")
        // 从 Activity 之外（例如 application context）拉起选择器时必须自带新任务标记。
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
    }
}
