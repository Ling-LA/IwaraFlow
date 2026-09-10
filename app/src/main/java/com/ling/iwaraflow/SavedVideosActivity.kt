package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SavedVideosActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var listView: RecyclerView
    private lateinit var adapter: AuthorVideoListAdapter
    private val items = mutableListOf<VideoItem>()
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_videos)

        api = IwaraApi(this)
        history = HistoryStore(this)
        listView = findViewById(R.id.savedVideos)
        adapter = AuthorVideoListAdapter(items, ::openVideo)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<View>(R.id.savedBack).setOnClickListener { finish() }
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_HISTORY
        val isFavorite = kind == KIND_FAVORITES
        findViewById<TextView>(R.id.savedTitle).text = if (isFavorite) "本地收藏" else "浏览历史"

        val raw = if (isFavorite) history.localFavorites(300) else history.recentHistory(200)
        items += raw
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.savedSubtitle).text = if (isFavorite) {
            "${items.size} 条 · 仅保存在本机"
        } else {
            "最近观看的 ${items.size} 条视频"
        }
        enrichMissingVideoDetails()
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

    private fun openVideo(item: VideoItem) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_VIDEO_ID, item.id))
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
        const val KIND_HISTORY = "history"
        const val KIND_FAVORITES = "favorites"
    }
}
