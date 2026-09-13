package com.ling.iwaraflow

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 浏览历史 / 我的收藏 / 已下载。列表页点一条进入竖滑播放页：上滑是列表里的下一条，
 * 滑到底提示没有更多；已下载的视频直接放本地文件，其它照常在线播放。
 * 播放页和作者作品页是同一套：评论、简介、小窗、分享、下载都在。
 */
class SavedVideosActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var prefs: AppPrefs
    private lateinit var mediaCache: MediaPreloadCache
    private lateinit var listPage: View
    private lateinit var feedPage: View
    private lateinit var listView: RecyclerView
    private lateinit var pager: ViewPager2
    private lateinit var adapter: SavedVideoListAdapter
    private lateinit var feedAdapter: VideoAdapter
    private lateinit var comments: CommentsHost
    private val items = mutableListOf<VideoItem>()
    private val downloads = mutableMapOf<String, DownloadLibrary.Entry>()
    private var closed = false
    private var inFeed = false
    private var kind = KIND_HISTORY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_videos)

        api = IwaraApi(this)
        history = HistoryStore(this)
        prefs = AppPrefs(this)
        mediaCache = MediaPreloadCache(this)
        listPage = findViewById(R.id.savedListPage)
        feedPage = findViewById(R.id.savedFeedPage)
        listView = findViewById(R.id.savedVideos)
        pager = findViewById(R.id.savedPager)
        adapter = SavedVideoListAdapter(items, ::openVideo) { item -> downloads[item.id]?.note() }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<View>(R.id.savedBack).setOnClickListener { finish() }
        findViewById<View>(R.id.savedFeedBack).setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_HISTORY
        findViewById<TextView>(R.id.savedTitle).text = pageTitle()
        findViewById<TextView>(R.id.savedFeedBack).text = "‹ ${pageTitle()}"

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

        feedAdapter = VideoAdapter(
            api = api,
            history = history,
            prefs = prefs,
            mediaCache = mediaCache,
            onDownload = ::download,
            onEnterPip = ::enterPip,
            onShare = ::shareVideo,
            onEnded = ::nextVideo,
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
            topBarHeight = { (60 * density).toInt() },
            gapPx = (20 * density).toInt(),
            onNeedLogin = { Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_SHORT).show() },
            onOpenAuthor = ::openAuthor,
            onCommentPosted = { history.recordInteraction(it, "comment", 1.5) },
            onDislike = { item ->
                history.recordInteraction(item, "dislike", -2.0)
                history.markSeen(item.id)
                Toast.makeText(this, "已减少此类推荐", Toast.LENGTH_SHORT).show()
            }
        )
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                comments.close()
                feedAdapter.setActive(position)
            }
        })
        // 列表是本地的、一次全在，所以滑到底就是真的到底了。
        EndOfFeedHint.install(pager) { false }

        enrichMissingVideoDetails()
    }

    private fun pageTitle(): String = when (kind) {
        KIND_FAVORITES -> "我的收藏"
        KIND_DOWNLOADS -> "已下载"
        else -> "浏览历史"
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
                        // 播放页里已经放着这条的话，把作者 id、点赞数这些补进去。
                        feedAdapter.items.firstOrNull { it.id == original.id }?.let { shown ->
                            if (shown.authorId.isBlank()) shown.authorId = fresh.authorId
                            if (shown.authorUsername.isBlank()) shown.authorUsername = fresh.authorUsername
                            if (shown.description.isBlank()) shown.description = fresh.description
                            shown.likes = maxOf(shown.likes, fresh.likes)
                            shown.liked = fresh.liked
                            shown.authorFollowing = fresh.authorFollowing
                            feedAdapter.refreshItem(shown.id)
                        }
                    }
                }
            }
        }
    }

    /**
     * 进入竖滑播放页，从点中的那条开始，整份列表都能上下滑。
     * 下好的视频直接放本地那一份——下载就是为了这个；还没下完 / 文件不在了的照常在线播。
     */
    private fun openVideo(item: VideoItem) {
        if (closed) return
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val feed = items.map { entry ->
            val local = downloads[entry.id]?.takeIf { it.state == DownloadLibrary.State.READY }?.localUri?.toString()
            entry.copy().also { copy ->
                copy.localFavorite = history.isLocalFavorite(entry.id)
                if (local != null) {
                    copy.sources = listOf(VideoSource(MainActivityV3.LOCAL_SOURCE_NAME, local, 10_000))
                    copy.streamUrl = local
                    copy.selectedQuality = MainActivityV3.LOCAL_SOURCE_NAME
                }
            }
        }
        inFeed = true
        listPage.visibility = View.GONE
        feedPage.visibility = View.VISIBLE
        feedAdapter.replace(feed)
        pager.setCurrentItem(index, false)
        feedAdapter.setActive(index)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) feedAdapter.resumeActive()
    }

    private fun showList() {
        if (!inFeed || closed) return
        comments.close()
        feedAdapter.pauseAll()
        inFeed = false
        feedPage.visibility = View.GONE
        listPage.visibility = View.VISIBLE
    }

    private fun handleBack() {
        if (closed) return
        if (comments.isOpen) comments.close() else if (inFeed) showList() else finish()
    }

    /** 一条播完自动下一条；最后一条播完停在这里并提示。 */
    private fun nextVideo(position: Int) {
        if (closed) return
        if (position + 1 < feedAdapter.itemCount) pager.setCurrentItem(position + 1, true)
        else EndOfFeedHint.show(this)
    }

    /** 卡片上的作者名（item_video.xml 里的 onClick）。 */
    @Suppress("UNUSED_PARAMETER")
    fun openAuthorProfile(view: View) {
        if (!inFeed) return
        val item = feedAdapter.items.getOrNull(pager.currentItem) ?: return
        if (item.authorId.isBlank() && item.authorUsername.isBlank()) {
            Toast.makeText(this, "正在读取作者资料…", Toast.LENGTH_SHORT).show()
            api.getVideo(item.id) { result ->
                runOnUiThread {
                    if (closed) return@runOnUiThread
                    result.onSuccess { detail ->
                        item.authorId = detail.authorId
                        item.authorUsername = detail.authorUsername
                        history.updateDownloadDetail(item.id, detail.authorId, detail.authorUsername, detail.description)
                        openAuthor(IwaraAuthor(detail.authorId, item.author, detail.authorUsername))
                    }.onFailure {
                        Toast.makeText(this, "读取作者资料失败：${IwaraApi.explainError(it)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        openAuthor(IwaraAuthor(item.authorId, item.author, item.authorUsername))
    }

    private fun openAuthor(target: IwaraAuthor) {
        if (closed) return
        if (target.id.isBlank() && target.username.isBlank()) {
            Toast.makeText(this, "该视频没有作者资料", Toast.LENGTH_SHORT).show(); return
        }
        feedAdapter.pauseAll()
        startActivity(Intent(this, AuthorActivity::class.java).apply {
            putExtra(AuthorActivity.EXTRA_ID, target.id)
            putExtra(AuthorActivity.EXTRA_NAME, target.name)
            putExtra(AuthorActivity.EXTRA_USERNAME, target.username)
        })
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

    // ---------------------------------------------------------------- 生命周期 / 小窗 / 分享

    private var leavingForShare = false

    override fun onResume() {
        super.onResume()
        leavingForShare = false
        if (inFeed && !closed) feedAdapter.resumeActive()
    }

    override fun onPause() {
        if (!isInPictureInPictureMode) feedAdapter.suspendPlayback()
        super.onPause()
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) feedAdapter.pauseAll()
        super.onStop()
    }

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
        if (!inFeed || closed) return
        comments.close()
        PipRegistry.enter(this)
        val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        if (!entered) {
            PipRegistry.leave(this)
            Toast.makeText(this, "画中画启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (leavingForShare || closed) return
        if (inFeed && prefs.autoPip && feedAdapter.isActivePlaying() && !isInPictureInPictureMode) enterPip()
    }

    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        findViewById<View>(R.id.savedFeedBack).visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        feedAdapter.setPipMode(isInPictureInPictureMode)
        when {
            isInPictureInPictureMode -> { comments.close(); PipRegistry.enter(this) }
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> PipRegistry.leave(this)
            else -> { feedAdapter.pauseAll(); NavigationDiagnostics.note(this, "${pageTitle()}小窗被关闭：停播，页面留在后台") }
        }
    }

    override fun onDestroy() {
        closed = true
        PipRegistry.leave(this)
        if (::comments.isInitialized) comments.release()
        if (::feedAdapter.isInitialized) feedAdapter.releaseAll()
        if (::mediaCache.isInitialized) mediaCache.close()
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
