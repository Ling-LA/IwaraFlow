package com.ling.iwaraflow

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/**
 * Seek bar that is visible only while the current Media3 player is paused.
 * It sits in the information panel under the video tags, so it no longer has to chase the
 * bottom edge of letterboxed video or hide the surrounding chrome to stay draggable.
 */
class PauseSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    private var dragging = false
    private var observedPlayer: Player? = null

    private val updater = object : Runnable {
        override fun run() {
            refreshFromPlayer()
            if (isAttachedToWindow) postDelayed(this, 150L)
        }
    }

    init {
        max = 1000
        splitTrack = false
        progressTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        visibility = View.GONE
        setPadding(0, 0, 0, 0)

        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = observedPlayer ?: findPlayer()
                val duration = p?.duration ?: 0L
                if (duration > 0L) p?.seekTo((duration * value / max.toDouble()).toLong())
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                dragging = false
                refreshFromPlayer()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(updater)
        post(updater)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updater)
        observedPlayer = null
        visibility = View.GONE
        super.onDetachedFromWindow()
    }

    private fun refreshFromPlayer() {
        val p = findPlayer()
        observedPlayer = p
        if (p == null) {
            visibility = View.GONE
            return
        }

        val duration = p.duration
        val paused = !p.isPlaying && !p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        if (!paused || duration <= 0L) {
            visibility = View.GONE
            return
        }

        visibility = View.VISIBLE
        if (!dragging) {
            progress = ((p.currentPosition.coerceIn(0L, duration) * max.toDouble()) / duration)
                .roundToInt().coerceIn(0, max)
        }
    }

    /**
     * 只在自己这张卡片里找播放器。RecyclerView 上同时挂着别的卡片，
     * 沿着父节点一直往上找会拿到别人的 PlayerView。
     */
    private fun findPlayer(): Player? {
        var node: View? = this
        while (node != null) {
            if (node.id == R.id.root) return node.findViewById<PlayerView>(R.id.playerView)?.player
            node = node.parent as? View
        }
        return null
    }
}
