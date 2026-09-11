package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SavedVideosActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var listView: RecyclerView
    private lateinit var adapter: SavedVideoListAdapter
    private val items = mutableListOf<VideoItem>()
    private val downloads = mutableMapOf<String, DownloadLibrary.Entry>()
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_videos)

        api = IwaraApi(this)
        history = HistoryStore(this)
        listView = findViewById(R.id.savedVideos)
        adapter = SavedVideoListAdapter(items, ::openVideo) { item -> downloads[item.id]?.note() }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<View>(R.id.savedBack).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_HISTORY
        findViewById<TextView>(R.id.savedTitle).text = when (kind) {
            KIND_FAVORITES -> "我的收藏"
            KIND_DOWNLOADS -> "已下载"
            else -> "浏览历史"
        }

        items += when (kind) {
            KIND_FAVORITES -> history.localFavorites(300)
            KIND_DOWNLOADS -> DownloadLibrary.entries(this, history)
                .also { entries -> entries.forEach { downloads[it.item.id] = it } }
                .map { it.item }
            else -> history.recentHistory(200)
        }
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.savedSubtitle).text = when (kind) {
            KIND_FAVORITES -> "共 ${items.size} 条"
            KIND_DOWNLOADS -> downloadsSubtitle()
            else -> "最近观看的 ${items.size} 条视频"
        }
        enrichMissingVideoDetails()
    }

    private fun downloadsSubtitle(): String {
        if (items.isEmpty()) return "还没有下载过视频"
        val ready = downloads.values.filter { it.state == DownloadLibrary.State.READY }
        val size = DownloadLibrary.formatSize(ready.sumOf { it.sizeBytes })
        return buildString {
            append("共 ${items.size} 个")
            if (ready.size < items.size) append(" · ${ready.size} 个可播放")
            if (size.isNotEmpty()) append(" · $size")
        }
    }

    private fun enrichMissingVideoDetails() {
        items.forEachIndexed { index, original ->
            api.getVideo(original.id) { result ->
                result.onSuccess { fresh ->
                    if (closed) return@onSuccess
                    runOnUiThread {
                        if (closed || index !in items.indices || items[index].id != original.id) return@runOnUiThread
                        val keepFavorite = original.localFavorite || history.isLocalFavorite(original.id)
                        val keepPosition = original.resumePositionMs
                        items[index] = fresh.copy(localFavorite = keepFavorite, resumePositionMs = keepPosition)
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    /**
     * 下好的视频直接放本地那一份——下载就是为了这个。还没下完 / 文件不在了的，
     * 退回到照常在线播放，别让这一行点了没反应。
     */
    private fun openVideo(item: VideoItem) {
        val local = downloads[item.id]?.takeIf { it.state == DownloadLibrary.State.READY }?.localUri
        val data = Intent().putExtra(EXTRA_VIDEO_ID, item.id).putExtra(EXTRA_TITLE, item.title)
        // 本地文件交给主页的播放器直接放，不拉起外部播放器，也不去网上再拉一遍。
        if (local != null) data.putExtra(EXTRA_LOCAL_URI, local.toString())
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        closed = true
        api.close()
        history.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "saved_kind"
        const val EXTRA_VIDEO_ID = "video_id"
        /** 已下载视频的本地地址（content:// 或 file://），主页据此直接播本地文件。 */
        const val EXTRA_LOCAL_URI = "local_uri"
        const val EXTRA_TITLE = "video_title"
        const val KIND_HISTORY = "history"
        const val KIND_FAVORITES = "favorites"
        const val KIND_DOWNLOADS = "downloads"
    }
}
