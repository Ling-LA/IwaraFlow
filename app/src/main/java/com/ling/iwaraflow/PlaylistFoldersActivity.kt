package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load

/** Official playlist folders; legacy local favorites migrate automatically and remain accessible. */
class PlaylistFoldersActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var favorites: PlaylistFavorites
    private lateinit var page: PageLayout
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var status: TextView
    private lateinit var cards: FoldersAdapter
    private var folders = emptyList<PlaylistFolder>()
    private val previewRequested = HashSet<String>()
    private val previewLoading = HashSet<String>()
    private val previewState = HashMap<String, Pair<Int, Boolean>>()
    private var previewAccount = ""
    private var closed = false
    private var importing = false
    private var subscription: java.io.Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = IwaraApi(this); history = HistoryStore(this); favorites = PlaylistFavorites(this, api, history)
        page = PageLayout(this, "我的收藏")
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(page.dp(14), 0, page.dp(14), 0) }
        actions.addView(page.action("收藏夹") {}, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(page.action("＋ 新建") { PlaylistPicker.createDialog(this, favorites) { reload() } })
        page.body.addView(actions)
        status = page.text("与 Iwara 官方公开播放列表同步 · 旧版收藏自动导入", 13f).apply { setPadding(page.dp(22), 0, page.dp(22), page.dp(8)) }
        page.body.addView(status)
        val legacy = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(page.dp(14), 0, page.dp(14), 0) }
        legacy.addView(page.action("旧版本地收藏 ›") {
            startActivity(Intent(this, SavedVideosActivity::class.java).putExtra(SavedVideosActivity.EXTRA_KIND, SavedVideosActivity.KIND_FAVORITES))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        legacy.addView(page.action("重试同步") {
            val pendingCreation = favorites.store.migrationCreation(favorites.store.snapshot()) != null
            if (!importing) AlertDialog.Builder(this).setTitle("导入旧版收藏")
                .setMessage("将旧版本地收藏导入单独新建的默认公开收藏夹；已有官网分类与本地记录均保留，已处理或已移除的视频不会重复添加。" +
                    if (pendingCreation) "\n\n上次创建结果未确认。请先到官网核对：若没有新收藏夹，点击导入会重试创建；若有多个同名新夹，请先在官网处理重名。" else "")
                .setNegativeButton("取消", null).setPositiveButton("导入") { _, _ ->
                    importing = true; status.text = "正在导入旧版收藏…"
                    favorites.importLegacy(retryUnconfirmedCreation = pendingCreation) { result ->
                        importing = false
                        result.onSuccess { Toast.makeText(this, "已导入 $it 条", Toast.LENGTH_LONG).show(); reload() }
                            .onFailure { status.text = it.message }
                    }
                }.show()
        })
        page.body.addView(legacy)
        cards = FoldersAdapter()
        val recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@PlaylistFoldersActivity); adapter = cards }
        refresh = SwipeRefreshLayout(this).apply { addView(recycler); setOnRefreshListener { reload() } }
        page.body.addView(refresh, LinearLayout.LayoutParams(-1, 0, 1f))
        updateFolders(favorites.store.folders())
        subscription = favorites.listen {
            if (!closed) runOnUiThread {
                if (!closed) {
                    favorites.store.migrationStatus().takeIf(String::isNotBlank)?.let { status.text = it }
                    updateFolders(favorites.store.folders())
                }
            }
        }
    }
    override fun onResume() { super.onResume(); if (::favorites.isInitialized) { favorites.migrateLegacyIfNeeded(); reload() } }
    private fun updateFolders(rows: List<PlaylistFolder>) {
        val account = favorites.store.snapshot().account
        if (account != previewAccount) {
            previewAccount = account; previewRequested.clear(); previewState.clear()
        }
        val ids = rows.mapTo(HashSet()) { it.id }
        previewRequested.retainAll(ids); previewState.keys.retainAll(ids)
        rows.forEach { folder ->
            val cached = favorites.store.page(folder.id, 0)
            val previous = previewState.put(folder.id, folder.count to (cached != null))
            // Writes invalidate page 0. Counts can also change during a metadata refresh.
            // Invalidate an attempt only for that change, never for another unrelated store
            // notification: a failed preview must not cause an endless retry/rebind loop.
            if (previous != null && (previous.first != folder.count || (previous.second && cached == null)) &&
                (cached == null || cached.folder.count != folder.count)) previewRequested.remove(folder.id)
        }
        folders = rows; cards.notifyDataSetChanged()
    }
    private fun reload() {
        if (closed || importing) return
        previewRequested.clear()
        refresh.isRefreshing = true
        favorites.refreshFolders { result ->
            if (closed) return@refreshFolders
            refresh.isRefreshing = false
            result.onSuccess {
                updateFolders(it)
                status.text = favorites.store.migrationStatus().ifBlank {
                    if (it.isEmpty()) "暂无收藏夹 · 点击新建 · 旧版收藏自动同步" else "${it.size} 个收藏夹 · 已与 Iwara 同步 · 公开"
                }
            }.onFailure {
                updateFolders(favorites.store.folders())
                status.text = if (folders.isNotEmpty()) "显示本地缓存 · 同步失败：${it.message}" else "${it.message} · 下拉重试"
            }
        }
    }
    private fun open(folder: PlaylistFolder) {
        startActivity(Intent(this, SavedVideosActivity::class.java)
            .putExtra(SavedVideosActivity.EXTRA_KIND, "playlist")
            .putExtra("playlist_id", folder.id).putExtra("playlist_title", folder.title))
    }
    override fun onDestroy() {
        closed = true
        subscription?.close()
        if (::favorites.isInitialized) favorites.close()
        if (::api.isInitialized) api.close()
        if (::history.isInitialized) history.close()
        super.onDestroy()
    }
    private inner class FoldersAdapter : RecyclerView.Adapter<FoldersAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(page.card())
        override fun getItemCount() = folders.size
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(folders[position])
        inner class Holder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
            fun bind(folder: PlaylistFolder) {
                card.removeAllViews()
                val title = LinearLayout(this@PlaylistFoldersActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                title.addView(page.text(folder.title, 19f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, page.dp(42), 1f))
                title.addView(page.text("${folder.count} 个视频 ›", 13f))
                card.addView(title)
                val previews = if (folder.count > 0) favorites.store.previews(folder.id) else emptyList()
                val shouldLoad = folder.count > 0 && folder.id !in previewRequested && folder.id !in previewLoading
                val row = LinearLayout(this@PlaylistFoldersActivity)
                val cells = if (previews.isNotEmpty()) previews.map { it.thumbnailUrl to it.title } else
                    if (folder.count > 0 && folder.thumbnail.isNotBlank()) listOf(folder.thumbnail to "") else emptyList()
                cells.take(3).forEach { (url, titleText) ->
                    val cell = LinearLayout(this@PlaylistFoldersActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, page.dp(6), 0) }
                    cell.addView(ImageView(this@PlaylistFoldersActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = android.graphics.drawable.GradientDrawable().apply { setColor(UiPalette.resolve(context, 0xFFE4EFF7.toInt())); cornerRadius = page.dp(10).toFloat() }
                        clipToOutline = true; if (url.isNotBlank()) load(url)
                    }, LinearLayout.LayoutParams(-1, page.dp(74)))
                    if (titleText.isNotBlank()) cell.addView(page.text(titleText, 13f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = page.dp(40) })
                    row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (cells.isEmpty()) row.addView(page.text(when {
                    folder.count == 0 -> "暂无视频"
                    shouldLoad || folder.id in previewLoading -> "正在加载预览…"
                    else -> "暂无预览 · 下拉重试"
                }, 14f).apply { minHeight = page.dp(48) })
                card.addView(row); card.setOnClickListener { open(folder) }
                // Refresh only visible cards, once per reload/content invalidation. Existing
                // cached previews remain on screen while their official contents are refreshed.
                if (shouldLoad) {
                    previewRequested.add(folder.id); previewLoading.add(folder.id)
                    favorites.getFolderPage(folder.id, 0) {
                        previewLoading.remove(folder.id)
                        if (!closed) {
                            val index = folders.indexOfFirst { value -> value.id == folder.id }
                            if (index >= 0) notifyItemChanged(index)
                        }
                    }
                }
            }
        }
    }
}
