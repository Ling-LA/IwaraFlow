package com.ling.iwaraflow

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/**
 * Seek bar that is visible only while the current Media3 player is paused.
 * It follows the actual bottom edge of resize_mode=fit video content, including letterboxed video.
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
        placeAtVideoBottom(p)
    }

    private fun findPlayer(): Player? {
        val container = parent as? FrameLayout ?: return null
        return container.findViewById<PlayerView>(R.id.playerView)?.player
    }

    private fun placeAtVideoBottom(player: Player) {
        val container = parent as? FrameLayout ?: return
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0) return

        val videoSize = player.videoSize
        val vw = videoSize.width
        val vh = videoSize.height
        if (vw <= 0 || vh <= 0) {
            translationY = (ch - height).toFloat()
            return
        }

        val videoAspect = vw.toFloat() / vh.toFloat()
        val containerAspect = cw.toFloat() / ch.toFloat()
        val displayedWidth: Float
        val displayedHeight: Float
        if (videoAspect > containerAspect) {
            displayedWidth = cw.toFloat()
            displayedHeight = displayedWidth / videoAspect
        } else {
            displayedHeight = ch.toFloat()
            displayedWidth = displayedHeight * videoAspect
        }

        val left = ((cw - displayedWidth) / 2f).roundToInt().coerceAtLeast(0)
        val bottom = (ch + displayedHeight) / 2f
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val wantedWidth = displayedWidth.roundToInt().coerceAtLeast(1)
        if (lp.width != wantedWidth || lp.leftMargin != left) {
            lp.width = wantedWidth
            lp.leftMargin = left
            layoutParams = lp
        }
        translationY = bottom - height / 2f
    }
}
