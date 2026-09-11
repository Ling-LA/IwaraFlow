package com.ling.iwaraflow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/**
 * 暂停时显示的播放三角：坐在左下角的暂停控制行里，点一下继续播放。
 * 可以在设置里关掉。
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

    init {
        setOnClickListener { findPlayer()?.play() }
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
        val player = findPlayer()
        val explicitlyPaused = player != null &&
            !player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        visibility = if (explicitlyPaused) View.VISIBLE else View.GONE
    }

    /** 只认自己这张卡片的播放器：往上找到卡片根布局，再从那里取 playerView。 */
    private fun findPlayer(): Player? {
        var node: View? = this
        while (node != null) {
            if (node.id == R.id.root) return node.findViewById<PlayerView>(R.id.playerView)?.player
            node = node.parent as? View
        }
        return null
    }
}
