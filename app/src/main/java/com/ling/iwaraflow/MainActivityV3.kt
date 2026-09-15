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
import androidx.activity.OnBackPressedCallback
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
    private lateinit var comments: CommentsHost

    /** 评论面板开着时返回键先关面板，而不是退出应用。 */
    private val closeCommentsOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { comments.close() }
    }

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
    /**
     * 连着这么多次重新生成推荐都拿不到新内容，才退回官方榜单分页。
     * 以前是“固定生成 3 批就降级”，刷得久的用户必然被踢出个性化推荐——
     * 越刷越不懂你就是这么来的。降级的判据应该是“真的找不到新候选了”。
     */
    private val maxEmptyRefills = 2

    /** 四个顶部流各自的现场（列表、看到第几条、翻到第几页），见 [FeedSessionStore]。 */
    private val homeFeedSessions = FeedSessionStore()

    /** 预检给出的确定性不可播放原因；这些视频直接播放同样放不了。 */
    private val permanentIssues = setOf("仅限好友观看", "视频仍在处理中", "视频已删除或不存在", "没有观看权限")

    /** 查本地库（排除已看之类）用的后台线程：不在主线程上碰数据库。 */
    private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 推荐算法一次产出的剩余候选，推荐流翻页从这里取，取完再重新生成。 */
    private val recommendQueue = ArrayList<VideoItem>()
    /**
     * 这一轮推荐里**已经处理过**的候选 id：显示出来的、还在队列里的，以及
     * 被可播放验证刷掉的。
     *
     * 以前判据是 `adapter.items`，也就是“真的显示出来了”。被判定不可播放的候选
     * 没进 adapter，于是下一轮重新生成时它又算“新内容”，可以被反复召回、反复探测；
     * 连着抽到同一批坏视频的话，[emptyRefills] 还会被它们一次次重置，永远降不了级。
     */
    private val consumedCandidates = HashSet<String>()

    /**
     * 这一轮推荐 / 这一次列表的会话号，曝光记录按它归组，见 [HistoryStore.recordImpression]。
     * 每次整列表重新加载换一个。
     */
    private var feedSession = ""
    /** 连续几次重新生成推荐都没拿到**能播的**新内容。拿到就归零。 */
    private var emptyRefills = 0

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

    /**
     * 分享目标应用也走 launcher。直接 startActivity 的话，目标应用弹出来的一瞬间会触发
     * onUserLeaveHint，开了“自动小窗”的话应用就缩进小窗藏到人家后面去了。
     */
    private val shareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        sharingToApp = false
        resumeAfterInternalPage()
    }

    /**
     * 正在把链接交给别的应用。QQ 这类应用的分享入口是一张小窗卡片，盖在本页上面，
     * 视频要在后面接着播（和哔哩哔哩分享到 QQ 一样），所以这期间 onPause 不暂停。
     */
    private var sharingToApp = false

    private val savedVideosLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        openingInternalPage = false
        val videoId = result.data?.getStringExtra(SavedVideosActivity.EXTRA_VIDEO_ID).orEmpty()
        pendingVideoId = videoId.takeIf { it.isNotBlank() }
        pendingLocalUri = result.data?.getStringExtra(SavedVideosActivity.EXTRA_LOCAL_URI)?.takeIf { it.isNotBlank() }
        pendingTitle = result.data?.getStringExtra(SavedVideosActivity.EXTRA_TITLE).orEmpty()
    }

    /** 已下载页面选中的本地文件；有它就直接播本地，不走网络。 */
    private var pendingLocalUri: String? = null
    private var pendingTitle = ""

    /**
     * 从搜索页 / 作者页 / 关注列表回来。那几页里搜了什么、点开了谁、关注了谁都已经记进画像，
     * 顺手把还没展示的推荐候选按新画像重排一次——跨页面学到的兴趣当场就能在主页看见，
     * 而不是等下一次重新生成候选。
     */
    private fun resumeAfterInternalPage() {
        openingInternalPage = false
        rerankQueue()
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
        recommender = RecommendationEngine(api, history).apply { classicsEvery = prefs.classicsEvery }
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
            onNeedLogin = ::showLoginDialog,
            onShare = ::shareVideo,
            onComments = ::openComments,
            onInfo = { item -> if (!isInPictureInPictureMode) comments.open(item, CommentsPanel.Tab.INFO) },
            onSignal = { _, action -> if (action != "comments") rerankQueue() },
            onDisliked = { item, _ -> afterDislike(item) },
            onFullscreen = { _, enabled -> setFullscreen(enabled) }
        )
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        comments = CommentsHost(
            pager = pager,
            panelRoot = findViewById(R.id.commentsPanel),
            scrim = findViewById(R.id.commentsScrim),
            adapter = adapter,
            api = api,
            topBarHeight = { topBar.height.takeIf { it > 0 } ?: dp(58) },
            gapPx = dp(COMMENTS_PANEL_GAP_DP),
            onNeedLogin = ::showLoginDialog,
            onOpenAuthor = { author -> openAuthor(author.id, author.name, author.username) },
            onOpenChanged = { open -> closeCommentsOnBack.isEnabled = open },
            onCommentPosted = { item -> history.recordInteraction(item, "comment", 1.5); rerankQueue() },
            onOpenTag = ::openTagSearch,
            // 简介里写清楚这条是怎么被推荐出来的；别的流（最新 / 流行 / 人气）没有理由可写。
            reasonFor = { videoId ->
                if (mode != "recommend") null
                else recommender.reasonFor(videoId)?.let { reason ->
                    // 调试模式：理由下面再附上这条视频的分是怎么算出来的。
                    if (!prefs.recommendDebug) reason
                    else reason + (recommender.explain(videoId)?.let { "\n$it" }.orEmpty())
                }
            }
        )
        onBackPressedDispatcher.addCallback(this, closeCommentsOnBack)
        onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 翻到下一条了，上一条的评论就不该还挂着。
                comments.close()
                adapter.setActive(position)
                if (pagingEnabled && position >= adapter.itemCount - 5) loadMore()
                // 每翻几页按最新画像重排一次还没展示的候选。
                if (mode == "recommend" && ++pagesSinceRerank >= RERANK_EVERY_PAGES) rerankQueue()
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
        // 从桌面图标再次打开：singleTop 的主页收到的是启动器的 intent。
        if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.action == Intent.ACTION_MAIN) launchedFromIcon = true
        if (intent.getBooleanExtra("return_recommend", false)) returnToRecommend()
    }

    /** 这次 onResume 是不是用户点桌面图标带来的（而不是子页面进小窗把主页顶出来）。 */
    private var launchedFromIcon = false

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
            val sameHomeMode = mode == newMode && newMode in FeedSessionStore.HOME_MODES

            if (sameHomeMode && newMode == "recommend") {
                saveCurrentHomeSession()
                homeFeedSessions.forget("recommend")
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

            val saved = homeFeedSessions.restorable(newMode)
            if (saved != null) {
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
        if (mode !in FeedSessionStore.HOME_MODES || adapter.items.isEmpty()) return
        adapter.savePlaybackPosition()
        val index = pager.currentItem.coerceIn(0, adapter.items.lastIndex)
        homeFeedSessions.save(mode, FeedSessionStore.Session(
            items = adapter.items.toList(),
            currentIndex = index,
            currentPage = currentPage,
            pagingEnabled = pagingEnabled
        ))
    }

    private fun restoreHomeSession(session: FeedSessionStore.Session) {
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

    /**
     * 登录之后：**先种画像，再生成第一批推荐**。
     *
     * 登录成功和「刷新推荐」以前是同时发生的，官方点赞还在后台一页页翻，
     * 推荐已经算完了——一个用了多年 Iwara 的账号第一屏仍然是冷启动的样子，
     * 要到第二次刷新才像认识你。这里只等第一页（最多 50 个点赞）种进画像，
     * 剩下的照旧交给后台同步。等太久也不行，所以给它一个上限，到点就照常刷新。
     */
    /**
     * 退出登录：账号级的东西一起放下——账号 id、关注名单缓存，以及
     * [HistoryStore] 里那份“云端数据属于谁”的作用域。本机自己的浏览记录、
     * 收藏、不感兴趣是设备级的，照旧留着。
     */
    private fun logOut() {
        api.logout()
        prefs.accountId = ""
        prefs.likedSyncAt = 0L
        history.accountId = ""
        RecommendationEngine.notifyAccountChanged("")
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        loadFeed(reset = true)
    }

    private fun seedThenReload() {
        loading.visibility = View.VISIBLE
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = Runnable {
            if (started.getAndSet(true)) return@Runnable
            if (isFinishing || isDestroyed) return@Runnable
            likedSync.syncIfStale()
            loadFeed(reset = true)
        }
        likedSync.seedFirstPage { runOnUiThread(go) }
        window.decorView.postDelayed(go, LOGIN_SEED_BUDGET_MS)
    }

    private fun loadFeed(reset: Boolean) {
        if (reset) {
            currentPage = 0
            emptyRefills = 0
            consumedCandidates.clear()
            feedSession = java.util.UUID.randomUUID().toString()
            adapter.impressionSession = feedSession
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
                    note("推荐候选 ${raw.size} 条（抽页 ${recommender.sampledPages.joinToString(",")}）" +
                        recommender.recallNote.takeIf { it.isNotBlank() }?.let { " 召回：$it" }.orEmpty())
                    val firstPage = minOf(recommendFirstPage, NetworkProfile.coldStartCandidates(this))
                    val first = raw.take(firstPage)
                    val rest = raw.drop(firstPage)
                    playableGate.filterPlayable(first, prefs.defaultQuality, maxItems = first.size, onFirstBatch = head) { playable ->
                        runOnUiThread {
                            if (requestId != requestSerial) return@runOnUiThread
                            noteGateResult("推荐首屏", first, playable)
                            recommendQueue.clear()
                            recommendQueue.addAll(rest)
                            raw.forEach { consumedCandidates += it.id }
                            showFeedReset(requestId, playable, emptyMessage, first)
                        }
                    }
                }.onFailure {
                    runOnUiThread {
                        if (requestId != requestSerial) return@runOnUiThread
                        awaitingFullFeed = false
                        loading.visibility = View.GONE
                        note("推荐加载失败：${it.message?.take(120)}")
                        showError(NetworkProxy.explain(it.message) ?: "推荐加载失败\n${it.message}")
                    }
                }
            }
            return
        }

        // 首屏只验证很小的窗口保证秒开；续页整页验证，避免每页只剩个位数视频。
        // 弱网上每个候选都是一次额外往返，窗口再收一点，先把首屏放出来。
        val candidateWindow = if (reset) NetworkProfile.coldStartCandidates(this) else recommendPageSize
        val callback: (Result<List<VideoItem>>) -> Unit = { result ->
            result.onSuccess { official ->
                prepareOfficialCandidates(official) { raw ->
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
                }
            }.onFailure {
                runOnUiThread {
                    if (requestId != requestSerial) return@runOnUiThread
                    awaitingFullFeed = false
                    loading.visibility = View.GONE
                    loadingMore = false
                    note("$mode 第 $currentPage 页加载失败：${it.message?.take(120)}")
                    if (reset) showError(NetworkProxy.explain(it.message)
                        ?: "Iwara 数据加载失败\n${it.message}\n\n请确认网络可以访问 iwara.tv")
                }
            }
        }

        api.getVideos(if (mode == "recommend") "trending" else mode, currentPage, pageSize, callback)
    }

    /**
     * 推荐流回退到官方榜单时，官方候选也要过一遍本地这一套：排除已看 → 本地评分 →
     * 作者打散。原样贴一页 trending 上来，等于这一页完全没有个性化。
     *
     * 其它几个榜单页（最新 / 流行 / 人气）是用户自己选的排序，顺序不动；只有在设置里
     * 打开「最新 / 流行 / 人气 也排除已看」之后，才在这里把看过的滤掉。
     */
    private fun prepareOfficialCandidates(raw: List<VideoItem>, next: (List<VideoItem>) -> Unit) {
        if (mode == "recommend") {
            if (raw.size < 2) { next(raw); return }
            recommender.rankOfficial(raw, prefs.skipSeen) { ranked -> next(ranked) }
            return
        }
        if (!prefs.skipSeenEverywhere || raw.isEmpty()) { next(raw); return }
        dbExecutor.execute {
            val unseen = runCatching {
                val seen = history.loadStatuses(raw.map { it.id }).seen
                raw.filterNot { it.id in seen }
            }.getOrDefault(raw)
            // 整页都看过就别把这一页也扔了，不然只能翻到空页。
            next(if (unseen.isEmpty()) raw else unseen)
        }
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
        val head = decorate(batch)
        adapter.replace(head)
        noteImpressions(head, 0)
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
            noteImpressions(list, 0)
            pager.setCurrentItem(0, false)
            adapter.setActive(0)
        }
    }

    private fun appendFeed(list: List<VideoItem>) {
        val before = adapter.itemCount
        adapter.append(list)
        noteImpressions(list, before)
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

    /**
     * 兴趣管理：把点过「不感兴趣：作者 / 标签」的都列出来，可以随时恢复。
     *
     * 明确的负反馈本来连探索位都不给，恢复入口就是它唯一的出路——
     * 口味变了、或者手滑点错了，不该永远出不来。
     */
    private fun showInterestManager() {
        val profile = runCatching { history.preferenceProfile() }.getOrNull()
        val authors = profile?.mutedAuthors.orEmpty().sorted()
        val tags = profile?.mutedTags.orEmpty().sorted()
        val entries = authors.map { DislikeSheet.Kind.AUTHOR to it } + tags.map { DislikeSheet.Kind.TAG to it }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("兴趣管理")
                .setMessage("还没有点过「不感兴趣：作者 / 标签」。\n\n点过之后这类内容就不再进入推荐，也不会随时间自己恢复；只有在这里点“恢复”才会回来。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = entries.map { (kind, key) ->
            if (kind == DislikeSheet.Kind.AUTHOR) "作者 @$key" else "标签 #$key"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("兴趣管理 · 点一项恢复")
            .setItems(labels) { _, which ->
                val (kind, key) = entries[which]
                val removed = runCatching { history.forgetDislike(kind, key) }.getOrDefault(0)
                val what = if (kind == DislikeSheet.Kind.AUTHOR) "@$key" else "#$key"
                Toast.makeText(
                    this,
                    if (removed > 0) "已恢复 $what，之后还会推荐" else "没有找到 $what 的记录",
                    Toast.LENGTH_SHORT
                ).show()
                if (removed > 0) rerankQueue()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 上次重排剩余候选之后翻了几页。 */
    private var pagesSinceRerank = 0
    private var rerankSerial = 0

    /**
     * 按此刻的画像重排还没展示的推荐候选。只动队列，不动已经在流里的卡片，
     * 也不发网络请求；结果回来时队列已经被翻页消费过的话，就只替换还剩下的部分。
     */
    private fun rerankQueue() {
        pagesSinceRerank = 0
        if (mode != "recommend" || recommendQueue.size < 2) return
        val snapshot = ArrayList(recommendQueue)
        val serial = ++rerankSerial
        val request = requestSerial
        recommender.rerank(snapshot) { reordered ->
            runOnUiThread {
                if (isFinishing || isDestroyed || serial != rerankSerial || request != requestSerial) return@runOnUiThread
                val stillQueued = recommendQueue.mapTo(HashSet()) { it.id }
                val kept = reordered.filter { it.id in stillQueued }
                if (kept.size != recommendQueue.size) return@runOnUiThread
                recommendQueue.clear()
                recommendQueue.addAll(kept)
            }
        }
    }

    /** 长按上半区选了“不感兴趣”（画像已经记好）：剩余候选重排，推荐流里跳到下一条。 */
    private fun afterDislike(item: VideoItem) {
        rerankQueue()
        if (mode != "recommend") return
        val position = pager.currentItem
        if (adapter.items.getOrNull(position)?.id == item.id && position + 1 < adapter.itemCount) {
            pager.setCurrentItem(position + 1, true)
        }
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
                // 真的产出了能播的新内容，降级计数才归零，见 refillRecommendQueue。
                emptyRefills = 0
                appendFeed(list)
            }
        }
    }

    /**
     * 重新生成一批推荐候选。只有**连着 [maxEmptyRefills] 次都一条能播的新内容都拿不到**
     * 才退回官方榜单，而不是按生成次数封顶；判据是“有没有产出能播的结果”，
     * 不是“有没有见过的候选 id”——全是不可播放的坏视频也该算没产出；退回之后官方候选同样会走本地这一套
     * （排除已看 → 本地评分 → 作者打散），见 [prepareOfficialCandidates]。
     */
    private fun refillRecommendQueue(requestId: Int) {
        if (emptyRefills >= maxEmptyRefills) {
            note("连续 $emptyRefills 次没有新候选，回退官方榜单（仍按本地画像重排）")
            loadMoreOfficialPage()
            return
        }
        recommender.load(prefs.skipSeen) { result ->
            runOnUiThread {
                if (requestId != requestSerial) return@runOnUiThread
                // 这一轮处理过的一概不再进队列：显示过的、还排着的、验证没通过的都算。
                val fresh = result.getOrNull().orEmpty().filter { consumedCandidates.add(it.id) }
                if (fresh.isEmpty()) {
                    emptyRefills += 1
                    if (emptyRefills >= maxEmptyRefills) loadMoreOfficialPage() else refillRecommendQueue(requestId)
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

    /** 一次问清这一批的本地收藏状态，不是一条视频查一次库。 */
    /**
     * 记一批曝光：这几条视频进了流的第几位、当时是怎么被选出来的。
     *
     * 推荐诊断以前是从全局行为表算的，而播放器是所有页面共用的——在作者页连看一小时
     * 同一个作者，那些观看也会被算进“推荐质量”。改成按曝光记之后，指标只看本流，
     * 平均播放也是真实播放时长而不是 `history.last_position`。只存本机，不上传。
     */
    private fun noteImpressions(list: List<VideoItem>, startPosition: Int) {
        if (list.isEmpty() || feedSession.isBlank()) return
        val session = feedSession
        val surface = mode
        val rows = list.mapIndexed { index, item ->
            val candidate = if (surface == HistoryStore.SURFACE_RECOMMEND) recommender.candidateFor(item.id) else null
            ImpressionRow(
                item = item,
                position = startPosition + index,
                sources = candidate?.sources?.joinToString("\u001F").orEmpty(),
                score = candidate?.let { it.sourceScore + it.baseQuality } ?: 0.0,
                reason = candidate?.reason().orEmpty(),
                exploration = candidate?.exploration == true,
                classic = candidate?.classic == true
            )
        }
        history.post {
            rows.forEach { row ->
                history.recordImpression(
                    session, row.item, surface, row.position,
                    row.sources, row.score, row.reason, row.exploration, row.classic
                )
            }
        }
    }

    /** [noteImpressions] 里一条待写入的曝光；取好数据再交给写线程，别在后台线程读界面状态。 */
    private class ImpressionRow(
        val item: VideoItem,
        val position: Int,
        val sources: String,
        val score: Double,
        val reason: String,
        val exploration: Boolean,
        val classic: Boolean
    )

    private fun decorate(raw: List<VideoItem>): List<VideoItem> {
        if (raw.isEmpty()) return raw
        val favorites = runCatching { history.loadStatuses(raw.map { it.id }).favorites }.getOrNull()
        return raw.onEach { it.localFavorite = favorites?.contains(it.id) ?: history.isLocalFavorite(it.id) }
    }

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
        // 专门点进作者主页：对这个作者有点兴趣。
        history.recordInteraction(item, "author_visit", 0.3)
        if (item.authorId.isBlank() && item.authorUsername.isBlank() && item.id.isNotBlank()) {
            // 本地下载记录里没有作者 id：现拉一次详情再进。
            Toast.makeText(this, "正在读取作者资料…", Toast.LENGTH_SHORT).show()
            api.getVideo(item.id) { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.onSuccess { detail ->
                        item.authorId = detail.authorId
                        item.authorUsername = detail.authorUsername
                        if (item.description.isBlank()) item.description = detail.description
                        note("补拉详情 ${item.id}：作者 id=${detail.authorId.take(8)} 用户名=${detail.authorUsername}")
                        history.updateDownloadDetail(item.id, detail.authorId, detail.authorUsername, detail.description)
                        openAuthor(item.authorId, item.author, item.authorUsername)
                    }.onFailure {
                        note("补拉详情失败 ${item.id}：${it.message?.take(80)}")
                        Toast.makeText(this, "读取作者资料失败：${IwaraApi.explainError(it)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
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
    private fun openSearchPage(query: String) = launchSearch(query, asTag = false)

    /** 简介页点了标签：收起面板去搜这个标签，并记一笔兴趣。 */
    private fun openTagSearch(tag: String) {
        if (tag.isBlank()) return
        history.recordInteraction(VideoItem("tag:$tag", tag, "", listOf(tag), 0), HistoryStore.ACTION_SEARCH, HistoryStore.SEARCH_WEIGHT)
        comments.close()
        launchSearch(tag, asTag = true)
    }

    private fun launchSearch(query: String, asTag: Boolean) {
        if (openingInternalPage || isFinishing || isDestroyed) return
        openingInternalPage = true
        adapter.pauseAll()
        searchLauncher.launch(Intent(this, SearchActivity::class.java)
            .putExtra(SearchActivity.EXTRA_QUERY, query)
            .putExtra(SearchActivity.EXTRA_AS_TAG, asTag))
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
                0 -> if (api.isLoggedIn()) { logOut() } else showLoginDialog()
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
            // setSingleLine 会把变换方法换成单行的，密码的圆点遮罩就没了，得再装回去。
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setTextColor(0xFF17324A.toInt()); setHintTextColor(0x99607D93.toInt()); background = ContextCompat.getDrawable(this@MainActivityV3, R.drawable.bg_input)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        container.addView(email); container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) }); container.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle("登录 Iwara")
            .setMessage("密码只用于登录请求；登录 Token 使用 Android 加密存储。没有账号可点“注册”，在应用内打开官网注册页。")
            .setView(container).setNeutralButton("注册", null).setNegativeButton("取消", null).setPositiveButton("登录", null).create()
        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, RegisterActivity::class.java))
            }
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
                        seedThenReload()
                    }
                } }
            }
        }
        dialog.show()
    }

    /**
     * 播一个下载好的本地文件：视频信息从下载记录里取，播放源就是那个文件，
     * 不去网上解析地址，所以离线也能放。
     */
    private fun openLocalVideo(videoId: String, localUri: String, title: String) {
        val record = history.downloadRecords(limit = 5000).firstOrNull { it.item.id == videoId }
        val item = record?.item ?: VideoItem(videoId, title.ifBlank { "已下载的视频" }, "", emptyList(), 0)
        item.sources = listOf(VideoSource(LOCAL_SOURCE_NAME, localUri, 10_000))
        item.streamUrl = localUri
        item.selectedQuality = LOCAL_SOURCE_NAME
        item.localFavorite = history.isLocalFavorite(item.id)
        saveCurrentHomeSession()
        pagingEnabled = false; mode = "single"; invalidateRequests()
        loading.visibility = View.GONE
        adapter.replace(listOf(item)); pager.setCurrentItem(0, false); adapter.setActive(0)
        // 这里是从 onResume 直接调过来的，生命周期状态还没翻到 RESUMED，不能拿它当条件——
        // 否则播放一直处于禁用状态，这一页黑屏，之后切到哪个榜单都黑屏。
        if (!openingInternalPage) adapter.resumeActive()
        // 下载记录里只有标题和作者名：后台补拉一次详情，把作者 id、简介、点赞数填上，
        // 这样评论区、简介页和“进作者主页”都能正常用。拉不到（离线）也不影响播放。
        val requestSerial = ++localDetailSerial
        api.getVideo(videoId) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed || requestSerial != localDetailSerial) return@runOnUiThread
                val current = adapter.items.firstOrNull { it.id == videoId } ?: return@runOnUiThread
                result.onFailure { note("本地视频补拉详情失败 $videoId：${it.message?.take(80)}") }
                result.onSuccess { detail ->
                    note("本地视频补拉详情 $videoId：作者 id=${detail.authorId.take(8)} 用户名=${detail.authorUsername} 简介 ${detail.description.length} 字")
                    current.authorId = detail.authorId
                    current.authorUsername = detail.authorUsername
                    current.description = detail.description
                    current.likes = detail.likes
                    current.liked = detail.liked
                    current.authorFollowing = detail.authorFollowing
                    history.updateDownloadDetail(videoId, detail.authorId, detail.authorUsername, detail.description)
                    adapter.refreshItem(videoId)
                }
            }
        }
    }

    /** 本地视频补拉详情的序号：连着点两个下载视频时，只认最后一次的结果。 */
    private var localDetailSerial = 0

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

    /**
     * 设置弹窗自己是一个类（[SettingsDialogController]）：那三百行全是搭界面和存偏好，
     * 和这个页面的播放、导航、下载没有关系。这里只回答它问的几个问题。
     */
    private fun showSettingsDialog() {
        SettingsDialogController(
            activity = this,
            prefs = prefs,
            currentMode = { mode },
            onClassicsChanged = { recommender.classicsEvery = it },
            onSaved = { reload -> if (reload) loadFeed(reset = true) else adapter.applyDisplayPrefs() },
            onSyncLikes = { syncLikedVideos() },
            onInterestManager = { showInterestManager() },
            onDiagnostics = { NavigationDiagnostics.show(this) }
        ).show()
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        val accent = 0xFFD84B73.toInt()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply { setTextColor(accent); backgroundTintList = ColorStateList.valueOf(0x00000000); setTypeface(null, android.graphics.Typeface.BOLD); textSize = 14f }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enqueueDownload(item: VideoItem, source: VideoSource) {
        DownloadPrompt.confirmIfDuplicate(this, history, item, source) { startDownload(item, source) }
    }

    private fun startDownload(item: VideoItem, source: VideoSource) {
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

    /**
     * 打开评论面板。面板高度和画面顶边按当前视频的宽高比算：横屏视频面板顶边贴住画面底边，
     * 竖屏视频画面缩到顶栏和面板之间。播放器不动，视频照常播。
     */
    private fun openComments(item: VideoItem) {
        if (isInPictureInPictureMode) return
        comments.open(item)
    }

    /**
     * 分享当前视频：弹应用内的分享面板，选中目标应用后直接拉起它。
     * 期间标记成“正在打开内部页面”（不自动进小窗），并且不暂停播放——
     * QQ 的分享入口是盖在本页上的小窗卡片，视频在后面照常播。
     */
    private fun shareVideo(item: VideoItem) {
        if (openingInternalPage || isFinishing || isDestroyed) return
        if (item.id.isBlank()) {
            Toast.makeText(this, "这个视频没有可分享的链接", Toast.LENGTH_SHORT).show()
            return
        }
        SharePanel.show(this, VideoShare.shareText(item), item.title, "分享视频链接") { intent ->
            openingInternalPage = true
            sharingToApp = true
            runCatching { shareLauncher.launch(intent) }.onFailure {
                openingInternalPage = false
                sharingToApp = false
                Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 全屏时返回键先退出全屏，而不是直接退出应用。 */
    private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreen(false)
    }

    private fun setFullscreen(enabled: Boolean) {
        if (isFinishing || isDestroyed) return
        if (enabled) comments.close()
        FullscreenMode.apply(this, adapter, listOf(topBar), enabled)
        exitFullscreenOnBack.isEnabled = enabled
    }

    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()

    private fun enterPip() {
        comments.close()
        try { enterPictureInPictureMode(pipParams()) }
        catch (e: Exception) { Toast.makeText(this, "画中画启动失败：${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (openingInternalPage) return
        if (prefs.autoPip && adapter.isActivePlaying() && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) comments.close()
        // 全屏时顶栏本来就是收起的，退出小窗别把它放回来。
        topBar.visibility = if (isInPictureInPictureMode || adapter.isFullscreen) View.GONE else View.VISIBLE
        adapter.setPipMode(isInPictureInPictureMode)
        // 退出小窗时页面已经停在后台（没有被展开成全屏）：是用户把小窗关掉了。
        // onStop 那会儿还算在小窗里没停播，这里必须停，否则没画面还一直出声。
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            note("小窗被关闭：停止播放")
            adapter.pauseAll()
        }
    }

    override fun onStart() {
        super.onStart()
        updates.tryContinueInstall()
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        // 作者页 / 搜索页的小窗还在别的任务里放着。这个判断要放在 openingInternalPage 之前：
        // 子页面进小窗时主页正是“正在打开内部页面”的状态，被动顶上来的主页不能就这么黑着。
        val fromIcon = launchedFromIcon
        launchedFromIcon = false
        val pip = PipRegistry.otherPipActivity(this)
        if (!isInPictureInPictureMode && !isFinishing && pip != null) {
            // 小窗被关掉后子页面并不结束，只是停在自己的任务里；这时再打开应用，
            // 回到的应该是刚才小窗里那条视频，而不是推荐页。
            if (fromIcon || !pip.isInPictureInPictureMode) {
                // 用户点图标要的是这个视频：把小窗（或停在后台的子页面）展开到前台。展开成功的话
                // 本页马上会被盖住；过一会儿还在前台就说明系统没展开，那就关掉它，让本页正常用。
                if (PipRegistry.expandInto(this)) {
                    note("主页从图标打开：展开子页面小窗")
                    pager.postDelayed({ dismissStrandedPip() }, PIP_EXPAND_GRACE_MS)
                    return
                }
                if (PipRegistry.dismiss(this)) note("主页从图标打开：小窗展开失败，已关闭小窗")
                openingInternalPage = false
            } else {
                // 是小窗把子页面带走、主页被动露出来的：退到后台，露出桌面，和按了 Home 一样。
                moveTaskToBack(true)
                return
            }
        }
        if (openingInternalPage || isFinishing) return
        val videoId = pendingVideoId
        val localUri = pendingLocalUri
        pendingVideoId = null
        pendingLocalUri = null
        if (videoId != null && localUri != null) openLocalVideo(videoId, localUri, pendingTitle)
        else if (videoId != null) openSingleVideo(videoId)
        else adapter.resumeActive()
    }

    /**
     * 点图标展开子页面小窗之后本页还留在前台：系统没有把小窗展开。关掉小窗，
     * 把“正在打开内部页面”的锁解开，本页接着播自己的视频，菜单里的页面也都能开了。
     */
    private fun dismissStrandedPip() {
        if (isFinishing || isDestroyed || isInPictureInPictureMode) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!PipRegistry.dismiss(this)) return
        note("主页从图标打开：小窗没有展开，已关闭小窗")
        openingInternalPage = false
        adapter.resumeActive()
    }

    override fun onPause() {
        // 只暂停不释放：半透明界面盖上来时只会走到这里，释放了画面就黑。
        // 正在把链接交给别的应用时干脆不暂停：QQ 的分享卡片盖在上面，视频照常播；
        // 真的进后台会接着走 onStop，那里才停。
        if (sharingToApp && !isInPictureInPictureMode) {
            // 继续播
        } else if (openingInternalPage || !isInPictureInPictureMode) {
            adapter.suspendPlayback()
        }
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
        dbExecutor.shutdownNow()
        if (::comments.isInitialized) comments.release()
        adapter.releaseAll(); playableGate.close(); mediaCache.close(); recommender.close()
        likedSync.close(); updates.close(); api.close(); history.close(); super.onDestroy()
    }

    companion object {
        /** 登录后最多等这么久的“第一页点赞种画像”，到点就照常刷新，见 [seedThenReload]。 */
        const val LOGIN_SEED_BUDGET_MS = 4000L
        /** 评论面板顶边比“刚好贴住画面底边”再往下收的距离（dp）。 */
        const val COMMENTS_PANEL_GAP_DP = 20
        /** 已下载视频的播放源名字，画质按钮上显示它。 */
        const val LOCAL_SOURCE_NAME = "本地文件"
        /** 点图标展开子页面小窗后，等多久还没被盖住就当作展开失败。 */
        const val PIP_EXPAND_GRACE_MS = 1200L
        /** 推荐流每翻这么多页，按最新画像重排一次剩余候选。 */
        const val RERANK_EVERY_PAGES = 4
    }
}
