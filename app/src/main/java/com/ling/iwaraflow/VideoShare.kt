package com.ling.iwaraflow

import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 把视频链接分享到其它应用；所有播放页面共用同一套分享文案。 */
object VideoShare {
    fun linkFor(item: VideoItem): String = "https://www.iwara.tv/video/${item.id}"

    fun shareText(item: VideoItem): String {
        val link = linkFor(item)
        val title = item.title.trim().ifBlank { "未命名视频" }
        val author = item.author.trim().ifBlank { item.authorUsername.trim().ifBlank { "未知作者" } }
        val time = if (item.createdAt > 0L) java.time.Instant.ofEpochMilli(item.createdAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) else "未知"
        return "标题：$title\n作者：$author\n发布时间：$time\n链接：$link"
    }

    /**
     * 有 Activity 就弹应用内的分享面板（点目标应用直接拉起，QQ 等会以小窗盖在本页上）；
     * 只有普通 Context 时退回系统选择器。
     */
    fun share(context: Context, item: VideoItem) {
        if (item.id.isBlank()) {
            Toast.makeText(context, "这个视频没有可分享的链接", Toast.LENGTH_SHORT).show()
            return
        }
        val activity = context as? android.app.Activity
        if (activity != null) {
            SharePanel.show(activity, shareText(item), item.title, "分享视频链接") { start(activity, it) }
        } else {
            start(context, chooser(context, shareText(item), item.title, "分享视频链接"))
        }
    }

    /**
     * 只造出选择器 Intent，不负责启动。
     * 播放页要自己 launch，这样返回时能接上播放状态、也不会在分享面板后面自动进小窗。
     */
    fun chooserFor(context: Context, item: VideoItem): Intent? {
        if (item.id.isBlank()) {
            Toast.makeText(context, "这个视频没有可分享的链接", Toast.LENGTH_SHORT).show()
            return null
        }
        return chooser(context, shareText(item), item.title, "分享视频链接")
    }

    fun authorLinkFor(author: IwaraAuthor): String =
        "https://www.iwara.tv/profile/${UriEncoder.encodePathSegment(author.username)}"

    /** 作者名片明确标明分享的是作者主页。 */
    fun authorShareText(author: IwaraAuthor): String {
        val name = author.name.trim().ifBlank { author.username }
        return buildString {
            append("作者：").append(name.ifBlank { "未知作者" })
            if (author.username.isNotBlank()) append("\n链接：").append(authorLinkFor(author))
        }
    }

    fun shareAuthor(context: Context, author: IwaraAuthor) {
        if (author.username.isBlank() && author.name.isBlank()) {
            Toast.makeText(context, "这个作者没有可分享的资料", Toast.LENGTH_SHORT).show()
            return
        }
        val activity = context as? android.app.Activity
        if (activity != null) {
            SharePanel.show(activity, authorShareText(author), author.name, "分享作者主页") { start(activity, it) }
        } else {
            start(context, chooser(context, authorShareText(author), author.name, "分享作者主页"))
        }
    }

    private fun chooser(context: Context, text: String, subject: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(send, title)
        // 从 Activity 之外（例如 application context）拉起选择器时必须自带新任务标记。
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooser
    }

    private fun start(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
    }
}
