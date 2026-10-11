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
        history.post {
        val existing = history.downloadedQualities(item.id)
        activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        if (existing.isEmpty()) { proceed(); return@runOnUiThread }
        val qualities = existing.distinct().joinToString(" / ")
        val sameQuality = source.name in existing
        AlertDialog.Builder(activity)
            .setTitle("重新下载？")
            .setMessage(
                "这个视频已经下载过（$qualities）。" +
                    if (sameQuality) "再下一次「${source.name}」可能产生重复文件，确定吗？"
                    else "确定再下载一份「${source.name}」吗？"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("重新下载") { _, _ -> proceed() }
            .show()
        }
        }
    }
}
