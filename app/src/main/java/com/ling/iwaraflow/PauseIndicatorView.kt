package com.ling.iwaraflow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/**
 * 暂停时在画面中央显示一个播放图标。可以在设置里关掉——有人嫌它挡画面。
 */
class PauseIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    /** 关掉之后就一直不显示；由卡片在绑定时按设置写入。 */
    var indicatorEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            refreshState()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshState()
            if (isAttachedToWindow) handler.postDelayed(this, 120L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        visibility = View.GONE
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        if (!indicatorEnabled) {
            visibility = View.GONE
            return
        }
        val player = findSiblingPlayerView()?.player
        val explicitlyPaused = player != null &&
            !player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        visibility = if (explicitlyPaused) View.VISIBLE else View.GONE
    }

    private fun findSiblingPlayerView(): PlayerView? {
        val parentGroup = parent as? ViewGroup ?: return null
        for (i in 0 until parentGroup.childCount) {
            val child = parentGroup.getChildAt(i)
            if (child is PlayerView) return child
        }
        return null
    }
}
