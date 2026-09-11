package com.ling.iwaraflow

import android.app.Activity
import android.app.AlertDialog

/** 下载前的确认：同一个视频已经下载过就先问一声，别让人不小心下第二份。 */
object DownloadPrompt {
    fun confirmIfDuplicate(
        activity: Activity,
        history: HistoryStore,
        item: VideoItem,
        source: VideoSource,
        proceed: () -> Unit
    ) {
        val existing = history.downloadRecords(limit = 5000).filter { it.item.id == item.id }
        if (existing.isEmpty()) { proceed(); return }
        val qualities = existing.map { it.quality }.distinct().joinToString(" / ")
        val sameQuality = existing.any { it.quality == source.name }
        AlertDialog.Builder(activity)
            .setTitle("重新下载？")
            .setMessage(
                "这个视频已经下载过（$qualities）。" +
                    if (sameQuality) "再下一次「${source.name}」会覆盖原来那份，确定吗？"
                    else "确定再下载一份「${source.name}」吗？"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("重新下载") { _, _ -> proceed() }
            .show()
    }
}
