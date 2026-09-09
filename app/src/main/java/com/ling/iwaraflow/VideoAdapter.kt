package com.ling.iwaraflow

import android.view.*
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

class VideoAdapter(
    private val api: IwaraApi,
    private val onDoubleLike: (VideoItem, TextView) -> Unit
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    val items = mutableListOf<VideoItem>()
    private val holders = mutableSetOf<Holder>()
    private var activePosition = 0

    fun replace(newItems: List<VideoItem>) {
        releaseAll(); items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    fun releaseAll() { holders.toList().forEach { it.release() }; holders.clear() }

    fun setActive(position: Int) {
        activePosition = position
        holders.forEach { h ->
            if (h.bindingAdapterPosition == activePosition) h.resume() else h.pause()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return Holder(v).also { holders += it }
    }

    override fun onViewRecycled(holder: Holder) { holder.release(); holders -= holder; super.onViewRecycled(holder) }
    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        private val playerView = v.findViewById<PlayerView>(R.id.playerView)
        private val author = v.findViewById<TextView>(R.id.author)
        private val title = v.findViewById<TextView>(R.id.title)
        private val tags = v.findViewById<TextView>(R.id.tags)
        private val like = v.findViewById<TextView>(R.id.like)
        private val likeCount = v.findViewById<TextView>(R.id.likeCount)
        private val favorite = v.findViewById<TextView>(R.id.favorite)
        private var player: ExoPlayer? = null
        private var bound: VideoItem? = null
        private var lastTap = 0L

        fun bind(item: VideoItem) {
            release(); bound = item
            author.text = "@${item.author}"
            title.text = item.title
            tags.text = item.tags.take(8).joinToString("  ") { "#$it" }
            likeCount.text = formatCount(item.likes)
            favorite.setOnClickListener {
                val on = favorite.text == "★"; favorite.text = if (on) "☆" else "★"
            }
            itemView.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTap < 330) {
                    onDoubleLike(item, like); lastTap = 0
                } else {
                    lastTap = now
                    player?.let { p -> p.playWhenReady = !p.playWhenReady }
                }
            }
            start(item)
        }

        private fun start(item: VideoItem) {
            val p = ExoPlayer.Builder(itemView.context).build().also {
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.playWhenReady = (bindingAdapterPosition == activePosition)
            }
            player = p; playerView.player = p
            val cached = item.streamUrl
            if (cached != null) play(p, cached) else api.resolveStream(item.id) { result ->
                itemView.post {
                    if (bound?.id != item.id || player !== p) return@post
                    result.onSuccess { url -> item.streamUrl = url; play(p, url) }
                    result.onFailure { title.text = "${item.title}\n[播放地址获取失败：${it.message}]" }
                }
            }
        }

        private fun play(p: ExoPlayer, url: String) {
            p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); if (bindingAdapterPosition == activePosition) p.play() else p.pause()
        }

        fun pause() { player?.pause() }
        fun resume() { player?.play() }
        fun release() { playerView.player = null; player?.release(); player = null }
        private fun formatCount(v: Int) = when { v >= 10000 -> "%.1fw".format(v / 10000.0); v >= 1000 -> "%.1fk".format(v / 1000.0); else -> v.toString() }
    }
}
