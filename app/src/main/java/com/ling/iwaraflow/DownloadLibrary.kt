package com.ling.iwaraflow

import android.app.DownloadManager
import android.content.Context
import android.net.Uri

/**
 * “已下载”那一页的数据。
 *
 * 下载走的是系统下载器，所以本地表只记“下过什么”，真正的状态要问系统：
 * 文件可能还在下、可能失败了、也可能被用户删掉了。两边对上以后才知道这一条
 * 到底还能不能播。
 */
object DownloadLibrary {

    enum class State {
        /** 下好了，本地文件还在，可以直接播。 */
        READY,
        RUNNING,
        FAILED,
        /** 系统下载器里已经查不到这条记录了——多半是用户清了下载列表或删了文件。 */
        GONE
    }

    data class Entry(
        val record: DownloadRecord,
        val state: State,
        val localUri: Uri?,
        val sizeBytes: Long
    ) {
        val item: VideoItem get() = record.item

        /** 列表里那行小字：清晰度 + 状态。下好了就不用特意说“已完成”。 */
        fun note(): String? = when (state) {
            State.READY -> null
            State.RUNNING -> "下载中 · ${record.quality}"
            State.FAILED -> "下载失败 · ${record.quality}"
            State.GONE -> "文件已不在 · ${record.quality}"
        }
    }

    fun entries(context: Context, history: HistoryStore): List<Entry> {
        val records = history.downloadRecords()
        if (records.isEmpty()) return emptyList()
        val status = queryStatus(context, records.map { it.downloadId })
        return records.map { record ->
            val row = status[record.downloadId]
            when {
                row == null -> Entry(record, State.GONE, null, 0L)
                row.status == DownloadManager.STATUS_SUCCESSFUL ->
                    Entry(record, State.READY, row.localUri, row.sizeBytes)
                row.status == DownloadManager.STATUS_FAILED -> Entry(record, State.FAILED, null, 0L)
                else -> Entry(record, State.RUNNING, null, row.sizeBytes)
            }
        }
    }

    private class Row(val status: Int, val localUri: Uri?, val sizeBytes: Long)

    private fun queryStatus(context: Context, ids: List<Long>): Map<Long, Row> {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return emptyMap()
        val out = HashMap<Long, Row>()
        // 一次查全部，别一条一条问——列表有几百条时那是几百次跨进程调用。
        runCatching {
            manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                val idIndex = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sizeIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    if (idIndex < 0 || statusIndex < 0) continue
                    val id = c.getLong(idIndex)
                    val local = if (uriIndex >= 0) c.getString(uriIndex)?.let(Uri::parse) else null
                    val size = if (sizeIndex >= 0) c.getLong(sizeIndex).coerceAtLeast(0L) else 0L
                    out[id] = Row(c.getInt(statusIndex), local, size)
                }
            }
        }
        return out
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
        else -> ""
    }
}
