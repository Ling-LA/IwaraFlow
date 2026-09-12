package com.ling.iwaraflow

import android.app.Activity
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
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.transform.CircleCropTransformation

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
    private lateinit var comments: CommentsHost
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
    private var adjustingLoopEdge = false
    private var exiting = false
    private var descriptionExpanded = false
    private val pageSize = 36

    private fun setDescriptionExpanded(expanded: Boolean) {
        if (descriptionExpanded == expanded) return
        descriptionExpanded = expanded
        descriptionView.maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES
        descriptionView.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
    }

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
                // 一滑作品列表就把展开的简介收回去，给列表让地方。
                if (dy != 0) setDescriptionExpanded(false)
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (!noMore && !loadingPage && lm.findLastVisibleItemPosition() >= works.size - 6) loadNextPage()
            }
        })
        // 简介默认只显示三行，点一下展开全文，再点收起。
        descriptionView.setOnClickListener { setDescriptionExpanded(!descriptionExpanded) }

        feedAdapter = VideoAdapter(
            api = api,
            history = history,
            prefs = prefs,
            mediaCache = mediaCache,
            onDownload = ::download,
            onEnterPip = ::enterPip,
            onShare = ::shareVideo,
            onEnded = ::nextWork,
            onNeedLogin = { Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_SHORT).show() },
            onComments = { comments.open(it) },
            onInfo = { comments.open(it, CommentsPanel.Tab.INFO) }
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
            // 左上角的“‹ 作者作品”按钮：16dp 边距 + 44dp 高。
            topBarHeight = { (60 * density).toInt() },
            gapPx = (20 * density).toInt(),
            onNeedLogin = { Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_SHORT).show() },
            onOpenAuthor = ::openAnotherAuthor
        )
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                comments.close()
                if (!adjustingLoopEdge) feedAdapter.setActive(position)
                if (!noMore && !loadingPage && position >= feedAdapter.itemCount - 5) loadNextPage()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) normalizeLoopEdge()
            }
        })

        findViewById<View>(R.id.authorBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.authorShare).setOnClickListener { shareAuthor() }
        findViewById<View>(R.id.feedBack).setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
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
            finishSafely()
        }
    }

    private fun loadProfile(username: String) {
        statusView.text = "正在读取作者资料…"
        api.getAuthorProfile(username) { result ->
            if (isFinishing || isDestroyed || exiting) return@getAuthorProfile
            runOnUiThread {
                if (isFinishing || isDestroyed || exiting) return@runOnUiThread
                result.onSuccess { loaded ->
                    author = loaded
                    nameView.text = loaded.name
                    usernameView.text = "@${loaded.username}"
                    descriptionView.text = loaded.description
                    if (loaded.avatarUrl.isNotBlank()) {
                        findViewById<android.widget.ImageView>(R.id.authorAvatar).load(loaded.avatarUrl) {
                            crossfade(true)
                            placeholder(R.drawable.bg_avatar_placeholder)
                            error(R.drawable.bg_avatar_placeholder)
                            transformations(CircleCropTransformation())
                        }
                    }
                    refreshRelationUi()
                    if (api.isLoggedIn()) {
                        api.getFriendStatus(loaded.id) { statusResult ->
                            if (isFinishing || isDestroyed || exiting) return@getFriendStatus
                            runOnUiThread {
                                if (isFinishing || isDestroyed || exiting) return@runOnUiThread
                                statusResult.onSuccess {
                                    loaded.friendStatus = it
                                    loaded.friend = it == "friends"
                                }
                                refreshRelationUi()
                            }
                        }
                    }
                    loadNextPage()
                }.onFailure { statusView.text = "作者资料加载失败：${it.message}" }
            }
        }
    }

    private fun loadNextPage() {
        val a = author ?: return
        if (loadingPage || noMore || a.id.isBlank() || exiting) return
        loadingPage = true
        statusView.text = if (works.isEmpty()) "正在检查作者作品是否可播放…" else "正在加载更多作品…"
        api.getAuthorVideos(a.id, page, pageSize) { result ->
            if (isFinishing || isDestroyed || exiting) return@getAuthorVideos
            result.onSuccess { raw ->
                if (raw.isEmpty()) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || exiting) return@runOnUiThread
                        loadingPage = false
                        noMore = true
                        if (inFeed) rebuildFeedKeepingCurrent()
                        updateStatus()
                    }
                    return@onSuccess
                }
                gate.inspectAll(raw, prefs.defaultQuality) { checked ->
                    if (isFinishing || isDestroyed || exiting) return@inspectAll
                    runOnUiThread {
                        if (isFinishing || isDestroyed || exiting) return@runOnUiThread
                        val currentId = if (inFeed) feedAdapter.items.getOrNull(pager.currentItem)?.id else null
                        val start = works.size
                        works += checked
                        playableWorks += checked.filter { it.playbackIssue == null }
                        listAdapter.notifyItemRangeInserted(start, checked.size)
                        page++
                        noMore = raw.size < pageSize
                        loadingPage = false
                        if (inFeed) rebuildFeed(currentId)
                        updateStatus()
                    }
                }
            }.onFailure {
                runOnUiThread {
                    if (isFinishing || isDestroyed || exiting) return@runOnUiThread
                    loadingPage = false
                    statusView.text = "作品加载失败：${it.message}"
                }
            }
        }
    }

    private fun updateStatus() {
        // 从评论区点进来的用户不一定发过视频：资料照常显示，作品列表就明说没有。
        if (works.isEmpty() && noMore) { statusView.text = "该用户没有作品"; return }
        val unavailable = works.count { it.playbackIssue != null }
        statusView.text = buildString {
            append("作品 ${works.size} 条")
            if (unavailable > 0) append("  ·  $unavailable 条当前不可播放")
            if (!noMore) append("  ·  下滑继续加载")
        }
    }

    private fun buildFeedItems(): List<VideoItem> {
        if (!noMore || playableWorks.size <= 1) return playableWorks.toList()
        return buildList {
            add(playableWorks.last())
            addAll(playableWorks)
            add(playableWorks.first())
        }
    }

    private fun realAdapterPosition(videoId: String): Int {
        val realIndex = playableWorks.indexOfFirst { it.id == videoId }
        if (realIndex < 0) return 0
        return if (noMore && playableWorks.size > 1) realIndex + 1 else realIndex
    }

    private fun rebuildFeed(currentId: String?) {
        val list = buildFeedItems()
        feedAdapter.replace(list)
        if (list.isEmpty()) return
        val desiredId = currentId ?: playableWorks.firstOrNull()?.id.orEmpty()
        val position = realAdapterPosition(desiredId).coerceIn(0, list.lastIndex)
        pager.setCurrentItem(position, false)
        feedAdapter.setActive(position)
    }

    private fun rebuildFeedKeepingCurrent() {
        val currentId = feedAdapter.items.getOrNull(pager.currentItem)?.id
        rebuildFeed(currentId)
    }

    private fun normalizeLoopEdge() {
        if (!inFeed || !noMore || playableWorks.size <= 1 || adjustingLoopEdge || exiting) return
        val current = pager.currentItem
        val lastSentinel = feedAdapter.itemCount - 1
        val target = when (current) {
            0 -> playableWorks.size
            lastSentinel -> 1
            else -> return
        }
        adjustingLoopEdge = true
        pager.setCurrentItem(target, false)
        feedAdapter.setActive(target)
        adjustingLoopEdge = false
    }

    private fun openWork(item: VideoItem) {
        if (exiting) return
        val realIndex = playableWorks.indexOfFirst { it.id == item.id }
        if (realIndex < 0) return
        inFeed = true
        listPage.visibility = View.GONE
        feedPage.visibility = View.VISIBLE
        val feed = buildFeedItems()
        feedAdapter.replace(feed)
        val index = if (noMore && playableWorks.size > 1) realIndex + 1 else realIndex
        pager.setCurrentItem(index, false)
        feedAdapter.setActive(index)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) feedAdapter.resumeActive()
    }

    /** 评论区里点了别的用户：另开一个作者页；点的就是当前作者就回到作品列表。 */
    private fun openAnotherAuthor(target: IwaraAuthor) {
        if (exiting) return
        val current = author
        val same = current != null && (
            (target.id.isNotBlank() && target.id == current.id) ||
                (target.username.isNotBlank() && target.username == current.username)
        )
        if (same) { comments.close(); showList(); return }
        feedAdapter.pauseAll()
        startActivity(Intent(this, AuthorActivity::class.java).apply {
            putExtra(EXTRA_ID, target.id)
            putExtra(EXTRA_NAME, target.name)
            putExtra(EXTRA_USERNAME, target.username)
        })
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
        val data = Intent().putExtra(EXTRA_RETURN_FROM_AUTHOR, true)
        // 把这一趟的关注状态带回去，调用方的关注按钮不用再查一次接口。
        author?.let { loaded ->
            data.putExtra(EXTRA_ID, loaded.id)
                .putExtra(EXTRA_USERNAME, loaded.username)
                .putExtra(EXTRA_FOLLOWING, loaded.following)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun nextWork(position: Int) {
        if (exiting) return
        if (position + 1 < feedAdapter.itemCount) pager.setCurrentItem(position + 1, true)
        else if (!noMore) loadNextPage()
        else if (feedAdapter.itemCount > 0) pager.setCurrentItem(if (playableWorks.size > 1) 1 else 0, true)
    }

    /** 分享作者名片：资料还没回来时先用进入本页时带过来的名字和用户名。 */
    private fun shareAuthor() {
        val loaded = author
        val fallbackName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val fallbackUsername = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val card = loaded?.takeIf { it.username.isNotBlank() || it.name.isNotBlank() }
            ?: IwaraAuthor(intent.getStringExtra(EXTRA_ID).orEmpty(), fallbackName, fallbackUsername)
        VideoShare.shareAuthor(this, card)
    }

    private fun toggleFollow() {
        val a = author ?: return
        if (!api.isLoggedIn()) { Toast.makeText(this, "请先登录 Iwara", Toast.LENGTH_SHORT).show(); return }
        followButton.isEnabled = false
        val desired = !a.following
        api.followUser(a.id, desired) { result ->
            if (isFinishing || isDestroyed || exiting) return@followUser
            runOnUiThread {
                if (isFinishing || isDestroyed || exiting) return@runOnUiThread
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
            if (isFinishing || isDestroyed || exiting) return@setFriend
            runOnUiThread {
                if (isFinishing || isDestroyed || exiting) return@runOnUiThread
                friendButton.isEnabled = true
                result.onSuccess {
                    api.getFriendStatus(a.id) { refreshed ->
                        if (isFinishing || isDestroyed || exiting) return@getFriendStatus
                        runOnUiThread {
                            if (isFinishing || isDestroyed || exiting) return@runOnUiThread
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
        works.clear(); playableWorks.clear(); listAdapter.notifyDataSetChanged(); feedAdapter.replace(emptyList())
        page = 0; noMore = false; loadingPage = false; loadNextPage()
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
        DownloadPrompt.confirmIfDuplicate(this, history, item, source) { startDownload(item, source) }
    }

    private fun startDownload(item: VideoItem, source: VideoSource) {
        try {
            val fileName = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "_${source.name}.mp4"
            val request = DownloadManager.Request(Uri.parse(source.url))
                .setTitle(item.title).setMimeType("video/mp4")
                .addRequestHeader("Referer", "https://www.iwara.tv/")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "IwaraFlow/$fileName")
            val downloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            history.recordDownload(item, source.name, downloadId)
            Toast.makeText(this, "已加入下载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun openAuthorProfile(view: View) { if (inFeed) showList() }

    override fun onResume() {
        super.onResume()
        leavingForShare = false
        if (inFeed && !exiting) feedAdapter.resumeActive()
    }

    override fun onPause() {
        // 半透明界面盖上来只会走到 onPause，这时释放播放器画面会变黑。
        // 小窗里更不能停。
        if (!isInPictureInPictureMode) feedAdapter.suspendPlayback()
        super.onPause()
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) feedAdapter.pauseAll()
        super.onStop()
    }

    // ---------------------------------------------------------------- 小窗 / 分享

    /** 正在把链接交给别的应用：这段时间不要因为 onUserLeaveHint 自动进小窗。 */
    private var leavingForShare = false

    private fun shareVideo(item: VideoItem) {
        if (item.id.isBlank()) { Toast.makeText(this, "这个视频没有可分享的链接", Toast.LENGTH_SHORT).show(); return }
        SharePanel.show(this, VideoShare.shareText(item), item.title, "分享视频链接") { intent ->
            leavingForShare = true
            runCatching { startActivity(intent) }.onFailure {
                leavingForShare = false
                Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pipParams(): android.app.PictureInPictureParams =
        android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()

    private fun enterPip() {
        if (!inFeed || exiting) return
        comments.close()
        // 先登记再进：系统把本页挪进独立任务时主页会被顶上来，那一刻它就得知道有小窗。
        PipRegistry.enter(this)
        val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        if (!entered) {
            PipRegistry.leave(this)
            Toast.makeText(this, "画中画启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (leavingForShare || exiting) return
        if (inFeed && prefs.autoPip && feedAdapter.isActivePlaying() && !isInPictureInPictureMode) enterPip()
    }

    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) { comments.close(); PipRegistry.enter(this) } else PipRegistry.leave(this)
        findViewById<View>(R.id.feedBack).visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        feedAdapter.setPipMode(isInPictureInPictureMode)
        // 退出小窗时页面停在后台（不是被展开成全屏）：用户把小窗关掉了。onStop 那会儿还算在
        // 小窗里没停播，这里必须停。页面本身不结束：下次打开应用时主页会把它拉回前台，
        // 用户回到的还是刚才小窗里那条视频。
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            feedAdapter.pauseAll()
        }
    }

    override fun onDestroy() {
        PipRegistry.leave(this)
        if (::comments.isInitialized) comments.release()
        feedAdapter.releaseAll()
        gate.close()
        mediaCache.close()
        api.close()
        history.close()
        super.onDestroy()
    }

    /** 作者页回传的关注状态。 */
    data class FollowResult(val authorId: String, val username: String, val following: Boolean)

    companion object {
        /** 简介折叠时显示几行。 */
        const val DESCRIPTION_COLLAPSED_LINES = 3
        const val EXTRA_ID = "author_id"
        const val EXTRA_NAME = "author_name"
        const val EXTRA_USERNAME = "author_username"
        const val EXTRA_FOLLOWING = "author_following"
        const val EXTRA_RETURN_FROM_AUTHOR = "return_from_author"

        fun readFollowResult(data: Intent?): FollowResult? {
            data ?: return null
            if (!data.hasExtra(EXTRA_FOLLOWING)) return null
            val id = data.getStringExtra(EXTRA_ID).orEmpty()
            val username = data.getStringExtra(EXTRA_USERNAME).orEmpty()
            if (id.isBlank() && username.isBlank()) return null
            return FollowResult(id, username, data.getBooleanExtra(EXTRA_FOLLOWING, false))
        }
    }
}
