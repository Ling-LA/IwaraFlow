package com.ling.iwaraflow

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.widget.ViewPager2

class MainActivityV3 : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var prefs: AppPrefs
    private lateinit var recommender: RecommendationEngine
    private lateinit var playableGate: PlayableVideoGate
    private lateinit var mediaCache: MediaPreloadCache
    private lateinit var updates: UpdateManager
    private lateinit var likedSync: LikedVideoSync
    private lateinit var pager: ViewPager2
    private lateinit var loading: ProgressBar
    private lateinit var error: TextView
    private lateinit var adapter: VideoAdapter
    private lateinit var topBar: View

    private var mode = "recommend"
    private var currentPage = 0
    private var loadingMore = false
    private var pendingAdvanceAfterLoad = false
    private var pagingEnabled = true
    private var requestSerial = 0
    private var awaitingFullFeed = false
    private var launchUpdateChecked = false
    private var openingInternalPage = false
    private var pendingVideoId: String? = null
    private val pageSize = 28
    private val recommendFirstPage = 10
    /** 续页一次验证多少条候选：翻页频率和单页请求量的折中。 */
    private val recommendPageSize = 20
    private val maxRecommendRefills = 3

    private data class FeedSession(
        val items: List<VideoItem>,
        val currentIndex: Int,
        val currentPage: Int,
        val pagingEnabled: Boolean
    )

    private val homeFeedSessions = mutableMapOf<String, FeedSession>()
    private val homeModes = setOf("recommend", "date", "trending", "popularity")

    /** 预检给出的确定性不可播放原因；这些视频直接播放同样放不了。 */
    private val permanentIssues = setOf("仅限好友观看", "视频仍在处理中", "视频已删除或不存在", "没有观看权限")

    /** 推荐算法一次产出的剩余候选，推荐流翻页从这里取，取完再重新生成。 */
    private val recommendQueue = ArrayList<VideoItem>()
    private var recommendRefills = 0

    private val authorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        resumeAfterInternalPage()
        // 在作者页关注/取关后，回到视频流的关注按钮要跟着变。
        AuthorActivity.readFollowResult(result.data)?.let { adapter.applyFollowState(it) }
    }

    private val followingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumeAfterInternalPage()
    }

    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumeAfterInternalPage()
    }

    private val savedVideosLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        openingInternalPage = false
        val videoId = result.data?.getStringExtra(SavedVideosActivity.EXTRA_VIDEO_ID).orEmpty()
        pendingVideoId = videoId.takeIf { it.isNotBlank() }
    }

    private fun resumeAfterInternalPage() {
        openingInternalPage = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openingInternalPage = savedInstanceState?.getBoolean("opening_internal_page") ?: false
        setContentView(R.layout.activity_main)
        hideStatusBar()

        api = IwaraApi(this)
        history = HistoryStore(this)
        history.warmUp()
        prefs = AppPrefs(this)
        recommender = RecommendationEngine(api, history)
        playableGate = PlayableVideoGate(api)
        mediaCache = MediaPreloadCache(this)
        updates = UpdateManager(this)
        likedSync = LikedVideoSync(api, history, prefs)

        pager = findViewById(R.id.pager)
        loading = findViewById(R.id.loading)
        error = findViewById(R.id.error)
        topBar = findViewById(R.id.topBar)

        adapter = VideoAdapter(
            api = api,
            history = history,
            prefs = prefs,
            mediaCache = mediaCache,
            onDownload = ::enqueueDownload,
            onEnterPip = ::enterPip,
            onEnded = ::onVideoEnded,
            onNeedLogin = ::showLoginDialog
        )
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.setActive(position)
                if (pagingEnabled && position >= adapter.itemCount - 5) loadMore()
            }
        })

        setupTopBar()
        if (intent.getBooleanExtra("return_recommend", false)) returnToRecommend() else loadFeed(reset = true)
        // 官方点赞的完整同步放在首屏之后，避免和冷启动抢网络。
        window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) likedSync.syncIfStale() }, 2500L)
        // 兜底：即使首屏加载失败，也仍然会检查更新。正常情况下首屏出来后会更早触发。
        window.decorView.postDelayed({ runLaunchUpdateCheck() }, 9000L)
    }

    /** 启动检查更新不和首屏视频抢冷启动的网络。 */
    private fun runLaunchUpdateCheck() {
        if (launchUpdateChecked || isFinishing || isDestroyed) return
        launchUpdateChecked = true
        updates.checkOnLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("return_recommend", false)) returnToRecommend()
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun setupTopBar() {
        val recommend = findViewById<TextView>(R.id.tabRecommend)
        val trending = findViewById<TextView>(R.id.tabTrending)
        val popular = findViewById<TextView>(R.id.tabPopular)
        val latest = findViewById<TextView>(R.id.tabLatest)
        val search = findViewById<TextView>(R.id.btnSearch)
        val menu = findViewById<TextView>(R.id.btnMenu)
        val tabs = listOf(recommend, trending, popular, latest)

        fun styleSelected(selected: TextView) {
            tabs.forEach { tab ->
                tab.setTextColor(if (tab === selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
                tab.setTypeface(null, if (tab === selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }

        fun select(newMode: String, selected: TextView) {
            val sameHomeMode = mode == newMode && newMode in homeModes

            if (sameHomeMode && newMode == "recommend") {
                saveCurrentHomeSession()
                homeFeedSessions.remove("recommend")
                invalidateRequests()
                mode = "recommend"
                pagingEnabled = true
                currentPage = 0
                styleSelected(selected)
                loadFeed(reset = true)
                return
            }

            if (sameHomeMode) return

            saveCurrentHomeSession()
            invalidateRequests()
            mode = newMode
            pagingEnabled = true
            styleSelected(selected)

            val saved = homeFeedSessions[newMode]
            if (saved != null && saved.items.isNotEmpty()) {
                restoreHomeSession(saved)
            } else {
                currentPage = 0
                loadFeed(reset = true)
            }
        }

        recommend.setOnClickListener { select("recommend", recommend) }
        trending.setOnClickListener { select("trending", trending) }
        popular.setOnClickListener { select("popularity", popular) }
        latest.setOnClickListener { select("date", latest) }
        search.setOnClickListener { showSearchDialog() }
        menu.setOnClickListener { showMainMenu() }
    }

    private fun saveCurrentHomeSession() {
        if (mode !in homeModes || adapter.items.isEmpty()) return
        adapter.savePlaybackPosition()
        val index = pager.currentItem.coerceIn(0, adapter.items.lastIndex)
        homeFeedSessions[mode] = FeedSession(
            items = adapter.items.toList(),
            currentIndex = index,
            currentPage = currentPage,
            pagingEnabled = pagingEnabled
        )
    }

    private fun restoreHomeSession(session: FeedSession) {
        loading.visibility = View.GONE
        error.visibility = View.GONE
        loadingMore = false
        pendingAdvanceAfterLoad = false
        currentPage = session.currentPage
        pagingEnabled = session.pagingEnabled
        adapter.replace(session.items)
        if (adapter.itemCount == 0) return
        val index = session.currentIndex.coerceIn(0, adapter.itemCount - 1)
        pager.setCurrentItem(index, false)
        adapter.setActive(index)
    }

    private fun returnToRecommend() {
        mode = "recommend"
        pagingEnabled = true
        currentPage = 0
        val selected = findViewById<TextView>(R.id.tabRecommend)
        listOf(R.id.tabRecommend, R.id.tabTrending, R.id.tabPopular, R.id.tabLatest).forEach { id ->
            val tab = findViewById<TextView>(id)
            tab.setTextColor(if (tab === selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
            tab.setTypeface(null, if (tab === selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        loadFeed(reset = true)
    }

    private fun loadFeed(reset: Boolean) {
        if (reset) {
            currentPage = 0
            recommendRefills = 0
            invalidateRequests()
            loading.visibility = View.VISIBLE
            error.visibility = View.GONE
        }
        val requestId = requestSerial
        val emptyMessage = if (mode == "recommend") {
            "没有找到可播放的推荐视频。可以稍后刷新，或关闭“刷新推荐时排除已看视频”。"
        } else "这一页没有可播放视频"
        val head: ((List<VideoItem>) -> Unit)? = if (reset) {
            { batch: List<VideoItem> -> runOnUiThread { showFeedHead(requestId, batch) } }
        } else null

        if (mode == "recommend" && currentPage == 0) {
            recommender.load(prefs.skipSeen) { result ->
                result.onSuccess { raw ->
                    note("推荐候选 ${raw.size} 条（抽页 ${recommender.sampledPages.joinToString(",")}）")
                    val firstPage = minOf(recommendFirstPage, NetworkProfile.coldStartCandidates(this))
                    val first = raw.take(firstPage)
                    val rest = raw.drop(firstPage)
                    playableGate.filterPlayable(first, prefs.defaultQuality, maxItems = first.size, onFirstBatch = head) { playable ->
                        runOnUiThread {
                            if (requestId != requestSerial) return@runOnUiThread
                            noteGateResult("推荐首屏", first, playable)
                            recommendQueue.clear()
                            recommendQueue.addAll(rest)
                            showFeedReset(requestId, playable, emptyMessage, first)
                        }
                    }
                }.onFailure {
                    runOnUiThread {
                        if (requestId != requestSerial) return@runOnUiThread
                        awaitingFullFeed = false
                        loading.visibility = View.GONE
                        note("推荐加载失败：${it.message?.take(80)}")
                        showError("推荐加载失败\n${it.message}")
                    }
                }
            }
            return
        }

        // 首屏只验证很小的窗口保证秒开；续页整页验证，避免每页只剩个位数视频。
        // 弱网上每个候选都是一次额外往返，窗口再收一点，先把首屏放出来。
        val candidateWindow = if (reset) NetworkProfile.coldStartCandidates(this) else recommendPageSize
        val callback: (Result<List<VideoItem>>) -> Unit = { result ->
            result.onSuccess { raw ->
                playableGate.filterPlayable(raw, prefs.defaultQuality, maxItems = pageSize, maxCandidates = candidateWindow, onFirstBatch = head) { playable ->
                    runOnUiThread {
                        if (requestId != requestSerial) return@runOnUiThread
                        loadingMore = false
                        noteGateResult("$mode 第 $currentPage 页", raw.take(candidateWindow), playable)
                        if (reset) showFeedReset(requestId, playable, emptyMessage, raw.take(candidateWindow))
                        else {
                            loading.visibility = View.GONE
                            appendFeed(decorate(playable))
                            if (adapter.itemCount == 0) showError(emptyMessage)
                        }
                    }
                }
            }.onFailure {
                runOnUiThread {
                    if (requestId != requestSerial) return@runOnUiThread
                    awaitingFullFeed = false
                    loading.visibility = View.GONE
                    loadingMore = false
                    note("$mode 第 $currentPage 页加载失败：${it.message?.take(80)}")
                    if (reset) showError("Iwara 数据加载失败\n${it.message}\n\n请确认网络可以访问 iwara.tv")
                }
            }
        }

        api.getVideos(if (mode == "recommend") "trending" else mode, currentPage, pageSize, callback)
    }

    /**
     * 验证最快的前几条先上屏并开始播放，剩下的候选继续在后台验证。
     * 这一批永远是完整结果的前缀，所以补齐时顺序不会变。
     */
    private fun showFeedHead(requestId: Int, batch: List<VideoItem>) {
        if (requestId != requestSerial || batch.isEmpty()) return
        awaitingFullFeed = true
        loading.visibility = View.GONE
        error.visibility = View.GONE
        adapter.replace(decorate(batch))
        pager.setCurrentItem(0, false)
        adapter.setActive(0)
        window.decorView.postDelayed({ runLaunchUpdateCheck() }, 1500L)
    }

    private fun showFeedReset(
        requestId: Int,
        playable: List<VideoItem>,
        emptyMessage: String,
        candidates: List<VideoItem> = emptyList()
    ) {
        if (requestId != requestSerial) return
        val filling = awaitingFullFeed
        awaitingFullFeed = false
        loading.visibility = View.GONE
        val list = decorate(playable)
        if (filling && adapter.itemCount > 0) {
            appendFeed(list)
            return
        }
        if (list.isEmpty()) {
            val unreachable = unreachableCandidates(candidates)
            // 整批候选都连不上视频源（而不是个别视频失效）说明这台设备根本做不了预检，
            // 再换一批候选也是同样结果，直接跳过重试。
            val cannotProbe = candidates.isNotEmpty() && unreachable.size == candidates.size
            // 首屏候选全都不可播放时，继续用剩下的推荐候选找，而不是直接报空。
            if (!cannotProbe && mode == "recommend" && recommendQueue.isNotEmpty() && !loadingMore) {
                loadingMore = true
                loading.visibility = View.VISIBLE
                loadMoreRecommend(requestId)
                return
            }
            if (unreachable.isNotEmpty()) {
                note("预检全部失败，回退显示 ${unreachable.size} 条未验证视频")
                error.visibility = View.GONE
                adapter.replace(decorate(unreachable))
                pager.setCurrentItem(0, false)
                adapter.setActive(0)
                Toast.makeText(this, "无法预检视频源，已直接显示未验证的视频", Toast.LENGTH_LONG).show()
                return
            }
            showError(emptyMessage)
        } else {
            error.visibility = View.GONE
            adapter.replace(list)
            pager.setCurrentItem(0, false)
            adapter.setActive(0)
        }
    }

    private fun appendFeed(list: List<VideoItem>) {
        val before = adapter.itemCount
        adapter.append(list)
        if (adapter.itemCount == 0) return
        if (before == 0) {
            pendingAdvanceAfterLoad = false
            error.visibility = View.GONE
            pager.setCurrentItem(0, false)
            adapter.setActive(0)
            return
        }
        if (pendingAdvanceAfterLoad && adapter.itemCount > before) {
            pendingAdvanceAfterLoad = false
            pager.setCurrentItem(before, true)
        }
    }

    /**
     * 作废还在飞行中的请求。翻页状态一并清零：被作废的续页回调不会再回来
     * 复位 loadingMore，否则新列表就再也翻不动了。
     */
    private fun invalidateRequests() {
        requestSerial++
        awaitingFullFeed = false
        loadingMore = false
    }

    private fun loadMore() {
        if (loadingMore || awaitingFullFeed || adapter.itemCount == 0) return
        loadingMore = true
        if (mode == "recommend") loadMoreRecommend(requestSerial)
        else loadMoreOfficialPage()
    }

    /**
     * 推荐流翻页继续走推荐算法：先用本次生成的剩余候选，用完再重新生成一批
     * （重新生成时同样会排除已看视频），实在没有新内容才回退到官方榜单分页。
     */
    private fun loadMoreRecommend(requestId: Int) {
        if (recommendQueue.isEmpty()) {
            refillRecommendQueue(requestId)
            return
        }
        val chunk = ArrayList<VideoItem>(recommendPageSize)
        while (recommendQueue.isNotEmpty() && chunk.size < recommendPageSize) chunk += recommendQueue.removeAt(0)
        playableGate.filterPlayable(chunk, prefs.defaultQuality, maxItems = chunk.size, maxCandidates = chunk.size) { playable ->
            runOnUiThread {
                if (requestId != requestSerial) return@runOnUiThread
                val list = decorate(playable)
                if (list.isEmpty()) {
                    // 这一批全不可播放，直接继续下一批；队列见底时会重新生成或回退官方榜单。
                    loadMoreRecommend(requestId)
                    return@runOnUiThread
                }
                loadingMore = false
                loading.visibility = View.GONE
                appendFeed(list)
            }
        }
    }

    private fun refillRecommendQueue(requestId: Int) {
        if (recommendRefills >= maxRecommendRefills) {
            loadMoreOfficialPage()
            return
        }
        recommendRefills += 1
        recommender.load(prefs.skipSeen) { result ->
            runOnUiThread {
                if (requestId != requestSerial) return@runOnUiThread
                val known = adapter.items.mapTo(HashSet<String>()) { it.id }
                val fresh = result.getOrNull().orEmpty().filter { known.add(it.id) }
                if (fresh.isEmpty()) {
                    loadMoreOfficialPage()
                    return@runOnUiThread
                }
                recommendQueue.addAll(fresh)
                loadMoreRecommend(requestId)
            }
        }
    }

    private fun loadMoreOfficialPage() {
        loadingMore = true
        currentPage += 1
        loadFeed(reset = false)
    }

    private fun decorate(raw: List<VideoItem>): List<VideoItem> = raw.onEach { it.localFavorite = history.isLocalFavorite(it.id) }

    private fun note(event: String) = NavigationDiagnostics.note(this, event)

    private fun noteGateResult(label: String, candidates: List<VideoItem>, playable: List<VideoItem>) {
        if (playable.isNotEmpty()) {
            note("$label 验证 ${candidates.size} 条 → ${playable.size} 条可播放")
            return
        }
        val reasons = candidates.mapNotNull { it.playbackIssue }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .take(3).joinToString("，") { "${it.key} ×${it.value}" }
        note("$label 验证 ${candidates.size} 条 → 0 条可播放" + if (reasons.isBlank()) "" else "（$reasons）")
    }

    /**
     * 预检失败但看起来只是连不上视频源的候选。仅限好友、已删除、无权限等确定性原因
     * 不在其中：那些视频即使直接播放也放不了。
     */
    private fun unreachableCandidates(candidates: List<VideoItem>): List<VideoItem> =
        candidates.filter { it.playbackIssue !in permanentIssues }.onEach { it.playbackIssue = null }

    private fun onVideoEnded(position: Int) {
        if (position + 1 < adapter.itemCount) pager.setCurrentItem(position + 1, true)
        else { pendingAdvanceAfterLoad = true; loadMore() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun openAuthorProfile(view: View) {
        val item = adapter.items.getOrNull(pager.currentItem) ?: return
        openAuthor(item.authorId, item.author, item.authorUsername)
    }

    private fun openAuthor(id: String, name: String, username: String) {
        if (openingInternalPage || isFinishing || isDestroyed) return
        if (id.isBlank() && username.isBlank()) {
            Toast.makeText(this, "该视频没有作者资料", Toast.LENGTH_SHORT).show(); return
        }
        openingInternalPage = true
        adapter.pauseAll()
        authorLauncher.launch(Intent(this, AuthorActivity::class.java).apply {
            putExtra(AuthorActivity.EXTRA_ID, id)
            putExtra(AuthorActivity.EXTRA_NAME, name)
            putExtra(AuthorActivity.EXTRA_USERNAME, username)
        })
    }

    private fun openSavedVideos(kind: String) {
        if (openingInternalPage || isFinishing || isDestroyed) return
        openingInternalPage = true
        adapter.pauseAll()
        savedVideosLauncher.launch(Intent(this, SavedVideosActivity::class.java).putExtra(SavedVideosActivity.EXTRA_KIND, kind))
    }

    /** 搜索结果改成独立页面：视频名 / 标签 / 作者名分别成列表，而不是直接顶掉首页视频流。 */
    private fun openSearchPage(query: String) {
        if (openingInternalPage || isFinishing || isDestroyed) return
        openingInternalPage = true
        adapter.pauseAll()
        searchLauncher.launch(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_QUERY, query))
    }

    private fun openFollowingPage() {
        if (openingInternalPage || isFinishing || isDestroyed) return
        if (!api.isLoggedIn()) { showLoginDialog(); return }
        openingInternalPage = true
        adapter.pauseAll()
        followingLauncher.launch(Intent(this, FollowingActivity::class.java))
    }

    private fun showSearchDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(2)) }
        panel.addView(TextView(this).apply {
            text = "搜索标题、标签或作者"; setTextColor(0xFF607D93.toInt()); textSize = 13f; setPadding(0, 0, 0, dp(10))
        })
        val input = EditText(this).apply {
            hint = "例如 MMD、标签、作者名"; setSingleLine(true); setTextColor(0xFF17324A.toInt())
            setHintTextColor(0x99607D93.toInt()); background = ContextCompat.getDrawable(this@MainActivityV3, R.drawable.bg_input)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        panel.addView(input)
        val dialog = AlertDialog.Builder(this).setTitle("搜索 Iwara 视频").setView(panel)
            .setNegativeButton("取消", null).setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotBlank()) openSearchPage(q)
            }.create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }; dialog.show()
    }

    private fun showMainMenu() {
        val account = if (api.isLoggedIn()) "退出 Iwara 登录" else "登录 Iwara"
        // 同步点赞记录和诊断信息都挪进了设置页：主菜单留常用入口就够了。
        val items = arrayOf(account, "浏览历史", "我的收藏", "已下载", "已关注用户", "设置", "检查更新", "重新加载当前流")
        val dialog = AlertDialog.Builder(this).setTitle("IwaraFlow").setItems(items) { _, which ->
            when (which) {
                0 -> if (api.isLoggedIn()) { api.logout(); Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show(); loadFeed(reset = true) } else showLoginDialog()
                1 -> openSavedVideos(SavedVideosActivity.KIND_HISTORY)
                2 -> openSavedVideos(SavedVideosActivity.KIND_FAVORITES)
                3 -> openSavedVideos(SavedVideosActivity.KIND_DOWNLOADS)
                4 -> openFollowingPage()
                5 -> showSettingsDialog()
                6 -> updates.check(manual = true)
                7 -> loadFeed(reset = true)
            }
        }.create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }; dialog.show()
    }

    /** 手动把官方点赞补进“已看”，方便点赞很多的账号立刻生效。 */
    private fun syncLikedVideos() {
        if (!api.isLoggedIn()) { showLoginDialog(); return }
        Toast.makeText(this, "正在同步 Iwara 点赞记录…", Toast.LENGTH_SHORT).show()
        likedSync.syncNow { marked ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                note("手动同步点赞记录 $marked 条")
                Toast.makeText(this, "已同步 $marked 条点赞记录，刷新推荐即可生效", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLoginDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val email = EditText(this).apply {
            hint = "Iwara 邮箱"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine(true)
            setTextColor(0xFF17324A.toInt()); setHintTextColor(0x99607D93.toInt()); background = ContextCompat.getDrawable(this@MainActivityV3, R.drawable.bg_input)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val password = EditText(this).apply {
            hint = "密码"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true)
            setTextColor(0xFF17324A.toInt()); setHintTextColor(0x99607D93.toInt()); background = ContextCompat.getDrawable(this@MainActivityV3, R.drawable.bg_input)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        container.addView(email); container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) }); container.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle("登录 Iwara").setMessage("密码只用于登录请求；登录 Token 使用 Android 加密存储。")
            .setView(container).setNegativeButton("取消", null).setPositiveButton("登录", null).create()
        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val mail = email.text.toString().trim(); val pass = password.text.toString()
                if (mail.isBlank() || pass.isBlank()) { Toast.makeText(this, "请输入邮箱和密码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                confirm.isEnabled = false; confirm.text = "登录中…"
                api.login(mail, pass) { result -> runOnUiThread {
                    confirm.isEnabled = true; confirm.text = "登录"; Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        dialog.dismiss()
                        // 换账号后点赞记录要重新同步一次。
                        prefs.likedSyncAt = 0L
                        likedSync.syncIfStale()
                        loadFeed(reset = true)
                    }
                } }
            }
        }
        dialog.show()
    }

    private fun openSingleVideo(videoId: String) {
        loading.visibility = View.VISIBLE
        api.getVideo(videoId) { result -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            loading.visibility = View.GONE
            result.onSuccess { item ->
                saveCurrentHomeSession()
                item.localFavorite = history.isLocalFavorite(item.id); pagingEnabled = false; mode = "single"; invalidateRequests()
                adapter.replace(listOf(item)); pager.setCurrentItem(0, false); adapter.setActive(0)
            }.onFailure { Toast.makeText(this, it.message ?: "视频加载失败", Toast.LENGTH_LONG).show() }
            if (!openingInternalPage && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) adapter.resumeActive()
        } }
    }

    private fun showSettingsDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        panel.addView(sectionTitle("推荐"))
        val skipSeen = CheckBox(this).apply { text = "刷新推荐时排除已看视频"; isChecked = prefs.skipSeen }
        panel.addView(skipSeen)
        panel.addView(TextView(this).apply {
            text = "开启后只在生成新的推荐列表时过滤历史记录，不会在滑动过程中连续自动跳过。"; textSize = 12f
            setTextColor(0xFF607D93.toInt()); setPadding(dp(4), 0, 0, dp(8))
        })
        panel.addView(sectionTitle("播放"))
        val autoNext = CheckBox(this).apply { text = "播放完毕自动进入下一条"; isChecked = prefs.autoNext }
        val autoPip = CheckBox(this).apply { text = "切到后台时自动进入画中画"; isChecked = prefs.autoPip }
        panel.addView(autoNext); panel.addView(autoPip); panel.addView(sectionTitle("默认清晰度"))
        val qualityValues = arrayOf("highest", "Source", "1080", "720", "540", "360")
        val qualityNames = arrayOf("最高可用 / 原画", "Source", "1080p", "720p", "540p", "360p")
        val spinner = Spinner(this); spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualityNames)
        spinner.setSelection(qualityValues.indexOf(prefs.defaultQuality).let { if (it >= 0) it else 0 }); panel.addView(spinner)

        panel.addView(sectionTitle("维护"))
        panel.addView(actionRow("同步点赞记录", "把在网页端点过的赞补进“已看”，刷新推荐后生效。") { syncLikedVideos() })
        panel.addView(actionRow("诊断信息", "最近的异常、退出原因和加载线索，只存在本机，不会上传。") {
            NavigationDiagnostics.show(this)
        })

        // 多了“维护”这一段，矮屏幕上放不下，内容区要能滚动。
        val content = ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this).setTitle("设置").setMessage("播放行为、画质、推荐过滤和维护工具").setView(content)
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                prefs.skipSeen = skipSeen.isChecked; prefs.autoNext = autoNext.isChecked; prefs.autoPip = autoPip.isChecked
                prefs.defaultQuality = qualityValues[spinner.selectedItemPosition]
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show(); loadFeed(reset = true)
            }.create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }; dialog.show()
    }

    /**
     * 设置页里的一行“动作”。点了立刻执行，不等“保存”——它们本来就不是开关。
     * 也不关闭设置页：关掉的话，用户刚勾上还没保存的选项就白勾了。
     */
    private fun actionRow(title: String, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(TextView(context).apply {
                text = title; textSize = 15f; setTextColor(0xFFD84B73.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle; textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(0, dp(2), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; setTextColor(0xFFFF6F91.toInt()); textSize = 13f; setPadding(0, dp(12), 0, dp(4)); setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        val accent = 0xFFD84B73.toInt()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply { setTextColor(accent); backgroundTintList = ColorStateList.valueOf(0x00000000); setTypeface(null, android.graphics.Typeface.BOLD); textSize = 14f }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enqueueDownload(item: VideoItem, source: VideoSource) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 901)
            Toast.makeText(this, "请授予存储权限后再次点击下载", Toast.LENGTH_LONG).show(); return
        }
        try {
            val safeTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(90).ifBlank { item.id }
            val safeQuality = source.name.replace(Regex("[^0-9A-Za-z_-]"), "_")
            val request = DownloadManager.Request(Uri.parse(source.url)).setTitle(item.title).setDescription("IwaraFlow · ${source.name}")
                .setMimeType("video/mp4").addRequestHeader("Referer", "https://www.iwara.tv/")
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "IwaraFlow/${safeTitle}_${item.id}_${safeQuality}.mp4")
            val downloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            history.recordInteraction(item, "download", 1.1)
            // 记一笔，"已下载"那一页要靠它把视频和系统下载对上号。
            history.recordDownload(item, source.name, downloadId)
            Toast.makeText(this, "已加入系统下载：${source.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "下载创建失败：${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun enterPip() {
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        catch (e: Exception) { Toast.makeText(this, "画中画启动失败：${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (openingInternalPage) return
        if (prefs.autoPip && adapter.isActivePlaying() && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        adapter.setPipMode(isInPictureInPictureMode)
    }

    override fun onStart() {
        super.onStart()
        updates.tryContinueInstall()
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        if (openingInternalPage || isFinishing) return
        val videoId = pendingVideoId
        pendingVideoId = null
        if (videoId != null) openSingleVideo(videoId)
        else adapter.resumeActive()
    }

    override fun onPause() {
        if (openingInternalPage || !isInPictureInPictureMode) adapter.pauseAll()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("opening_internal_page", openingInternalPage)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (openingInternalPage || !isInPictureInPictureMode) adapter.pauseAll()
        super.onStop()
    }

    private fun showError(message: String) { error.text = message; error.visibility = View.VISIBLE }

    override fun onDestroy() {
        invalidateRequests()
        adapter.releaseAll(); playableGate.close(); mediaCache.close(); recommender.close()
        likedSync.close(); updates.close(); api.close(); history.close(); super.onDestroy()
    }
}
