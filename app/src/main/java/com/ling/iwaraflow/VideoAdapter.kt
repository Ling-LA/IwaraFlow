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
    private val onShare: ((VideoItem) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    val items = mutableListOf<VideoItem>()
    private val holders = mutableSetOf<Holder>()
    private val preloadHandler = Handler(Looper.getMainLooper())
    private var preloadTask: Runnable? = null
    private var activePosition = RecyclerView.NO_POSITION
    private var pipMode = false
    @Volatile private var released = false
    // Selecting/binding a card is independent from granting a visible page playback.
    @Volatile private var playbackEnabled = false

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
        activePosition = position
        if (!playbackEnabled) return
        claimPlaybackOwnership(this)
        holders.toList().forEach { holder ->
            holder.setActive(activePosition in items.indices && holder.bindingAdapterPosition == activePosition)
        }
        scheduleIdlePreload(position)
    }

    fun pauseAll() {
        playbackEnabled = false
        cancelIdlePreload()
        holders.toList().forEach { it.setActive(false) }
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

    /** 当前正在播的那一条；小窗里的分享按钮要靠它知道分享谁。 */
    fun activeItem(): VideoItem? = items.getOrNull(activePosition)

    fun isActivePlaying(): Boolean = holders.any {
        it.bindingAdapterPosition == activePosition && it.isPlaying()
    }

    fun setPipMode(enabled: Boolean) {
        pipMode = enabled
        holders.forEach { it.setChromeVisible(!enabled) }
    }

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
        holder.setChromeVisible(!pipMode)
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
        private val pip = view.findViewById<TextView>(R.id.pip)
        private val share = view.findViewById<TextView>(R.id.share)
        private val reactionBurst = view.findViewById<ReactionBurstView>(R.id.reactionBurst)
        private val speedIndicator = view.findViewById<TextView>(R.id.speedIndicator)
        private val pauseIndicator = view.findViewById<PauseIndicatorView>(R.id.pauseIndicator)

        private var player: ExoPlayer? = null
        private var bound: VideoItem? = null
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

        private val touchSlop = ViewConfiguration.get(itemView.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var touchMoved = false
        private var speedBoosting = false
        private val holdToSpeed = Runnable {
            if (active && !touchMoved) {
                player?.let { p ->
                    if (p.playbackState != Player.STATE_IDLE) {
                        p.setPlaybackSpeed(2f)
                        speedBoosting = true
                        speedIndicator.visibility = View.VISIBLE
                    }
                }
            }
        }

        fun bind(item: VideoItem) {
            release()
            bound = item
            recoveryAttempts = 0
            item.localFavorite = history.isLocalFavorite(item.id)
            pauseIndicator.indicatorEnabled = prefs.showPauseIndicator
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
                history.setLocalFavorite(item, desired)
                if (desired) {
                    history.recordInteraction(item, "favorite", 1.4)
                    reactionBurst.playOn(favorite, ReactionBurstView.Kind.FAVORITE)
                }
                // 收藏有动画、取消有图标变化，不用再弹一层提示挡着视频。
                updateLikeUi(item)
            }
            quality.setOnClickListener { showQualityChooser(item, false) }
            download.setOnClickListener { showQualityChooser(item, true) }
            pip.setOnClickListener { onEnterPip() }
            share.setOnClickListener {
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
                            player?.let { p -> if (p.isPlaying) p.pause() else if (active) p.play() }
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
                        if (!touchMoved && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                            touchMoved = true
                            tapHandler.removeCallbacks(holdToSpeed)
                            if (speedBoosting) stopSpeedBoost()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        tapHandler.removeCallbacks(holdToSpeed)
                        if (speedBoosting) stopSpeedBoost() else if (!touchMoved) v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        tapHandler.removeCallbacks(holdToSpeed)
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
                    override fun onPlayerError(error: PlaybackException) {
                        if (active && bound?.id == item.id) refreshSourceAfterError(item, error.errorCodeName)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) recoveryAttempts = 0
                        if (playbackState == Player.STATE_READY && active) {
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) scheduleIdlePreload(pos)
                        }
                        if (playbackState == Player.STATE_ENDED && active && bound?.id == item.id) {
                            persistHistory(completed = true)
                            history.recordInteraction(item, "completed", 0.7)
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
                    history.recordWatch(item, p?.currentPosition ?: item.resumePositionMs, (p?.duration ?: 0L).coerceAtLeast(0L), false)
                }
                startWatchdog()
            } else {
                if (!active && player == null) return
                if (active) persistHistory(completed = false)
                active = false
                generation++
                pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                pendingSingleTap = null
                tapHandler.removeCallbacks(holdToSpeed)
                stopSpeedBoost()
                stopWatchdog()
                releasePlayerOnly()
            }
        }

        fun readyForIdlePreload(): Boolean = active && player?.playbackState == Player.STATE_READY

        fun context(): android.content.Context = itemView.context

        fun refreshFollowButton() {
            val item = bound ?: return
            itemView.findViewById<AuthorFollowButton>(R.id.authorFollow)?.render(item.authorFollowing)
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
            item.resumePositionMs = if (completed) 0L else position
            val completedByProgress = duration > 0 && position >= (duration * 0.9).toLong()
            history.recordWatch(item, position, duration, completed || completedByProgress)
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
                            history.recordInteraction(item, "like", 2.0)
                            history.markSeen(item.id)
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
        }

        fun isPlaying(): Boolean = active && player?.isPlaying == true

        fun release() {
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
            speedIndicator.visibility = View.GONE
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
        /** 同一条视频最多自动刷新几次播放地址，避免真的放不了时无限重试。 */
        const val MAX_ERROR_RECOVERIES = 2
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
