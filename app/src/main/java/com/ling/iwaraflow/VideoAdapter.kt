package com.ling.iwaraflow

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import kotlin.math.abs

class VideoAdapter(
    private val api: IwaraApi,
    private val history: HistoryStore,
    private val prefs: AppPrefs,
    private val mediaCache: MediaPreloadCache,
    private val onDownload: (VideoItem, VideoSource) -> Unit,
    private val onEnterPip: () -> Unit,
    private val onEnded: (Int) -> Unit,
    private val onNeedLogin: () -> Unit,
    /**
     * 交给页面去分享。带播放器和小窗的页面要自己管这件事：分享面板弹出来的时候
     * 不能自动缩进小窗，回来之后也要接上播放。不传就按老样子直接拉起选择器。
     */
    private val onShare: ((VideoItem) -> Unit)? = null,
    /** 点了评论按钮。不传就不显示评论按钮（没有评论面板的页面）。 */
    private val onComments: ((VideoItem) -> Unit)? = null,
    /** 点了视频标题：打开简介。不传标题就只是文字。 */
    private val onInfo: ((VideoItem) -> Unit)? = null,
    /** 记下了一条明确的行为（like / favorite / comments / share）：页面可据此重排剩余候选。 */
    private val onSignal: ((VideoItem, String) -> Unit)? = null,
    /** 长按画面上半区选了“不感兴趣”并已写进画像：页面可以跳过这条、重排候选。 */
    private val onDisliked: ((VideoItem, DislikeSheet.Kind) -> Unit)? = null,
    /** 点了全屏 / 退出全屏。页面负责转屏和收起自己的顶栏，不传就没有全屏按钮。 */
    private val onFullscreen: ((VideoItem, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    val items = mutableListOf<VideoItem>()
    private val holders = mutableSetOf<Holder>()
    private val preloadHandler = Handler(Looper.getMainLooper())
    private var preloadTask: Runnable? = null
    private var activePosition = RecyclerView.NO_POSITION
    private var pipMode = false
    private var fullscreenMode = false
    @Volatile private var released = false
    // Selecting/binding a card is independent from granting a visible page playback.
    @Volatile private var playbackEnabled = false

    /**
     * 这批卡片属于哪一轮曝光会话，页面写进来（见 [HistoryStore.recordImpression]）。
     * 留空表示这个页面不记曝光——作者页、搜索页、收藏和历史都不该算进推荐质量。
     */
    var impressionSession: String = ""

    fun replace(newItems: List<VideoItem>) {
        if (released) return
        cancelIdlePreload()
        holders.toList().forEach { it.release() }
        items.clear()
        items.addAll(newItems)
        activePosition = if (items.isEmpty()) RecyclerView.NO_POSITION else 0
        notifyDataSetChanged()
    }

    fun append(newItems: List<VideoItem>) {
        if (released) return
        val existing = items.asSequence().map { it.id }.toHashSet()
        val unique = newItems.filter { existing.add(it.id) }
        if (unique.isEmpty()) return
        val start = items.size
        items.addAll(unique)
        notifyItemRangeInserted(start, unique.size)
    }

    fun releaseAll() {
        if (released) return
        playbackEnabled = false
        released = true
        cancelIdlePreload()
        holders.toList().forEach { it.release() }
        holders.clear()
        unregister(this)
    }

    fun setActive(position: Int) {
        if (released) return
        if (position != activePosition && playbackEnabled) {
            // 翻页离开的那张卡片：在停播之前先记下“看了多久 / 是不是很快划走”。
            //
            // 只有**页面还在前台、用户真的划到了下一条**才算划走。关掉应用、切到后台、
            // 进小窗、换榜单重新加载、恢复上次会话都会把 playbackEnabled 关掉或者先
            // replace 一次，那些位置变化只记正面信号，绝不能被判成“不感兴趣”。
            holders.toList().forEach { if (it.bindingAdapterPosition == activePosition) it.noteSwipedAway() }
        }
        activePosition = position
        if (!playbackEnabled) return
        claimPlaybackOwnership(this)
        holders.toList().forEach { holder ->
            holder.setActive(activePosition in items.indices && holder.bindingAdapterPosition == activePosition)
        }
        scheduleIdlePreload(position)
    }

    /** 彻底停下并释放播放器：真的切到后台时用，解码器要还给系统。 */
    fun pauseAll() {
        playbackEnabled = false
        cancelIdlePreload()
        holders.toList().forEach { it.setActive(false) }
    }

    /**
     * 只暂停，不释放播放器。
     *
     * 系统分享面板这类半透明界面盖上来时，本页只会走到 onPause（还看得见，不会走 onStop）。
     * 这时如果按 [pauseAll] 把播放器释放掉，`playerView` 的 surface 一空画面就是纯黑的，
     * 而面板根本没盖住上半屏——看上去就是“视频莫名其妙黑了”。留着播放器，画面就停在最后一帧。
     */
    fun suspendPlayback() {
        playbackEnabled = false
        cancelIdlePreload()
        holders.toList().forEach { it.suspend() }
    }

    fun savePlaybackPosition() {
        holders.toList().forEach { it.savePlaybackPosition() }
    }

    fun resumeActive() {
        if (released) return
        playbackEnabled = true
        claimPlaybackOwnership(this)
        holders.toList().forEach { holder ->
            holder.setActive(activePosition in items.indices && holder.bindingAdapterPosition == activePosition)
        }
        scheduleIdlePreload(activePosition)
    }

    /**
     * 只影响显示的设置改了，推给已经绑好的卡片。
     *
     * 走这里而不是 notifyDataSetChanged：重新绑定会先 release 再重建播放器，
     * 正在看的视频会顿一下、还得重新缓冲——只是换个图标显不显示，不值当。
     */
    fun applyDisplayPrefs() {
        holders.toList().forEach { it.applyDisplayPrefs() }
    }

    /** 某条视频的数据在外面被补全了（点赞数、作者、关注状态），刷新已绑定卡片的显示。 */
    fun refreshItem(videoId: String) {
        holders.toList().forEach { it.refreshIfBound(videoId) }
    }

    /**
     * 评论面板开着时画面要让出的上下留白（像素）。上面留顶栏，下面留面板高度，
     * 画面缩进中间那块，面板不遮画面。两个都传 0 就是关掉，卡片回到各自的默认摆法
     * （横屏略偏上、竖屏铺满）。
     */
    fun setVideoInsets(top: Int, bottom: Int) {
        topInset = top.coerceAtLeast(0)
        bottomInset = bottom.coerceAtLeast(0)
        holders.toList().forEach { it.applyVideoInsets() }
    }

    private var topInset = 0
    private var bottomInset = 0

    /** 上一条被快速划走的视频；连着划走两条同类的（同作者 / 有共同标签）就加压共同的那一维。 */
    private var lastSkipped: VideoItem? = null

    private fun noteSkipStreak(item: VideoItem) {
        val last = lastSkipped
        lastSkipped = item
        if (last == null || last.id == item.id) return
        val sameAuthor = item.author.isNotBlank() && last.author.equals(item.author, ignoreCase = true)
        val shared = item.tags.filter { tag -> last.tags.any { it.equals(tag, ignoreCase = true) } }
        if (!sameAuthor && shared.isEmpty()) return
        history.recordInteractionAsync(
            VideoItem(item.id, item.title, if (sameAuthor) item.author else "", shared, 0, authorId = if (sameAuthor) item.authorId else ""),
            "skip_streak", WatchInteractionTracker.SKIP_STREAK_WEIGHT
        )
    }

    /** 当前正在播的那一条；小窗里的分享按钮要靠它知道分享谁。 */
    fun activeItem(): VideoItem? = items.getOrNull(activePosition)

    /** 当前这条视频的宽高比（宽 / 高）；播放器还没解出画面尺寸时为 null。 */
    fun activeVideoAspect(): Float? = holders.firstOrNull {
        it.bindingAdapterPosition == activePosition
    }?.videoAspect()

    fun isActivePlaying(): Boolean = holders.any {
        it.bindingAdapterPosition == activePosition && it.isPlaying()
    }

    fun setPipMode(enabled: Boolean) {
        pipMode = enabled
        holders.forEach {
            it.setChromeVisible(!enabled)
            // 小窗里画面要铺满整个小窗，任何留白都不能有。
            it.applyVideoInsets()
        }
    }

    /**
     * 全屏：卡片上的信息栏和操作栏全部收起，画面铺满（横屏视频不再上移）。
     * 暂停时进度条那一套照常出现，见 [PauseSeekBar]。
     */
    fun setFullscreen(enabled: Boolean) {
        if (fullscreenMode == enabled) return
        fullscreenMode = enabled
        holders.toList().forEach {
            it.setChromeVisible(!enabled)
            it.applyVideoInsets()
            // 信息栏刚从 GONE 回到 VISIBLE，得重新量一次，进度条才知道该停在哪一行。
            it.itemView.requestLayout()
        }
        // 退出全屏时接着播：退出按钮只有暂停时才露出来，用户是为了够到它才按的暂停。
        // 不接着播的话会停在“只有进度条、没有信息栏”的样子，像是卡住了。
        if (!enabled) holders.toList().forEach { holder ->
            if (holder.bindingAdapterPosition == activePosition) holder.resumePlayback()
        }
    }

    val isFullscreen: Boolean get() = fullscreenMode

    private fun cancelIdlePreload() {
        preloadTask?.let { preloadHandler.removeCallbacks(it) }
        preloadTask = null
    }

    private fun scheduleIdlePreload(position: Int) {
        cancelIdlePreload()
        if (!playbackEnabled || released || position !in items.indices) return
        val task = Runnable {
            if (!playbackEnabled || released || position != activePosition) return@Runnable
            val activeReady = holders.any {
                it.bindingAdapterPosition == position && it.readyForIdlePreload()
            }
            if (!activeReady) return@Runnable
            // 弱网/计量网络只预缓存下一条，别和正在播的视频抢带宽。
            val ahead = NetworkProfile.prefetchCount(itemContext ?: return@Runnable)
            (1..ahead).map { position + it }.forEach { index ->
                val item = items.getOrNull(index) ?: return@forEach
                val knownUrl = item.streamUrl
                if (!knownUrl.isNullOrBlank()) {
                    mediaCache.prefetch(knownUrl)
                } else {
                    api.resolveSources(item.id) { result ->
                        result.onSuccess { sources ->
                            if (released || !playbackEnabled) return@onSuccess
                            item.sources = sources
                            val source = api.chooseSource(sources, item.selectedQuality ?: prefs.defaultQuality)
                            item.streamUrl = source?.url
                            source?.url?.let(mediaCache::prefetch)
                        }
                    }
                }
            }
        }
        preloadTask = task
        preloadHandler.postDelayed(task, 1800L)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return Holder(view).also { holders += it }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.release()
        holders -= holder
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (released) return
        // RecyclerView reuses holders after onViewRecycled; onCreateViewHolder is not called again.
        holders += holder
        holder.bind(items[position])
        holder.setChromeVisible(!pipMode && !fullscreenMode)
        holder.setActive(!released && playbackEnabled && position == activePosition)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val playerView = view.findViewById<PlayerView>(R.id.playerView)
        private val infoPanel = view.findViewById<View>(R.id.infoPanel)
        private val actionPanel = view.findViewById<View>(R.id.actionPanel)
        private val author = view.findViewById<TextView>(R.id.author)
        private val title = view.findViewById<TextView>(R.id.title)
        private val tags = view.findViewById<TextView>(R.id.tags)
        private val like = view.findViewById<ImageView>(R.id.like)
        private val likeCount = view.findViewById<TextView>(R.id.likeCount)
        private val favorite = view.findViewById<ImageView>(R.id.favorite)
        private val quality = view.findViewById<TextView>(R.id.quality)
        private val download = view.findViewById<TextView>(R.id.download)
        private val fullscreen = view.findViewById<TextView>(R.id.fullscreen)
        private val pausePip = view.findViewById<View>(R.id.pausePip)
        private val pauseFullscreenExit = view.findViewById<View>(R.id.pauseFullscreenExit)
        private val seekPreview = view.findViewById<TextView>(R.id.seekPreview)
        private val share = view.findViewById<TextView>(R.id.share)
        private val comments = view.findViewById<TextView>(R.id.comments)
        private val reactionBurst = view.findViewById<ReactionBurstView>(R.id.reactionBurst)
        private val speedIndicator = view.findViewById<TextView>(R.id.speedIndicator)
        private val pauseIndicator = view.findViewById<PauseIndicatorView>(R.id.pauseIndicator)
        private val pauseSeekBar = view.findViewById<PauseSeekBar>(R.id.pauseSeekBar)
        private val skipBack = view.findViewById<View>(R.id.skipBack)
        private val skipForward = view.findViewById<View>(R.id.skipForward)
        private val skipBackLabel = view.findViewById<TextView>(R.id.skipBackLabel)
        private val skipForwardLabel = view.findViewById<TextView>(R.id.skipForwardLabel)

        private var player: ExoPlayer? = null
        private var bound: VideoItem? = null
        /** 当前视频是横屏的吗。播放器解出画面尺寸后才知道，绑定时先当成不是。 */
        private var landscape = false
        /** 画面宽高比（宽 / 高），0 表示还不知道。 */
        private var aspect = 0f
        private var active = false
        private var generation = 0
        private var likeBusy = false
        private var recoveryAttempts = 0
        private val tapHandler = Handler(Looper.getMainLooper())
        private val watchdogHandler = Handler(Looper.getMainLooper())
        private var lastWatchdogPosition = -1L
        private var stalledChecks = 0
        private var pendingSingleTap: Runnable? = null
        private var lastTap = 0L
        /** 播放控件是被点出来的（没暂停也显示）。 */
        private var controlsPinned = false

        private val touchSlop = ViewConfiguration.get(itemView.context).scaledTouchSlop
        /** 加速进行中允许手指挪动的距离：约 48dp，比点按阈值宽得多。 */
        private val boostSlop = touchSlop * 6
        private var downX = 0f
        private var downY = 0f
        private var touchMoved = false
        private var speedBoosting = false

        // ------------------------------------------------------- 按住左右滑动调进度

        /** 正在用手指拖进度。 */
        private var seeking = false
        /** 按下那一刻的播放位置，滑动都是相对它算的。 */
        private var seekFrom = 0L
        /** 上一次真正发出去的跳转位置，避免每个触摸事件都让播放器重新定位。 */
        private var lastSeekTarget = -1L

        private fun startSeek() {
            val p = player ?: return
            val duration = p.duration
            if (duration <= 0L) return
            seeking = true
            seekFrom = p.currentPosition.coerceIn(0L, duration)
            lastSeekTarget = -1L
            // 这根手指归本卡片管，翻页控件不许中途抢走。
            itemView.parent?.requestDisallowInterceptTouchEvent(true)
            // 拖动期间跳到最近的关键帧：画面跟得上手指，松手再精确定位。
            p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
            pendingSingleTap = null
            lastTap = 0L
        }

        private fun updateSeek(dx: Float) {
            val p = player ?: return
            val duration = p.duration
            if (duration <= 0L) return
            val width = itemView.width.takeIf { it > 0 } ?: return
            val target = seekTargetFor(seekFrom, dx, width, duration)
            if (lastSeekTarget < 0L || abs(target - lastSeekTarget) >= SEEK_STEP_MS) {
                lastSeekTarget = target
                p.seekTo(target)
            }
            val delta = target - seekFrom
            if (delta < 0L) watch.noteRewound()
            val sign = if (delta >= 0) "+" else "-"
            seekPreview.text = "${formatTime(target)} / ${formatTime(duration)}   $sign${formatTime(abs(delta))}"
            if (seekPreview.visibility != View.VISIBLE) seekPreview.visibility = View.VISIBLE
        }

        private fun finishSeek() {
            seeking = false
            seekPreview.visibility = View.GONE
            val p = player ?: return
            p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
            // 松手时按最后停的位置精确定位一次，拖动期间用的是最近的关键帧。
            if (lastSeekTarget >= 0L) p.seekTo(lastSeekTarget)
            lastSeekTarget = -1L
        }

        private fun formatTime(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
        }

        init {
            // 卡片尺寸一变（进出小窗、分屏）就按新高度重算留白，别拿整屏的数字硬套。
            view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) applyVideoInsets()
            }
        }

        /**
         * 长按：画面分上下两半——上半区弹“不感兴趣”面板，下半区临时 2× 加速。
         * 小窗里两样都不做。
         */
        private val holdToSpeed = Runnable {
            if (!active || touchMoved || pipMode) return@Runnable
            if (downY < itemView.height / 2f) {
                // 当作已经“移动”了：松手时不能再触发单击（暂停）。
                touchMoved = true
                pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                pendingSingleTap = null
                lastTap = 0L
                bound?.let { item -> DislikeSheet.show(itemView.context, item, history) { target, kind -> onDisliked?.invoke(target, kind) } }
                return@Runnable
            }
            player?.let { p ->
                if (p.playbackState != Player.STATE_IDLE) {
                    p.setPlaybackSpeed(2f)
                    speedBoosting = true
                    speedIndicator.visibility = View.VISIBLE
                    // 加速已经开始：这根手指归本卡片管，翻页控件不许再抢走
                    // （抢走就是一个 CANCEL，加速立刻停）。
                    itemView.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }

        // ---------------------------------------------------------------- 观看行为

        /**
         * 这条视频看得怎么样，全部记在 [WatchInteractionTracker] 里——播了多久、
         * 有没有播完、有没有往回拖、报没报错，以及离开时怎么折算成画像信号和曝光结果。
         * 拆出去之后这一块不碰任何 View，可以脱离播放器单独测。
         */
        private val watch = WatchInteractionTracker(
            history,
            onSkipStreak = { item -> noteSkipStreak(item) },
            onWatched = { lastSkipped = null }
        )

        private fun resetWatchSignals() = watch.reset()

        private fun notePlaying(isPlaying: Boolean) = watch.notePlaying(isPlaying)

        /** 离开这条视频：把观看行为交给 [watch] 记账，见 [WatchInteractionTracker.report]。 */
        private fun recordWatchSignals(swipedAway: Boolean) {
            val item = bound ?: return
            val p = player
            watch.report(
                item = item,
                durationMs = p?.duration ?: 0L,
                playing = p?.isPlaying == true,
                swipedAway = swipedAway,
                impressionSession = impressionSession
            )
        }

        /** 点赞 / 收藏也记进这条曝光：来源好不好用，强正反馈是最直接的证据。 */
        private fun noteReaction(item: VideoItem, liked: Boolean = false, favorited: Boolean = false) {
            val session = impressionSession
            if (session.isBlank()) return
            history.post { history.noteImpressionReaction(session, item.id, liked, favorited) }
        }

        /** 用户把这张卡片划走了（翻页），在停播之前先记下行为。 */
        fun noteSwipedAway() = recordWatchSignals(swipedAway = true)

        fun bind(item: VideoItem) {
            release()
            // 卡片被回收复用：上一条点出来的控件不要跟着带到下一条。
            setControlsPinned(false)
            bound = item
            recoveryAttempts = 0
            landscape = false
            aspect = 0f
            applyVideoInsets()
            comments.visibility = if (onComments == null) View.GONE else View.VISIBLE
            comments.setOnClickListener {
                // 打开评论区也是一点兴趣，比点赞轻得多。
                history.recordInteractionAsync(item, "comments", 0.3)
                onSignal?.invoke(item, "comments")
                onComments?.invoke(item)
            }
            // 标题和标签行都能打开简介。
            val openInfo = onInfo?.let { open -> View.OnClickListener { open(item) } }
            title.setOnClickListener(openInfo)
            title.isClickable = onInfo != null
            tags.setOnClickListener(openInfo)
            tags.isClickable = onInfo != null
            resetWatchSignals()
            item.localFavorite = history.isLocalFavorite(item.id)
            applyDisplayPrefs()
            skipBack.setOnClickListener { skipBy(-prefs.skipSeconds * 1000L) }
            skipForward.setOnClickListener { skipBy(prefs.skipSeconds * 1000L) }
            author.text = "@${item.author}"
            title.text = item.title
            tags.text = item.tags.take(8).joinToString("  ") { "#$it" }
            updateLikeUi(item)
            quality.text = displayQuality(item)

            like.setOnClickListener {
                val desired = !item.liked
                // 动画播在按钮自己身上，而不是屏幕正中央。
                toggleRemoteLike(item, desired) {
                    reactionBurst.playOn(like, ReactionBurstView.Kind.LIKE)
                }
            }
            favorite.setOnClickListener {
                val desired = !item.localFavorite
                history.setLocalFavoriteAsync(item, desired)
                if (desired) {
                    history.recordInteractionAsync(item, "favorite", 1.4)
                    noteReaction(item, favorited = true)
                    onSignal?.invoke(item, "favorite")
                    reactionBurst.playOn(favorite, ReactionBurstView.Kind.FAVORITE)
                } else {
                    // 取消收藏：当初的兴趣不算数了，往回压一点。
                    history.recordInteractionAsync(item, "unfavorite", -0.9)
                    onSignal?.invoke(item, "unfavorite")
                }
                // 收藏有动画、取消有图标变化，不用再弹一层提示挡着视频。
                updateLikeUi(item)
            }
            quality.setOnClickListener { showQualityChooser(item, false) }
            download.setOnClickListener { showQualityChooser(item, true) }
            // 操作栏是全屏；小窗和退出全屏挪到了暂停时才出现的那一行里。
            fullscreen.visibility = if (onFullscreen == null) View.GONE else View.VISIBLE
            fullscreen.setOnClickListener { onFullscreen?.invoke(item, !fullscreenMode) }
            pausePip.setOnClickListener { onEnterPip() }
            pauseFullscreenExit.setOnClickListener { onFullscreen?.invoke(item, false) }
            share.setOnClickListener {
                // 愿意分享给别人，是比点赞还强的兴趣信号。
                history.recordInteractionAsync(item, "share", 1.0)
                onSignal?.invoke(item, "share")
                onShare?.invoke(item) ?: VideoShare.share(itemView.context, item)
            }

            itemView.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTap <= 320L) {
                    pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                    pendingSingleTap = null
                    lastTap = 0L
                    // 双击点赞：动画播在手指按下的位置。
                    reactionBurst.playAt(downX, downY, ReactionBurstView.Kind.LIKE)
                    if (!item.liked) toggleRemoteLike(item, true)
                } else {
                    lastTap = now
                    val action = Runnable {
                        if (System.currentTimeMillis() - lastTap >= 280L) {
                            // 设置里可以把「点一下画面暂停播放」关掉：那时点一下只是在
                            // 信息栏和播放控件之间切换，视频照常播。
                            if (prefs.tapToPause) {
                                setControlsPinned(false)
                                player?.let { p -> if (p.isPlaying) p.pause() else if (active) p.play() }
                            } else {
                                setControlsPinned(!controlsPinned)
                            }
                            lastTap = 0L
                        }
                    }
                    pendingSingleTap = action
                    tapHandler.postDelayed(action, 300L)
                }
            }

            itemView.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        touchMoved = false
                        tapHandler.removeCallbacks(holdToSpeed)
                        tapHandler.postDelayed(holdToSpeed, 450L)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val rawDx = event.x - downX
                        val dx = abs(rawDx)
                        val dy = abs(event.y - downY)
                        when {
                            seeking -> updateSeek(rawDx)
                            speedBoosting -> {
                                // 长按加速时手指难免慢慢挪动，按普通的点按阈值判“移动”会把加速掐掉。
                                // 放宽到大得多的距离，真的想滑走再停。
                                if (dx > boostSlop || dy > boostSlop) {
                                    touchMoved = true
                                    stopSpeedBoost()
                                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            // 明显是横着划：进入拖动进度，竖向的翻页交给 ViewPager2。
                            dx > touchSlop * 2 && dx > dy * 1.5f && !pipMode -> {
                                tapHandler.removeCallbacks(holdToSpeed)
                                touchMoved = true
                                startSeek()
                                updateSeek(rawDx)
                            }
                            !touchMoved && (dx > touchSlop || dy > touchSlop) -> {
                                touchMoved = true
                                tapHandler.removeCallbacks(holdToSpeed)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        tapHandler.removeCallbacks(holdToSpeed)
                        itemView.parent?.requestDisallowInterceptTouchEvent(false)
                        when {
                            seeking -> finishSeek()
                            speedBoosting -> stopSpeedBoost()
                            !touchMoved -> v.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        tapHandler.removeCallbacks(holdToSpeed)
                        itemView.parent?.requestDisallowInterceptTouchEvent(false)
                        if (seeking) finishSeek()
                        if (speedBoosting) stopSpeedBoost()
                        touchMoved = true
                        true
                    }
                    else -> true
                }
            }

        }

        private fun start(item: VideoItem) {
            val bindGeneration = generation
            if (!active || !playbackEnabled || released || player != null) return
            val renderersFactory = DefaultRenderersFactory(itemView.context).setEnableDecoderFallback(true)
            val p = ExoPlayer.Builder(itemView.context, renderersFactory)
                .setLoadControl(feedLoadControl())
                .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (bound?.id == item.id) notePlaying(isPlaying)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        watch.noteError()
                        // 记进诊断：黑屏、播不出来这类反馈要靠它看出是解码、地址还是文件的问题。
                        val cause = error.cause
                        NavigationDiagnostics.note(
                            itemView.context,
                            "播放错误 ${item.id}：${error.errorCodeName}" +
                                (cause?.let { "（${it.javaClass.simpleName}: ${it.message?.take(120)}）" } ?: "") +
                                " 源=${item.streamUrl?.substringBefore(':')?.take(12) ?: "无"}"
                        )
                        if (active && bound?.id == item.id) refreshSourceAfterError(item, error.errorCodeName)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width <= 0 || videoSize.height <= 0) return
                        val ratio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                        aspect = videoSize.width * ratio / videoSize.height
                        val wide = aspect > 1f
                        if (wide != landscape) {
                            landscape = wide
                            applyVideoInsets()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) { recoveryAttempts = 0; watch.noteReady() }
                        if (playbackState == Player.STATE_ENDED) watch.noteEnded()
                        if (playbackState == Player.STATE_READY && active) {
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) scheduleIdlePreload(pos)
                        }
                        if (playbackState == Player.STATE_ENDED && active && bound?.id == item.id) {
                            persistHistory(completed = true)
                            // 播完不再单记一笔固定分：离开这条时按连续兴趣值统一记（播完有加成），
                            // 免得出现“播完 +0.7 反而比看满 45 秒 +0.8 低”。
                            if (prefs.autoNext) {
                                val position = bindingAdapterPosition
                                if (position != RecyclerView.NO_POSITION) onEnded(position)
                            }
                        }
                    }
                })
            }
            player = p
            playerView.player = p

            val cachedSources = item.sources
            if (!cachedSources.isNullOrEmpty()) {
                prepareChosenSource(item, cachedSources, preservePosition = false)
                return
            }
            api.resolveSources(item.id) { result ->
                itemView.post {
                    if (released || !playbackEnabled || !active || generation != bindGeneration || bound?.id != item.id || player !== p) return@post
                    result.onSuccess { sources ->
                        item.sources = sources
                        prepareChosenSource(item, sources, preservePosition = false)
                    }.onFailure { title.text = "${item.title}\n[播放地址获取失败：${it.message}]" }
                }
            }
        }

        private fun prepareChosenSource(item: VideoItem, sources: List<VideoSource>, preservePosition: Boolean) {
            val p = player ?: return
            val preferred = item.selectedQuality ?: prefs.defaultQuality
            val source = if (!item.streamUrl.isNullOrBlank()) {
                sources.firstOrNull { it.url == item.streamUrl } ?: api.chooseSource(sources, preferred)
            } else api.chooseSource(sources, preferred)
            source ?: return
            val oldPosition = if (preservePosition) p.currentPosition else item.resumePositionMs.coerceAtLeast(0L)
            val shouldPlay = active && (p.isPlaying || !preservePosition)
            item.streamUrl = source.url
            if (item.selectedQuality == null && preferred != "highest") item.selectedQuality = source.name
            quality.text = "画质\n" + (item.selectedQuality ?: if (preferred == "highest") "最高" else source.name)
            p.setMediaSource(mediaCache.createMediaSource(source.url))
            if (oldPosition > 0L) p.seekTo(oldPosition)
            p.prepare()
            p.volume = if (active) 1f else 0f
            p.playWhenReady = active && shouldPlay
            if (active && shouldPlay) p.play()
        }

        fun setActive(enabled: Boolean) {
            if (enabled) {
                if (released || !playbackEnabled) return
                if (active && player != null) return
                active = true
                if (player == null) bound?.let { start(it) }
                else player?.apply { volume = 1f; playWhenReady = true; play() }
                bound?.let { item ->
                    val p = player
                    history.recordWatchAsync(item, p?.currentPosition ?: item.resumePositionMs, (p?.duration ?: 0L).coerceAtLeast(0L), false)
                }
                startWatchdog()
            } else {
                if (!active && player == null) return
                if (active) { recordWatchSignals(swipedAway = false); persistHistory(completed = false) }
                active = false
                setControlsPinned(false)
                generation++
                pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                pendingSingleTap = null
                tapHandler.removeCallbacks(holdToSpeed)
                stopSpeedBoost()
                stopWatchdog()
                releasePlayerOnly()
            }
        }

        /**
         * 暂停但留住播放器和画面。这里把 [active] 置回 false，所以恢复时
         * [setActive] 会走到“播放器还在就直接继续播”那一支，不用重新起播。
         */
        fun suspend() {
            val p = player ?: return
            if (!active) return
            persistHistory(completed = false)
            active = false
            pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
            pendingSingleTap = null
            tapHandler.removeCallbacks(holdToSpeed)
            stopSpeedBoost()
            stopWatchdog()
            p.playWhenReady = false
            p.pause()
        }

        /** 没暂停也把播放控件放出来（关掉点击暂停后，点一下画面就是切这个）。 */
        private fun setControlsPinned(pinned: Boolean) {
            if (controlsPinned == pinned) return
            controlsPinned = pinned
            pauseSeekBar.controlsPinned = pinned
            pauseIndicator.controlsPinned = pinned
        }

        fun applyDisplayPrefs() {
            // 设置里又把「点一下画面暂停播放」打开了：点出来的控件跟着收起。
            if (prefs.tapToPause) setControlsPinned(false)
            pauseIndicator.indicatorEnabled = prefs.showPauseIndicator
            val seconds = prefs.skipSeconds.toString()
            skipBackLabel.text = seconds
            skipForwardLabel.text = seconds
        }

        /** 暂停控制行的前进 / 后退：夹在 0 和片长之间，片长未知时只保证不为负。 */
        private fun skipBy(deltaMs: Long) {
            val p = player ?: return
            if (deltaMs < 0L) watch.noteRewound()
            val duration = p.duration
            val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
            p.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
        }

        /**
         * 画面在卡片里的摆法。PlayerView 是 fit 模式、内容居中，所以给它内边距
         * 就等于把画面限制在剩下的那块区域里。
         *
         * - 评论面板开着：上面留顶栏、下面留面板，画面缩进中间那块，面板不遮画面；
         * - 横屏视频：抬起卡片高度的 24%，画面中心落在约 38% 处——像哔哩哔哩那样偏上，
         *   而不是死在正中央，顺带也离底下的信息栏远一点；
         * - 竖屏视频：不动，铺满。
         */
        fun applyVideoInsets() {
            val height = itemView.height.takeIf { it > 0 } ?: itemView.resources.displayMetrics.heightPixels
            val panelOpen = bottomInset > 0 || topInset > 0
            val top = if (panelOpen && !pipMode && !fullscreenMode) topInset else 0
            val bottom = when {
                // 小窗：卡片只有两三百像素高，按整屏算出来的留白比窗口还大，画面会被挤没。
                pipMode -> 0
                // 全屏：画面铺满，横屏视频也不再上移——上移是为了给底下的信息栏让位，
                // 而全屏时那一栏根本不在。
                fullscreenMode -> 0
                panelOpen -> bottomInset
                landscape -> (height * LANDSCAPE_LIFT).toInt()
                else -> 0
            }
            // 留白不能把画面挤没：上下加起来至少给画面留卡片的 1/6。面板高度本来就能到
            // 七成屏，所以不能按“一半”硬砍——砍了画面会被居中回面板底下，反而被遮住。
            val cappedTop = top.coerceAtMost(height / 4)
            val cappedBottom = bottom.coerceAtMost((height - cappedTop - height / 6).coerceAtLeast(0))
            if (playerView.paddingBottom != cappedBottom || playerView.paddingTop != cappedTop) {
                playerView.setPadding(0, cappedTop, 0, cappedBottom)
            }
        }

        /** 继续播这张卡片（退出全屏时用）；卡片没在播放状态就不动。 */
        fun resumePlayback() {
            val p = player ?: return
            if (!active || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return
            p.playWhenReady = true
            p.play()
        }

        fun videoAspect(): Float? = aspect.takeIf { it > 0f }

        fun readyForIdlePreload(): Boolean = active && player?.playbackState == Player.STATE_READY

        fun context(): android.content.Context = itemView.context

        fun refreshFollowButton() {
            val item = bound ?: return
            itemView.findViewById<AuthorFollowButton>(R.id.authorFollow)?.render(item.authorFollowing)
        }

        fun refreshIfBound(videoId: String) {
            val item = bound ?: return
            if (item.id != videoId) return
            updateLikeUi(item)
            refreshFollowButton()
        }

        private fun stopSpeedBoost() {
            tapHandler.removeCallbacks(holdToSpeed)
            speedBoosting = false
            speedIndicator.visibility = View.GONE
            player?.setPlaybackSpeed(1f)
        }

        private val watchdog = object : Runnable {
            override fun run() {
                val p = player
                if (!active || p == null) return
                val position = p.currentPosition
                val shouldAdvance = p.playWhenReady && p.playbackState == Player.STATE_READY
                if (shouldAdvance && lastWatchdogPosition >= 0 && position <= lastWatchdogPosition + 120L) stalledChecks++
                else stalledChecks = 0
                if (stalledChecks >= 2) {
                    recoverStalledPlayback(position)
                    stalledChecks = 0
                }
                lastWatchdogPosition = position
                watchdogHandler.postDelayed(this, 3000L)
            }
        }

        private fun startWatchdog() {
            watchdogHandler.removeCallbacks(watchdog)
            lastWatchdogPosition = -1L
            stalledChecks = 0
            watchdogHandler.postDelayed(watchdog, 3000L)
        }

        private fun stopWatchdog() {
            watchdogHandler.removeCallbacks(watchdog)
            lastWatchdogPosition = -1L
            stalledChecks = 0
        }

        private fun recoverStalledPlayback(position: Long) {
            val item = bound ?: return
            val sources = item.sources ?: return
            val p = player ?: return
            val source = sources.firstOrNull { it.url == item.streamUrl }
                ?: api.chooseSource(sources, item.selectedQuality ?: prefs.defaultQuality)
                ?: return
            p.stop()
            p.clearMediaItems()
            p.setMediaSource(mediaCache.createMediaSource(source.url))
            p.prepare()
            if (position > 0L) p.seekTo(position)
            p.volume = 1f
            p.playWhenReady = true
            p.play()
            Toast.makeText(itemView.context, "播放器已自动恢复", Toast.LENGTH_SHORT).show()
        }

        /**
         * 播放出错就重新解析播放地址再来一次。
         *
         * Iwara 的 fileUrl 带 expires，开着 App 放久了地址会过期，播放器报错后停在 IDLE，
         * 卡片就一直黑屏——滑到下一条却能放，因为那条的地址是刚解析的。之前没有错误监听，
         * 这种黑屏永远不会自己恢复。
         */
        private fun refreshSourceAfterError(item: VideoItem, reason: String) {
            val p = player ?: return
            val position = p.currentPosition.coerceAtLeast(0L)
            if (recoveryAttempts >= MAX_ERROR_RECOVERIES) {
                title.text = "${item.title}\n[播放失败：$reason]"
                return
            }
            recoveryAttempts += 1
            val bindGeneration = generation
            val scheme = item.streamUrl?.substringBefore(':').orEmpty().lowercase()
            if (scheme == "content" || scheme == "file") {
                // 本地文件放不出来不该悄悄转成在线播放——用户会以为“下载没用、还得等很久”。
                Toast.makeText(itemView.context, "本地文件播放失败（$reason），改为在线播放", Toast.LENGTH_LONG).show()
            }
            item.sources = null
            item.streamUrl = null
            api.resolveSources(item.id) { result ->
                itemView.post {
                    if (released || !active || generation != bindGeneration || bound?.id != item.id) return@post
                    result.onSuccess { sources ->
                        item.sources = sources
                        item.resumePositionMs = position
                        prepareChosenSource(item, sources, preservePosition = false)
                    }.onFailure {
                        title.text = "${item.title}\n[播放地址刷新失败：${it.message}]"
                    }
                }
            }
        }

        private fun persistHistory(completed: Boolean) {
            val item = bound ?: return
            val p = player ?: return
            val position = p.currentPosition.coerceAtLeast(0L)
            val duration = p.duration.coerceAtLeast(0L)
            // 已经放完的视频记 0，不记片尾：否则划回来时从片尾起播，立刻又 ENDED、
            // 又自动跳下一条，看上去就是“划不回去”。
            val ended = completed || p.playbackState == Player.STATE_ENDED
            item.resumePositionMs = if (ended) 0L else position
            val completedByProgress = duration > 0 && position >= (duration * 0.9).toLong()
            history.recordWatchAsync(item, position, duration, completed || completedByProgress)
        }

        fun savePlaybackPosition() = persistHistory(completed = false)

        /**
         * [onAccepted] 在请求真正发出去时调用一次——登录检查和防抖都通过了才算数，
         * 所以不会出现“动画播完却弹出登录框”。
         */
        private fun toggleRemoteLike(item: VideoItem, desired: Boolean, onAccepted: (() -> Unit)? = null) {
            if (likeBusy) return
            if (!api.isLoggedIn()) { onNeedLogin(); return }
            likeBusy = true
            if (desired) onAccepted?.invoke()
            api.likeVideo(item.id, desired) { result ->
                itemView.post {
                    likeBusy = false
                    result.onSuccess {
                        if (item.liked != desired) item.likes = (item.likes + if (desired) 1 else -1).coerceAtLeast(0)
                        item.liked = desired
                        // 点赞等同于已看：下次生成推荐时要能被“排除已看视频”过滤掉。
                        if (desired) {
                            history.recordInteractionAsync(item, "like", 2.0)
                            history.markSeenAsync(item.id)
                            noteReaction(item, liked = true)
                            onSignal?.invoke(item, "like")
                        } else {
                            // 取消点赞：比“没点过”还差一点。
                            history.recordInteractionAsync(item, "unlike", -1.2)
                            onSignal?.invoke(item, "unlike")
                        }
                        updateLikeUi(item)
                    }.onFailure {
                        Toast.makeText(itemView.context, it.message ?: "点赞同步失败", Toast.LENGTH_SHORT).show()
                        if (it.message?.contains("登录") == true || it.message?.contains("401") == true) onNeedLogin()
                    }
                }
            }
        }

        private fun updateLikeUi(item: VideoItem) {
            like.setIcon(
                if (item.liked) R.drawable.ic_heart_rounded else R.drawable.ic_heart_rounded_outline,
                if (item.liked) 0xFFFF365D.toInt() else 0xFFFFFFFF.toInt()
            )
            favorite.setIcon(
                if (item.localFavorite) R.drawable.ic_star_rounded else R.drawable.ic_star_rounded_outline,
                if (item.localFavorite) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
            )
            likeCount.text = formatCount(item.likes)
        }

        /** ImageView 不回传当前是哪个图标，记在 tag 上，点没点赞一读就知道。 */
        private fun ImageView.setIcon(iconRes: Int, tint: Int) {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            setTag(R.id.reaction_icon, iconRes)
        }

        private fun showQualityChooser(item: VideoItem, forDownload: Boolean) {
            val cached = item.sources
            if (!cached.isNullOrEmpty()) { openSourceDialog(item, cached, forDownload); return }
            Toast.makeText(itemView.context, "正在读取清晰度…", Toast.LENGTH_SHORT).show()
            api.resolveSources(item.id) { result ->
                itemView.post {
                    result.onSuccess { sources -> item.sources = sources; openSourceDialog(item, sources, forDownload) }
                        .onFailure { Toast.makeText(itemView.context, "清晰度获取失败：${it.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        private fun openSourceDialog(item: VideoItem, sources: List<VideoSource>, forDownload: Boolean) {
            val labels = sources.map { if (it.score >= 10000) "${it.name}（原画）" else it.name }.toTypedArray()
            AlertDialog.Builder(itemView.context)
                .setTitle(if (forDownload) "选择下载清晰度" else "播放清晰度")
                .setItems(labels) { _, which ->
                    val source = sources[which]
                    if (forDownload) onDownload(item, source)
                    else {
                        item.selectedQuality = source.name
                        item.streamUrl = source.url
                        quality.text = "画质\n${source.name}"
                        prepareChosenSource(item, sources, preservePosition = true)
                    }
                }.show()
        }

        fun setChromeVisible(visible: Boolean) {
            infoPanel.visibility = if (visible) View.VISIBLE else View.GONE
            actionPanel.visibility = if (visible) View.VISIBLE else View.GONE
            // 时间牌在普通模式下要让开底下的信息栏；全屏没有那一栏，就往下挪到贴近底边。
            (seekPreview.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val dp = itemView.resources.displayMetrics.density
                val wanted = ((if (fullscreenMode) SEEK_PREVIEW_BOTTOM_FULLSCREEN_DP else SEEK_PREVIEW_BOTTOM_DP) * dp).toInt()
                if (lp.bottomMargin != wanted) {
                    lp.bottomMargin = wanted
                    seekPreview.layoutParams = lp
                }
            }
            // 进度条那一套要知道现在是普通、全屏还是小窗：小窗里它整个不出现，
            // 全屏里它反而是唯一的控制入口。
            itemView.setTag(R.id.chrome_mode, when {
                pipMode -> PauseSeekBar.MODE_PIP
                fullscreenMode -> PauseSeekBar.MODE_FULLSCREEN
                else -> PauseSeekBar.MODE_NORMAL
            })
        }

        fun isPlaying(): Boolean = active && player?.isPlaying == true

        fun release() {
            if (active) recordWatchSignals(swipedAway = false)
            persistHistory(completed = false)
            active = false
            tapHandler.removeCallbacks(holdToSpeed)
            stopSpeedBoost()
            stopWatchdog()
            generation++
            bound = null
            pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
            pendingSingleTap = null
            // 卡片被回收去放别的视频了，上一条的点赞动画不能跟着漂过去。
            reactionBurst.cancelBurst()
            releasePlayerOnly()
        }

        private fun releasePlayerOnly() {
            speedBoosting = false
            seeking = false
            speedIndicator.visibility = View.GONE
            seekPreview.visibility = View.GONE
            playerView.player = null
            player?.let { p ->
                p.setPlaybackSpeed(1f)
                p.playWhenReady = false
                p.volume = 0f
                p.stop()
                p.release()
            }
            player = null
        }

        private fun displayQuality(item: VideoItem): String {
            val value = item.selectedQuality ?: when (prefs.defaultQuality) { "highest" -> "最高"; else -> prefs.defaultQuality }
            return "画质\n$value"
        }

        private fun formatCount(value: Int): String = when {
            value >= 10_000 -> "%.1fw".format(value / 10_000.0)
            value >= 1_000 -> "%.1fk".format(value / 1_000.0)
            else -> value.toString()
        }
    }

    /**
     * 作者页关注/取关后同步到视频流：更新同一作者的所有条目，并刷新正在显示的关注按钮。
     */
    fun applyFollowState(result: AuthorActivity.FollowResult) {
        val key = result.authorId.ifBlank { result.username }
        if (key.isBlank()) return
        items.forEach { item ->
            val itemKey = item.authorId.ifBlank { item.authorUsername }
            val matches = itemKey == key ||
                (result.username.isNotBlank() && item.authorUsername == result.username) ||
                (result.authorId.isNotBlank() && item.authorId == result.authorId)
            if (matches) item.authorFollowing = result.following
        }
        holders.forEach { it.refreshFollowButton() }
    }

    /** 预缓存要判断网络状况，取任意一张已绑定卡片的 Context 就够。 */
    private val itemContext: android.content.Context?
        get() = holders.firstOrNull()?.context()

    /**
     * 竖滑流的缓冲策略：默认 50 秒的预读在划走时几乎全部作废，也会和当前播放抢带宽。
     * 25 秒的余量足够扛住网络抖动，浪费和峰值带宽都少一半；画质不受影响，
     * 码率由视频源本身决定。起播缓冲调小，弱网首帧更快。
     */
    private fun feedLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
        .build()

    companion object {
        /** 横屏视频往上抬的比例（占卡片高度）。 */
        const val LANDSCAPE_LIFT = 0.24f
        /** 同一条视频最多自动刷新几次播放地址，避免真的放不了时无限重试。 */
        const val MAX_ERROR_RECOVERIES = 2
        /** 观看兴趣值、快速划走判定、连续划走惩罚都搬到了 [WatchInteractionTracker]。 */
        /** 横向滑动调进度：位移比例的指数，越大越偏向“小幅=微调”。 */
        const val SEEK_CURVE = 1.8
        /** 一次滑动最多能调这么多（毫秒）；片长比它短时以片长为准。 */
        const val MAX_SEEK_RANGE_MS = 5 * 60 * 1000L
        /** 拖动中位置变化不到这么多就不重新定位，免得每个触摸事件都让播放器忙一次。 */
        const val SEEK_STEP_MS = 250L
        /** 时间牌离底边多远（dp）：普通模式要让开信息栏，全屏没有那一栏就贴得低一些。 */
        const val SEEK_PREVIEW_BOTTOM_DP = 150f
        const val SEEK_PREVIEW_BOTTOM_FULLSCREEN_DP = 56f

        /**
         * 按住画面左右滑动时该跳到哪一毫秒。
         *
         * 位移占屏宽的比例先取 [SEEK_CURVE] 次方再乘可调范围：手指挪一点是几秒的微调，
         * 划得越远跨度越大，划满一屏就是整个可调范围。可调范围取片长和
         * [MAX_SEEK_RANGE_MS] 里小的那个，结果夹在 0 和片长之间。
         */
        fun seekTargetFor(fromMs: Long, dx: Float, width: Int, durationMs: Long): Long {
            if (width <= 0 || durationMs <= 0L) return fromMs.coerceAtLeast(0L)
            val fraction = (dx / width).coerceIn(-1f, 1f)
            val curved = Math.signum(fraction) * Math.pow(abs(fraction).toDouble(), SEEK_CURVE).toFloat()
            val range = durationMs.coerceAtMost(MAX_SEEK_RANGE_MS)
            return (fromMs + (curved * range).toLong()).coerceIn(0L, durationMs)
        }
        const val MIN_BUFFER_MS = 12_000
        const val MAX_BUFFER_MS = 25_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_200
        const val BUFFER_AFTER_REBUFFER_MS = 2_500

        private val registryLock = Any()
        private var owner: WeakReference<VideoAdapter>? = null

        private fun claimPlaybackOwnership(adapter: VideoAdapter) = synchronized(registryLock) {
            val previous = owner?.get()
            if (previous !== null && previous !== adapter) previous.pauseAll()
            owner = WeakReference(adapter)
        }

        private fun unregister(adapter: VideoAdapter) = synchronized(registryLock) {
            if (owner?.get() === adapter) owner = null
        }
    }
}
