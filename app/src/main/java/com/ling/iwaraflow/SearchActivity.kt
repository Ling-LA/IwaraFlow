package com.ling.iwaraflow

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 搜索结果页。搜索不再直接把首页视频流换成搜索结果（那样只能看到一个视频），
 * 而是用和作者主页一样的浅蓝卡片列表分别展示视频名、标签和作者名的结果。
 */
class SearchActivity : AppCompatActivity() {
    private enum class Tab { VIDEOS, TAGS, AUTHORS }

    private class VideoTab {
        val items = mutableListOf<VideoItem>()
        val playable = mutableListOf<VideoItem>()
        val seenIds = mutableSetOf<String>()
        var page = 0
        var attempt = 0
        var loading = false
        var noMore = false
        var started = false
        var failure: String? = null

        fun reset() {
            items.clear(); playable.clear(); seenIds.clear()
            page = 0; attempt = 0; loading = false; noMore = false; started = false; failure = null
        }
    }

    private class AuthorTab {
        val items = mutableListOf<IwaraAuthor>()
        val seenIds = mutableSetOf<String>()
        var page = 0
        var loading = false
        var noMore = false
        var started = false
        var failure: String? = null

        fun reset() {
            items.clear(); seenIds.clear()
            page = 0; loading = false; noMore = false; started = false; failure = null
        }
    }

    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var prefs: AppPrefs
    private lateinit var gate: PlayableVideoGate
    private lateinit var mediaCache: MediaPreloadCache

    private lateinit var listPage: View
    private lateinit var feedPage: View
    private lateinit var resultList: RecyclerView
    private lateinit var pager: ViewPager2
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private lateinit var tabViews: Map<Tab, TextView>

    private lateinit var videoAdapter: AuthorVideoListAdapter
    private lateinit var tagAdapter: AuthorVideoListAdapter
    private lateinit var authorAdapter: FollowingAuthorAdapter
    private lateinit var feedAdapter: VideoAdapter

    private val videoTab = VideoTab()
    private val tagTab = VideoTab()
    private val authorTab = AuthorTab()

    private var query = ""
    private var querySerial = 0
    private var currentTab = Tab.VIDEOS
    private var feedTab: Tab? = null
    private var inFeed = false
    private var exiting = false
    private val pageSize = 24
    private lateinit var comments: CommentsHost

