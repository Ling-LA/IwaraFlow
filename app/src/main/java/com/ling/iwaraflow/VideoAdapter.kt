package com.ling.iwaraflow

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

class VideoAdapter(
    private val api: IwaraApi,
    private val history: HistoryStore,
    private val prefs: AppPrefs,
    private val onDownload: (VideoItem, VideoSource) -> Unit,
    private val onEnterPip: () -> Unit,
    private val onEnded: (Int) -> Unit,
    private val onNeedLogin: () -> Unit
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    val items = mutableListOf<VideoItem>()
    private val holders = mutableSetOf<Holder>()
    private var activePosition = RecyclerView.NO_POSITION
    private var pipMode = false

    fun replace(newItems: List<VideoItem>) {
        holders.toList().forEach { it.release() }
        items.clear()
        items.addAll(newItems)
        activePosition = if (items.isEmpty()) RecyclerView.NO_POSITION else 0
        notifyDataSetChanged()
    }

    fun append(newItems: List<VideoItem>) {
        val existing = items.asSequence().map { it.id }.toHashSet()
        val unique = newItems.filter { existing.add(it.id) }
        if (unique.isEmpty()) return
        val start = items.size
        items.addAll(unique)
        notifyItemRangeInserted(start, unique.size)
    }

    fun releaseAll() {
        holders.toList().forEach { it.release() }
        holders.clear()
    }

    fun setActive(position: Int) {
        activePosition = position
        holders.forEach { holder ->
            holder.setActive(holder.bindingAdapterPosition == activePosition)
        }
        preloadAround(position)
    }

    fun pauseAll() = holders.forEach { it.setActive(false) }

    fun resumeActive() = holders.forEach { holder ->
        holder.setActive(holder.bindingAdapterPosition == activePosition)
    }

    fun isActivePlaying(): Boolean = holders.any {
        it.bindingAdapterPosition == activePosition && it.isPlaying()
    }

    fun setPipMode(enabled: Boolean) {
        pipMode = enabled
        holders.forEach { it.setChromeVisible(!enabled) }
    }

    private fun preloadAround(position: Int) {
        listOf(position + 1, position + 2).forEach { index ->
            val item = items.getOrNull(index) ?: return@forEach
            if (item.sources != null) return@forEach
            api.resolveSources(item.id) { result ->
                result.onSuccess { sources ->
                    item.sources = sources
                    item.streamUrl = api.chooseSource(sources, item.selectedQuality ?: prefs.defaultQuality)?.url
                }
            }
        }
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
        holder.bind(items[position])
        holder.setChromeVisible(!pipMode)
        holder.setActive(position == activePosition)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val playerView = view.findViewById<PlayerView>(R.id.playerView)
        private val infoPanel = view.findViewById<View>(R.id.infoPanel)
        private val actionPanel = view.findViewById<View>(R.id.actionPanel)
        private val author = view.findViewById<TextView>(R.id.author)
        private val title = view.findViewById<TextView>(R.id.title)
        private val tags = view.findViewById<TextView>(R.id.tags)
        private val like = view.findViewById<TextView>(R.id.like)
        private val likeCount = view.findViewById<TextView>(R.id.likeCount)
        private val favorite = view.findViewById<TextView>(R.id.favorite)
        private val quality = view.findViewById<TextView>(R.id.quality)
        private val download = view.findViewById<TextView>(R.id.download)
        private val pip = view.findViewById<TextView>(R.id.pip)
        private val likeBurst = view.findViewById<TextView>(R.id.likeBurst)

        private var player: ExoPlayer? = null
        private var bound: VideoItem? = null
        private var active = false
        private var generation = 0
        private var likeBusy = false
        private val tapHandler = Handler(Looper.getMainLooper())
        private var pendingSingleTap: Runnable? = null
        private var lastTap = 0L

        fun bind(item: VideoItem) {
            releasePlayerOnly()
            generation++
            bound = item
            item.localFavorite = history.isLocalFavorite(item.id)
            author.text = "@${item.author}"
            title.text = item.title
            tags.text = item.tags.take(8).joinToString("  ") { "#$it" }
            updateLikeUi(item)
            quality.text = displayQuality(item)

            like.setOnClickListener { toggleRemoteLike(item, !item.liked, animate = false) }
            favorite.setOnClickListener { toggleRemoteLike(item, !item.liked, animate = false) }
            quality.setOnClickListener { showQualityChooser(item, false) }
            download.setOnClickListener { showQualityChooser(item, true) }
            pip.setOnClickListener { onEnterPip() }

            itemView.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTap <= 320L) {
                    pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                    pendingSingleTap = null
                    lastTap = 0L
                    showLikeBurst()
                    if (!item.liked) toggleRemoteLike(item, true, animate = false)
                } else {
                    lastTap = now
                    val action = Runnable {
                        if (System.currentTimeMillis() - lastTap >= 280L) {
                            player?.let { p ->
                                if (p.isPlaying) p.pause() else if (active) p.play()
                            }
                            lastTap = 0L
                        }
                    }
                    pendingSingleTap = action
                    tapHandler.postDelayed(action, 300L)
                }
            }

            start(item)
        }

        private fun start(item: VideoItem) {
            val bindGeneration = generation
            val p = ExoPlayer.Builder(itemView.context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
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
                    if (generation != bindGeneration || bound?.id != item.id || player !== p) return@post
                    result.onSuccess { sources ->
                        item.sources = sources
                        prepareChosenSource(item, sources, preservePosition = false)
                    }.onFailure {
                        title.text = "${item.title}\n[播放地址获取失败：${it.message}]"
                    }
                }
            }
        }

        private fun prepareChosenSource(item: VideoItem, sources: List<VideoSource>, preservePosition: Boolean) {
            val p = player ?: return
            val preferred = item.selectedQuality ?: prefs.defaultQuality
            val source = api.chooseSource(sources, preferred) ?: return
            val oldPosition = if (preservePosition) p.currentPosition else 0L
            val shouldPlay = active && (p.isPlaying || !preservePosition)
            item.streamUrl = source.url
            item.selectedQuality = if (preferred == "highest") null else source.name
            quality.text = if (preferred == "highest") "最高" else source.name
            p.setMediaItem(MediaItem.fromUri(source.url))
            if (oldPosition > 0) p.seekTo(oldPosition)
            p.prepare()
            if (active) {
                p.volume = 1f
                p.playWhenReady = shouldPlay
                if (shouldPlay) p.play()
            } else {
                p.playWhenReady = false
                p.volume = 0f
                p.pause()
            }
        }

        fun setActive(enabled: Boolean) {
            if (active == enabled && player != null) {
                if (!enabled) forceSilent()
                return
            }
            if (!enabled && active) persistHistory(completed = false)
            active = enabled
            val p = player ?: return
            if (enabled) {
                p.volume = 1f
                if (p.playbackState == Player.STATE_ENDED) {
                    p.seekTo(0L)
                    p.prepare()
                }
                p.playWhenReady = true
                p.play()
                bound?.let { item ->
                    history.recordWatch(item, p.currentPosition, p.duration.coerceAtLeast(0L), false)
                }
            } else {
                forceSilent()
            }
        }

        private fun forceSilent() {
            player?.let { p ->
                p.playWhenReady = false
                p.pause()
                p.volume = 0f
            }
        }

        private fun persistHistory(completed: Boolean) {
            val item = bound ?: return
            val p = player ?: return
            val duration = p.duration.coerceAtLeast(0L)
            val completedByProgress = duration > 0 && p.currentPosition >= (duration * 0.9).toLong()
            history.recordWatch(item, p.currentPosition, duration, completed || completedByProgress)
        }

        private fun toggleRemoteLike(item: VideoItem, desired: Boolean, animate: Boolean) {
            if (likeBusy) return
            if (!api.isLoggedIn()) {
                onNeedLogin()
                return
            }
            likeBusy = true
            if (animate) showLikeBurst()
            api.likeVideo(item.id, desired) { result ->
                itemView.post {
                    likeBusy = false
                    result.onSuccess {
                        if (item.liked != desired) {
                            item.likes = (item.likes + if (desired) 1 else -1).coerceAtLeast(0)
                        }
                        item.liked = desired
                        history.setLocalFavorite(item, desired)
                        if (desired) history.recordInteraction(item, "like", 2.0)
                        updateLikeUi(item)
                    }.onFailure {
                        Toast.makeText(itemView.context, it.message ?: "点赞同步失败", Toast.LENGTH_SHORT).show()
                        if (it.message?.contains("登录") == true || it.message?.contains("401") == true) onNeedLogin()
                    }
                }
            }
        }

        private fun updateLikeUi(item: VideoItem) {
            like.text = if (item.liked) "♥" else "♡"
            like.setTextColor(if (item.liked) 0xFFFF365D.toInt() else 0xFFFFFFFF.toInt())
            favorite.text = if (item.liked) "★" else "☆"
            favorite.setTextColor(if (item.liked) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
            likeCount.text = formatCount(item.likes)
        }

        private fun showLikeBurst() {
            likeBurst.visibility = View.VISIBLE
            likeBurst.alpha = 0f
            likeBurst.scaleX = 0.35f
            likeBurst.scaleY = 0.35f
            likeBurst.animate().cancel()
            likeBurst.animate()
                .alpha(1f)
                .scaleX(1.18f)
                .scaleY(1.18f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    likeBurst.animate()
                        .alpha(0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setStartDelay(180L)
                        .setDuration(240L)
                        .withEndAction { likeBurst.visibility = View.GONE }
                        .start()
                }
                .start()
        }

        private fun showQualityChooser(item: VideoItem, forDownload: Boolean) {
            val cached = item.sources
            if (!cached.isNullOrEmpty()) {
                openSourceDialog(item, cached, forDownload)
                return
            }
            Toast.makeText(itemView.context, "正在读取清晰度…", Toast.LENGTH_SHORT).show()
            api.resolveSources(item.id) { result ->
                itemView.post {
                    result.onSuccess { sources ->
                        item.sources = sources
                        openSourceDialog(item, sources, forDownload)
                    }.onFailure {
                        Toast.makeText(itemView.context, "清晰度获取失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        private fun openSourceDialog(item: VideoItem, sources: List<VideoSource>, forDownload: Boolean) {
            val labels = sources.map { if (it.score >= 10000) "${it.name}（原画）" else it.name }.toTypedArray()
            AlertDialog.Builder(itemView.context)
                .setTitle(if (forDownload) "选择下载清晰度" else "播放清晰度")
                .setItems(labels) { _, which ->
                    val source = sources[which]
                    if (forDownload) {
                        onDownload(item, source)
                    } else {
                        item.selectedQuality = source.name
                        quality.text = source.name
                        prepareChosenSource(item, sources, preservePosition = true)
                    }
                }
                .show()
        }

        fun setChromeVisible(visible: Boolean) {
            infoPanel.visibility = if (visible) View.VISIBLE else View.GONE
            actionPanel.visibility = if (visible) View.VISIBLE else View.GONE
        }

        fun isPlaying(): Boolean = active && player?.isPlaying == true

        fun release() {
            persistHistory(completed = false)
            active = false
            generation++
            bound = null
            pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
            pendingSingleTap = null
            releasePlayerOnly()
        }

        private fun releasePlayerOnly() {
            playerView.player = null
            player?.let { p ->
                p.playWhenReady = false
                p.volume = 0f
                p.stop()
                p.release()
            }
            player = null
        }

        private fun displayQuality(item: VideoItem): String = item.selectedQuality ?: when (prefs.defaultQuality) {
            "highest" -> "最高"
            else -> prefs.defaultQuality
        }

        private fun formatCount(value: Int): String = when {
            value >= 10_000 -> "%.1fw".format(value / 10_000.0)
            value >= 1_000 -> "%.1fk".format(value / 1_000.0)
            else -> value.toString()
        }
    }
}
