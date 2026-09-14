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

    /**
     * 控件是被点出来的（没暂停也显示）。这时按钮是暂停图标，点一下才暂停；
     * 视频暂停着的时候仍然是播放三角。
     */
    var controlsPinned: Boolean = false
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
        setOnClickListener {
            val player = findPlayer() ?: return@setOnClickListener
            if (player.isPlaying) player.pause() else player.play()
            refreshState()
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
        val player = findPlayer()
        val alive = player != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        val explicitlyPaused = alive && player != null && !player.playWhenReady
        // 点出来的控件里也有这个按钮：正在播就显示暂停图标。
        val shown = explicitlyPaused || (controlsPinned && alive)
        visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) {
            val playing = player != null && player.isPlaying
            setBackgroundResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (playing) "暂停" else "继续播放"
        }
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