    private val authorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // 从作者页回来后停留在搜索结果，不自动恢复播放；关注状态同步给结果里的视频。
        AuthorActivity.readFollowResult(result.data)?.let { feedAdapter.applyFollowState(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        api = IwaraApi(this)
        history = HistoryStore(this)
        prefs = AppPrefs(this)
        gate = PlayableVideoGate(api)
        mediaCache = MediaPreloadCache(this)

        listPage = findViewById(R.id.searchListPage)
        feedPage = findViewById(R.id.searchFeedPage)
        resultList = findViewById(R.id.searchResults)
        pager = findViewById(R.id.searchPager)
        statusView = findViewById(R.id.searchStatus)
        input = findViewById(R.id.searchInput)
        tabViews = mapOf(
            Tab.VIDEOS to findViewById<TextView>(R.id.tabVideos),
            Tab.TAGS to findViewById<TextView>(R.id.tabTags),
            Tab.AUTHORS to findViewById<TextView>(R.id.tabAuthors)
        )

        videoAdapter = AuthorVideoListAdapter(videoTab.items) { item -> openWork(Tab.VIDEOS, item) }
        tagAdapter = AuthorVideoListAdapter(tagTab.items) { item -> openWork(Tab.TAGS, item) }
        authorAdapter = FollowingAuthorAdapter(authorTab.items, emptyDescription = "查看作者主页", onClick = ::openAuthor)
        resultList.layoutManager = LinearLayoutManager(this)
        resultList.adapter = videoAdapter
        resultList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= currentCount() - 6) loadNextPage(currentTab)
            }
        })

        feedAdapter = VideoAdapter(
            api = api,
            history = history,
            prefs = prefs,
            mediaCache = mediaCache,
            onDownload = ::download,
            onEnterPip = { Toast.makeText(this, "搜索结果页暂不进入小窗", Toast.LENGTH_SHORT).show() },
            onEnded = ::nextWork,
            onNeedLogin = { Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_SHORT).show() },
            onComments = { comments.open(it) }
        )
        pager.adapter = feedAdapter
        pager.offscreenPageLimit = 1
        val density = resources.displayMetrics.density
        comments = CommentsHost(
            pager = pager,
            panelRoot = findViewById(R.id.commentsPanel),
            scrim = findViewById(R.id.commentsScrim),
            adapter = feedAdapter,
            api = api,
            // 左上角的返回按钮：16dp 边距 + 44dp 高。
            topBarHeight = { (60 * density).toInt() },
            gapPx = (20 * density).toInt(),
            onNeedLogin = { Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_SHORT).show() },
            onOpenAuthor = ::openAuthor
        )
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                comments.close()
                feedAdapter.setActive(position)
                if (position >= feedAdapter.itemCount - 5) feedTab?.let { loadNextPage(it) }
            }
        })

        findViewById<View>(R.id.searchBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.searchFeedBack).setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        findViewById<Button>(R.id.searchSubmit).setOnClickListener { runSearch(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(input.text.toString()); true } else false
        }
        tabViews.forEach { (tab, view) -> view.setOnClickListener { showTab(tab) } }
        styleTabs()

        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        input.setText(initial)
        if (initial.isNotBlank()) runSearch(initial) else statusView.text = "输入关键词后搜索视频名、标签或作者名"
    }

    private fun runSearch(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        if (inFeed) showList()
        query = trimmed
        querySerial++
        videoTab.reset(); tagTab.reset(); authorTab.reset()
        videoAdapter.notifyDataSetChanged()
        tagAdapter.notifyDataSetChanged()
        authorAdapter.notifyDataSetChanged()
        feedTab = null
        feedAdapter.replace(emptyList())
        loadNextPage(currentTab)
        updateStatus()
    }

    private fun showTab(tab: Tab) {
        if (currentTab == tab && resultList.adapter != null) return
        currentTab = tab
        styleTabs()
        resultList.adapter = when (tab) {
            Tab.VIDEOS -> videoAdapter
            Tab.TAGS -> tagAdapter
            Tab.AUTHORS -> authorAdapter
        }
        resultList.scrollToPosition(0)
        loadIfNeeded(tab)
        updateStatus()
    }

    /** 切回已经加载过的标签页时不再重复请求下一页。 */
    private fun loadIfNeeded(tab: Tab) {
        val started = when (tab) {
            Tab.VIDEOS -> videoTab.started
            Tab.TAGS -> tagTab.started
            Tab.AUTHORS -> authorTab.started
        }
        if (!started) loadNextPage(tab)
    }

    private fun styleTabs() {
        tabViews.forEach { (tab, view) ->
            val selected = tab == currentTab
            view.setTextColor(if (selected) 0xFF17324A.toInt() else 0xFF607D93.toInt())
            view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun currentCount(): Int = when (currentTab) {
        Tab.VIDEOS -> videoTab.items.size
        Tab.TAGS -> tagTab.items.size
        Tab.AUTHORS -> authorTab.items.size
    }

    private fun videoState(tab: Tab): VideoTab? = when (tab) {
        Tab.VIDEOS -> videoTab
        Tab.TAGS -> tagTab
        Tab.AUTHORS -> null
    }

    private fun loadNextPage(tab: Tab) {
        if (exiting || query.isBlank()) return
        if (tab == Tab.AUTHORS) loadAuthorPage() else loadVideoPage(tab)
    }

    private fun tagCandidates(): List<String> = TagQuery.candidates(query)

    /** 标签页当前真正在用的标签写法。 */
    private fun activeTags(): String =
        tagCandidates().getOrNull(tagTab.attempt)?.let { TagQuery.display(it) }.orEmpty()

    private fun loadVideoPage(tab: Tab) {
        val state = videoState(tab) ?: return
        if (state.loading || state.noMore) return
        val candidates = tagCandidates()
        // 只输了分隔符时没有标签可搜，直接收尾而不是拿空列表去取下标。
        if (tab == Tab.TAGS && candidates.getOrNull(state.attempt) == null) {
            state.started = true
            state.noMore = true
            updateStatus()
            return
        }
        state.loading = true
        state.started = true
        state.failure = null
        val serial = querySerial
        val handler: (Result<List<VideoItem>>) -> Unit = { result ->
            if (!isStale(serial)) result.onSuccess { raw ->
                if (raw.isEmpty()) {
                    runOnUiThread {
                        if (isStale(serial)) return@runOnUiThread
                        state.loading = false
                        // 标签写法没命中就换下一种，全部试完才算没有结果。
                        if (tab == Tab.TAGS && state.page == 0 && state.attempt + 1 < candidates.size) {
                            state.attempt += 1
                            loadVideoPage(tab)
                        } else {
                            state.noMore = true
                            updateStatus()
                        }
                    }
                } else {
                    gate.inspectAll(raw, prefs.defaultQuality) { checked ->
                        if (isStale(serial)) return@inspectAll
                        runOnUiThread {
                            if (isStale(serial)) return@runOnUiThread
                            val currentId = if (inFeed && feedTab == tab) {
                                feedAdapter.items.getOrNull(pager.currentItem)?.id
                            } else null
                            val fresh = checked.filter { state.seenIds.add(it.id) }
                            fresh.forEach { it.localFavorite = history.isLocalFavorite(it.id) }
                            val start = state.items.size
                            state.items += fresh
                            state.playable += fresh.filter { it.playbackIssue == null }
                            if (currentTab == tab) adapterFor(tab).notifyItemRangeInserted(start, fresh.size)
                            state.page += 1
                            state.noMore = raw.size < pageSize
                            state.loading = false
                            if (inFeed && feedTab == tab) rebuildFeed(currentId)
                            updateStatus()
                        }
                    }
                }
            }.onFailure { e ->
                runOnUiThread {
                    if (isStale(serial)) return@runOnUiThread
                    state.loading = false
                    state.failure = e.message
                    updateStatus()
                }
            }
        }
        updateStatus()
        if (tab == Tab.TAGS) api.getVideosByTag(candidates[state.attempt], state.page, pageSize, handler)
        else api.searchVideos(query, state.page, pageSize, handler)
    }

    private fun loadAuthorPage() {
        val state = authorTab
        if (state.loading || state.noMore) return
        state.loading = true
        state.started = true
        state.failure = null
        val serial = querySerial
        updateStatus()
        api.searchUsers(query, state.page, pageSize) { result ->
            if (isStale(serial)) return@searchUsers
            runOnUiThread {
                if (isStale(serial)) return@runOnUiThread
                state.loading = false
                result.onSuccess { users ->
                    val fresh = users.filter { state.seenIds.add(it.id.ifBlank { it.username }) }
                    val start = state.items.size
                    state.items += fresh
                    if (currentTab == Tab.AUTHORS) authorAdapter.notifyItemRangeInserted(start, fresh.size)
                    state.page += 1
                    state.noMore = users.size < pageSize
                }.onFailure { state.failure = it.message }
                updateStatus()
            }
        }
    }

    private fun adapterFor(tab: Tab): AuthorVideoListAdapter =
        if (tab == Tab.TAGS) tagAdapter else videoAdapter

    private fun isStale(serial: Int): Boolean =
        exiting || isFinishing || isDestroyed || serial != querySerial

    private fun updateStatus() {
        if (query.isBlank()) return
        val label = when (currentTab) {
            Tab.VIDEOS -> "视频名"
            Tab.TAGS -> "标签"
            Tab.AUTHORS -> "作者名"
        }
        val loading = when (currentTab) {
            Tab.VIDEOS -> videoTab.loading
            Tab.TAGS -> tagTab.loading
            Tab.AUTHORS -> authorTab.loading
        }
        val failure = when (currentTab) {
            Tab.VIDEOS -> videoTab.failure
            Tab.TAGS -> tagTab.failure
            Tab.AUTHORS -> authorTab.failure
        }
        val noMore = when (currentTab) {
            Tab.VIDEOS -> videoTab.noMore
            Tab.TAGS -> tagTab.noMore
            Tab.AUTHORS -> authorTab.noMore
        }
        val count = currentCount()
        val subject = if (currentTab == Tab.TAGS) activeTags().ifBlank { query } else "“$query”"
        statusView.text = buildString {
            append("$subject · $label")
            when {
                failure != null && count == 0 -> append("  ·  加载失败：$failure")
                failure != null -> append("  ·  $count 条 · 后续加载失败")
                loading && count == 0 -> append("  ·  正在搜索…")
                count == 0 && noMore -> append(if (currentTab == Tab.TAGS) "  ·  没有找到带这个标签的视频" else "  ·  没有找到结果")
                count == 0 -> append("  ·  正在搜索…")
                else -> {
                    append("  ·  $count 条")
                    if (currentTab != Tab.AUTHORS) {
                        val unavailable = when (currentTab) {
                            Tab.VIDEOS -> videoTab.items.size - videoTab.playable.size
                            else -> tagTab.items.size - tagTab.playable.size
                        }
                        if (unavailable > 0) append("  ·  $unavailable 条当前不可播放")
                    }
                    if (loading) append("  ·  正在加载更多…") else if (!noMore) append("  ·  下滑继续加载")
                }
            }
        }
    }

    private fun openWork(tab: Tab, item: VideoItem) {
        if (exiting) return
        val state = videoState(tab) ?: return
        val index = state.playable.indexOfFirst { it.id == item.id }
        if (index < 0) return
        inFeed = true
        feedTab = tab
        listPage.visibility = View.GONE
        feedPage.visibility = View.VISIBLE
        feedAdapter.replace(state.playable.toList())
        pager.setCurrentItem(index, false)
        feedAdapter.setActive(index)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) feedAdapter.resumeActive()
    }

    private fun rebuildFeed(currentId: String?) {
        val state = videoState(feedTab ?: return) ?: return
        val list = state.playable.toList()
        feedAdapter.replace(list)
        if (list.isEmpty()) return
        val position = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        pager.setCurrentItem(position, false)
        feedAdapter.setActive(position)
    }

    private fun nextWork(position: Int) {
        if (exiting) return
        if (position + 1 < feedAdapter.itemCount) pager.setCurrentItem(position + 1, true)
        else feedTab?.let { loadNextPage(it) }
    }

    private fun showList() {
        if (!inFeed || exiting) return
        comments.close()
        feedAdapter.pauseAll()
        inFeed = false
        feedPage.visibility = View.GONE
        listPage.visibility = View.VISIBLE
    }

    private fun handleBack() {
        if (exiting) return
        if (comments.isOpen) comments.close() else if (inFeed) showList() else finishSafely()
    }

    private fun finishSafely() {
        if (exiting) return
        exiting = true
        feedAdapter.pauseAll()
        finish()
    }

    private fun openAuthor(author: IwaraAuthor) {
        if (exiting) return
        if (author.id.isBlank() && author.username.isBlank()) {
            Toast.makeText(this, "该作者没有资料", Toast.LENGTH_SHORT).show()
            return
        }
        // 全 App 单一播放所有权：进入作者页前先停下搜索结果页的播放器。
        feedAdapter.pauseAll()
        authorLauncher.launch(Intent(this, AuthorActivity::class.java).apply {
            putExtra(AuthorActivity.EXTRA_ID, author.id)
            putExtra(AuthorActivity.EXTRA_NAME, author.name)
            putExtra(AuthorActivity.EXTRA_USERNAME, author.username)
        })
    }

    /** item_video.xml 里作者名的 onClick 绑定。 */
    @Suppress("UNUSED_PARAMETER")
    fun openAuthorProfile(view: View) {
        val item = feedAdapter.items.getOrNull(pager.currentItem) ?: return
        openAuthor(IwaraAuthor(item.authorId, item.author, item.authorUsername))
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        manager.hideSoftInputFromWindow(input.windowToken, 0)
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

    override fun onResume() {
        super.onResume()
        if (inFeed && !exiting) feedAdapter.resumeActive()
    }

    override fun onPause() {
        // 半透明界面盖上来只会走到 onPause，这时释放播放器画面会变黑。
        feedAdapter.suspendPlayback()
        super.onPause()
    }

    override fun onStop() {
        feedAdapter.pauseAll()
        super.onStop()
    }

    override fun onDestroy() {
        if (::comments.isInitialized) comments.release()
        exiting = true
        feedAdapter.releaseAll()
        gate.close()
        mediaCache.close()
        api.close()
        history.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "search_query"
    }
}
