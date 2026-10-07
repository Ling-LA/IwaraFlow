package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

class MyActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var status: TextView
    private lateinit var listAdapter: AuthorVideoListAdapter
    private val videos = mutableListOf<VideoItem>()
    private var me: IwaraAuthor? = null
    private var page = 0
    private var loading = false
    private var more = true
    private var refreshAfterUpload = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        api = IwaraApi(this)
        val layout = PageLayout(this, "我的")
        val card = layout.card()
        val avatar = android.widget.ImageView(this).apply { contentDescription = "我的头像" }
        card.addView(avatar, LinearLayout.LayoutParams(layout.dp(64), layout.dp(64)))
        status = layout.text("正在读取个人主页…", 18f)
        card.addView(status)
        card.addView(layout.action("我的关注  ›") { relation(false) })
        card.addView(layout.action("我的粉丝  ›") { relation(true) })
        card.addView(layout.action("我的收藏  ›") { PageNavigation.saved(this, SavedVideosActivity.KIND_FAVORITES) })
        card.addView(layout.action("上传视频  ↑") { refreshAfterUpload = true; startActivity(Intent(this, UploadActivity::class.java)) })
        layout.body.addView(card)
        layout.body.addView(layout.text("  我上传的视频", 18f))
        listAdapter = AuthorVideoListAdapter(videos) { item ->
            val user = me ?: return@AuthorVideoListAdapter
            startActivity(Intent(this, AuthorActivity::class.java)
                .putExtra(AuthorActivity.EXTRA_ID, user.id).putExtra(AuthorActivity.EXTRA_USERNAME, user.username)
                .putExtra(AuthorActivity.EXTRA_NAME, user.name).putExtra("open_work_id", item.id))
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MyActivity); adapter = listAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if ((view.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() >= videos.size - 5) loadPage()
                }
            })
        }
        layout.body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!api.isLoggedIn()) {
            status.text = "请先在主页登录 Iwara"
            status.setOnClickListener { PageNavigation.home(this) }
            return
        }
        api.getCurrentUser { result -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            result.onSuccess {
                me = it
                if (it.avatarUrl.isNotBlank()) avatar.load(it.avatarUrl) { transformations(CircleCropTransformation()) }
                status.text = "${it.name}\n@${it.username}" + if (it.followers >= 0) " · ${it.followers} 位粉丝" else ""
                if (it.description.isNotBlank()) status.append("\n${it.description.take(160)}")
                loadPage()
            }.onFailure { status.text = "个人主页读取失败，点击重试"; status.setOnClickListener { recreate() } }
        } }
    }
    private fun relation(followers: Boolean) {
        val user = me ?: return
        startActivity(Intent(this, FollowingActivity::class.java).putExtra("user_id", user.id).putExtra("followers", followers))
    }
    override fun onResume() {
        super.onResume()
        if (refreshAfterUpload && me != null && !loading) {
            refreshAfterUpload = false
            val count = videos.size; videos.clear(); listAdapter.notifyItemRangeRemoved(0, count)
            page = 0; more = true; loadPage()
        }
    }
    private fun loadPage() {
        val user = me ?: return
        if (loading || !more) return
        loading = true
        api.getAuthorVideoPage(user.id, page, 36) { result -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            loading = false
            result.onSuccess { batch ->
                val fresh = batch.videos.distinctBy { it.id }.filter { incoming -> videos.none { it.id == incoming.id } }
                val start = videos.size; videos += fresh; listAdapter.notifyItemRangeInserted(start, fresh.size)
                page++; more = fresh.isNotEmpty() && (batch.total < 0 || videos.size < batch.total)
                if (videos.isEmpty()) status.append("\n暂无上传视频")
            }.onFailure { status.text = "作品加载失败，点击重试"; status.setOnClickListener { loadPage() } }
        } }
    }
    override fun onDestroy() { api.close(); super.onDestroy() }
}
