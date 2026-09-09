package com.ling.iwaraflow

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class AuthorActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var prefs: AppPrefs
    private lateinit var gate: PlayableVideoGate
    private lateinit var mediaCache: MediaPreloadCache
    private lateinit var listPage: View
    private lateinit var feedPage: View
    private lateinit var listView: RecyclerView
    private lateinit var pager: ViewPager2
    private lateinit var listAdapter: AuthorVideoListAdapter
    private lateinit var feedAdapter: VideoAdapter
    private lateinit var nameView: TextView
    private lateinit var usernameView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var statusView: TextView
    private lateinit var followButton: Button
    private lateinit var friendButton: Button

    private val works = mutableListOf<VideoItem>()
    private val playableWorks = mutableListOf<VideoItem>()
    private var author: IwaraAuthor? = null
    private var page = 0
    private var loadingPage = false
    private var noMore = false
    private var inFeed = false
    private val pageSize = 36

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_author)

        api = IwaraApi(this)
        history = HistoryStore(this)
        prefs = AppPrefs(this)
        gate = PlayableVideoGate(api)
        mediaCache = MediaPreloadCache(this)

        listPage = findViewById(R.id.authorListPage)
        feedPage = findViewById(R.id.authorFeedPage)
        listView = findViewById(R.id.authorVideos)
        pager = findViewById(R.id.authorPager)
        nameView = findViewById(R.id.authorName)
        usernameView = findViewById(R.id.authorUsername)
        descriptionView = findViewById(R.id.authorDescription)
        statusView = findViewById(R.id.authorStatus)
        followButton = findViewById(R.id.followButton)
        friendButton = findViewById(R.id.friendButton)

        listAdapter = AuthorVideoListAdapter(works, ::openWork)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = listAdapter
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (!noMore && !loadingPage && lm.findLastVisibleItemPosition() >= works.size - 6) loadNextPage()
            }
        })

        feedAdapter = VideoAdapter(
            api = api,
            history = history,
            prefs = prefs,
            mediaCache = mediaCache,
            onDownload = ::download,
            onEnterPip = { Toast.makeText(this, "作者作品页暂不进入小窗", Toast.LENGTH_SHORT).show() },
            onEnded = ::nextWork,
            onNeedLogin = { Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_SHORT).show() }
        )
        pager.adapter = feedAdapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                feedAdapter.setActive(position)
                if (!noMore && !loadingPage && position >= playableWorks.size - 5) loadNextPage()
            }
        })

        findViewById<View>(R.id.authorBack).setOnClickListener { finishToMain() }
        findViewById<View>(R.id.feedBack).setOnClickListener { showList() }
        followButton.setOnClickListener { toggleFollow() }
        friendButton.setOnClickListener { toggleFriend() }

        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val display = intent.getStringExtra(EXTRA_NAME).orEmpty()
        nameView.text = display.ifBlank { username }
        usernameView.text = if (username.isBlank()) "" else "@$username"

        if (username.isNotBlank()) loadProfile(username)
        else if (id.isNotBlank()) {
            author = IwaraAuthor(id, display, "")
            loadNextPage()
        } else {
            Toast.makeText(this, "缺少作者信息", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadProfile(username: String) {
        statusView.text = "正在读取作者资料…"
        api.getAuthorProfile(username) { result ->
            runOnUiThread {
                result.onSuccess { loaded ->
                    author = loaded
                    nameView.text = loaded.name
                    usernameView.text = "@${loaded.username}"
                    descriptionView.text = loaded.description
                    refreshRelationUi()
                    if (api.isLoggedIn()) {
                        api.getFriendStatus(loaded.id) { statusResult ->
                            runOnUiThread {
                                statusResult.onSuccess { loaded.friendStatus = it; loaded.friend = it == "friends" }
                                refreshRelationUi()
                            }
                        }
                    }
                    loadNextPage()
                }.onFailure {
                    statusView.text = "作者资料加载失败：${it.message}"
                }
            }
        }
    }

    private fun loadNextPage() {
        val a = author ?: return
        if (loadingPage || noMore || a.id.isBlank()) return
        loadingPage = true
        statusView.text = if (works.isEmpty()) "正在检查作者作品是否可播放…" else "正在加载更多作品…"
        api.getAuthorVideos(a.id, page, pageSize) { result ->
            result.onSuccess { raw ->
                if (raw.isEmpty()) {
                    runOnUiThread { loadingPage = false; noMore = true; updateStatus() }
                    return@onSuccess
                }
                gate.inspectAll(raw, prefs.defaultQuality) { checked ->
                    runOnUiThread {
                        val start = works.size
                        works += checked
                        playableWorks += checked.filter { it.playbackIssue == null }
                        listAdapter.notifyItemRangeInserted(start, checked.size)
                        if (inFeed) feedAdapter.replace(playableWorks.toList()).also {
                            val keep = pager.currentItem.coerceAtMost((playableWorks.size - 1).coerceAtLeast(0))
                            pager.setCurrentItem(keep, false)
                            feedAdapter.setActive(keep)
                        }
                        page++
                        noMore = raw.size < pageSize
                        loadingPage = false
                        updateStatus()
                    }
                }
            }.onFailure {
                runOnUiThread {
                    loadingPage = false
                    statusView.text = "作品加载失败：${it.message}"
                }
            }
        }
    }

    private fun updateStatus() {
        val unavailable = works.count { it.playbackIssue != null }
        statusView.text = buildString {
            append("作品 ${works.size} 条")
            if (unavailable > 0) append("  ·  $unavailable 条当前不可播放")
            if (!noMore) append("  ·  下滑继续加载")
        }
    }

    private fun openWork(item: VideoItem) {
        val index = playableWorks.indexOfFirst { it.id == item.id }
        if (index < 0) return
        inFeed = true
        listPage.visibility = View.GONE
        feedPage.visibility = View.VISIBLE
        feedAdapter.replace(playableWorks.toList())
        pager.setCurrentItem(index, false)
        feedAdapter.setActive(index)
    }

    private fun showList() {
        if (!inFeed) return
        feedAdapter.pauseAll()
        inFeed = false
        feedPage.visibility = View.GONE
        listPage.visibility = View.VISIBLE
    }

    private fun nextWork(position: Int) {
        if (position + 1 < feedAdapter.itemCount) pager.setCurrentItem(position + 1, true)
        else if (!noMore) loadNextPage()
        else if (feedAdapter.itemCount > 0) pager.setCurrentItem(0, true)
    }

    private fun toggleFollow() {
        val a = author ?: return
        if (!api.isLoggedIn()) { Toast.makeText(this, "请先登录 Iwara", Toast.LENGTH_SHORT).show(); return }
        followButton.isEnabled = false
        val desired = !a.following
        api.followUser(a.id, desired) { result ->
            runOnUiThread {
                followButton.isEnabled = true
                result.onSuccess { a.following = desired; refreshRelationUi() }
                    .onFailure { Toast.makeText(this, it.message ?: "关注操作失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun toggleFriend() {
        val a = author ?: return
        if (!api.isLoggedIn()) { Toast.makeText(this, "请先登录 Iwara", Toast.LENGTH_SHORT).show(); return }
        friendButton.isEnabled = false
        val remove = a.friendStatus == "pending" || a.friendStatus == "friends"
        api.setFriend(a.id, !remove) { result ->
            runOnUiThread {
                friendButton.isEnabled = true
                result.onSuccess {
                    api.getFriendStatus(a.id) { refreshed ->
                        runOnUiThread {
                            a.friendStatus = refreshed.getOrDefault(if (remove) "none" else "pending")
                            a.friend = a.friendStatus == "friends"
                            refreshRelationUi()
                            if (a.friend) refreshWorksAfterRelationshipChange()
                        }
                    }
                }.onFailure { Toast.makeText(this, it.message ?: "好友操作失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun refreshWorksAfterRelationshipChange() {
        works.clear(); playableWorks.clear(); listAdapter.notifyDataSetChanged()
        feedAdapter.replace(emptyList())
        page = 0; noMore = false; loadingPage = false
        loadNextPage()
    }

    private fun refreshRelationUi() {
        val a = author ?: return
        followButton.text = if (a.following) "✓ 已关注" else "+ 关注"
        friendButton.text = when (a.friendStatus) {
            "friends" -> "✓ 已是好友"
            "pending" -> "⏳ 取消好友申请"
            else -> "+ 添加好友"
        }
    }

    private fun download(item: VideoItem, source: VideoSource) {
        try {
            val fileName = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "_${source.name}.mp4"
            val request = DownloadManager.Request(Uri.parse(source.url))
                .setTitle(item.title).setMimeType("video/mp4")
                .addRequestHeader("Referer", "https://www.iwara.tv/")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "IwaraFlow/$fileName")
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "已加入下载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun openAuthorProfile(view: View) { if (inFeed) showList() }

    private fun finishToMain() {
        startActivity(Intent(this, MainActivityV3::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("return_recommend", true)
        })
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inFeed) showList() else finishToMain()
    }

    override fun onStart() { super.onStart(); if (inFeed) feedAdapter.resumeActive() }
    override fun onStop() { if (inFeed) feedAdapter.pauseAll(); super.onStop() }
    override fun onDestroy() {
        feedAdapter.releaseAll(); gate.close(); mediaCache.close(); api.close(); history.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "author_id"
        const val EXTRA_NAME = "author_name"
        const val EXTRA_USERNAME = "author_username"
    }
}
