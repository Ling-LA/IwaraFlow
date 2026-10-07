package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var generation = 0
    private var refreshAfterUpload = false
    private var descriptionExpanded = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_my)
        api = IwaraApi(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        status = findViewById(R.id.authorStatus)
        findViewById<View>(R.id.authorBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.authorName).text = "我的"
        findViewById<TextView>(R.id.authorShare).apply {
            text = "上传"
            contentDescription = "上传视频"
            setTextColor(android.graphics.Color.WHITE)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            setBackgroundResource(R.drawable.bg_profile_primary)
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = layoutParams.apply { height = dp(48) }
            gravity = Gravity.CENTER
            setOnClickListener {
                refreshAfterUpload = true
                startActivity(Intent(this@MyActivity, UploadActivity::class.java))
            }
        }
        findViewById<TextView>(R.id.followButton).apply {
            text = "我的关注"; isEnabled = false
            setOnClickListener { relation(false) }
        }
        findViewById<TextView>(R.id.friendButton).apply {
            text = "我的粉丝"; isEnabled = false
            setOnClickListener { relation(true) }
        }
        findViewById<View>(R.id.profileFavoritesButton).apply {
            visibility = View.VISIBLE
            setOnClickListener { PageNavigation.saved(this@MyActivity, SavedVideosActivity.KIND_FAVORITES) }
        }
        findViewById<TextView>(R.id.authorDescription).apply {
            visibility = View.GONE
            setOnClickListener {
                descriptionExpanded = !descriptionExpanded
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3
            }
        }
        listAdapter = AuthorVideoListAdapter(videos) { item ->
            val user = me ?: return@AuthorVideoListAdapter
            startActivity(Intent(this, AuthorActivity::class.java)
                .putExtra(AuthorActivity.EXTRA_ID, user.id).putExtra(AuthorActivity.EXTRA_USERNAME, user.username)
                .putExtra(AuthorActivity.EXTRA_NAME, user.name).putExtra("open_work_id", item.id))
        }
        findViewById<RecyclerView>(R.id.authorVideos).apply {
            layoutManager = LinearLayoutManager(this@MyActivity)
            adapter = listAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 && descriptionExpanded) {
                        descriptionExpanded = false
                        findViewById<TextView>(R.id.authorDescription).maxLines = 3
                    }
                    if ((view.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() >= videos.size - 5) loadPage()
                }
            })
        }
        if (!api.isLoggedIn()) {
            status.text = "请先在主页登录 Iwara"
            status.setOnClickListener { PageNavigation.home(this) }
        } else loadProfile()
    }

    private fun loadProfile() {
        status.text = "正在读取个人主页…"
        status.setOnClickListener(null)
        api.getCurrentUser { result -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            result.onSuccess { bindProfile(it); loadPage() }
                .onFailure { status.text = "个人主页读取失败，点击重试"; status.setOnClickListener { loadProfile() } }
        } }
    }

    internal fun bindProfile(user: IwaraAuthor) {
        me = user
        findViewById<TextView>(R.id.authorName).text = user.name
        findViewById<TextView>(R.id.authorUsername).text = "@${user.username}"
        findViewById<TextView>(R.id.authorDescription).apply {
            text = user.description.takeUnless { it.trim().equals("null", true) }.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<ImageView>(R.id.authorAvatar).load(user.avatarUrl.ifBlank { null }) {
            placeholder(R.drawable.bg_avatar_placeholder)
            error(R.drawable.bg_avatar_placeholder)
            transformations(CircleCropTransformation())
        }
        findViewById<View>(R.id.followButton).isEnabled = true
        findViewById<View>(R.id.friendButton).isEnabled = true
    }

    private fun relation(followers: Boolean) {
        val user = me ?: return
        startActivity(Intent(this, FollowingActivity::class.java).putExtra("user_id", user.id).putExtra("followers", followers))
    }

    override fun onResume() {
        super.onResume()
        if (refreshAfterUpload && me != null) {
            refreshAfterUpload = false
            generation++
            val count = videos.size
            videos.clear(); listAdapter.notifyItemRangeRemoved(0, count)
            page = 0; more = true; loading = false
            loadPage()
        }
    }

    private fun loadPage() {
        val user = me ?: return
        if (loading || !more) return
        loading = true
        status.text = if (videos.isEmpty()) "正在读取我的作品…" else "作品 ${videos.size} 条 · 正在加载…"
        status.setOnClickListener(null)
        val requestGeneration = generation
        api.getAuthorVideoPage(user.id, page, 36) { result -> runOnUiThread {
            if (isFinishing || isDestroyed || generation != requestGeneration) return@runOnUiThread
            loading = false
            result.onSuccess { batch ->
                val fresh = batch.videos.distinctBy { it.id }.filter { incoming -> videos.none { it.id == incoming.id } }
                val start = videos.size
                videos += fresh; listAdapter.notifyItemRangeInserted(start, fresh.size)
                page++; more = fresh.isNotEmpty() && (batch.total < 0 || videos.size < batch.total)
                status.text = worksStatus(videos.size, more)
            }.onFailure { status.text = "作品加载失败，点击重试"; status.setOnClickListener { loadPage() } }
        } }
    }

    override fun onDestroy() { api.close(); super.onDestroy() }

    companion object {
        internal fun worksStatus(count: Int, more: Boolean) = if (count == 0) "暂无上传视频"
            else "作品 $count 条" + if (more) " · 下滑继续加载" else ""
    }
}
