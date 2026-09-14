package com.ling.iwaraflow

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/**
 * Seek bar that is visible only while the current Media3 player is paused.
 *
 * It sits just under the video tags, where the information panel ends. While it is on screen the
 * information and action panels are hidden: they share that bottom strip, and dragging the bar
 * across them is how the like, download and share buttons get hit by accident. The panels are
 * hidden with INVISIBLE rather than GONE so the bar can keep measuring where the tags are.
 */
class PauseSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    private var dragging = false
    private var observedPlayer: Player? = null
    private var chromeHidden = false

    /**
     * 不暂停也把这一套控件放出来。关掉「点一下画面暂停播放」后，点一下画面就是在
     * 信息栏和这套控件之间切换，视频照常播。
     */
    var controlsPinned = false
        set(value) {
            if (field == value) return
            field = value
            refreshFromPlayer()
        }

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
                if (duration > 0L) {
                    val target = (duration * value / max.toDouble()).toLong()
                    p?.seekTo(target)
                    showSeekPreview(target, duration)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                dragging = false
                preview(cardRoot())?.visibility = View.GONE
                refreshFromPlayer()
            }
        })
    }

    /** 拖动时屏幕下方居中：当前位置 / 总时长。 */
    private fun showSeekPreview(position: Long, duration: Long) {
        val view = preview(cardRoot()) ?: return
        view.text = "${formatTime(position)} / ${formatTime(duration)}"
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
    }

    /** 暂停时进度条右上方那一行：退出全屏 / 小窗 / 剩余时长。 */
    private fun placeTopRow(root: View, position: Long, duration: Long) {
        val row = topRow(root) ?: return
        remaining(root)?.text = "-${formatTime((duration - position).coerceAtLeast(0L))}"
        root.findViewById<View>(R.id.pauseFullscreenExit)?.visibility =
            if (mode(root) == MODE_FULLSCREEN) View.VISIBLE else View.GONE
        if (row.visibility != View.VISIBLE) row.visibility = View.VISIBLE
        var rowHeight = row.height
        if (rowHeight <= 0) {
            row.measure(
                View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
            )
            rowHeight = row.measuredHeight
        }
        val gap = (4f * resources.displayMetrics.density).roundToInt()
        row.translationY = (translationY - rowHeight - gap).coerceAtLeast(0f)
    }

    private fun topRow(root: View?): View? = root?.findViewById(R.id.pauseTopRow)
    private fun remaining(root: View?): TextView? = root?.findViewById(R.id.pauseRemaining)
    private fun preview(root: View?): TextView? = root?.findViewById(R.id.seekPreview)

    /** 卡片当前的显示模式，由 [VideoAdapter] 写在根视图的 tag 上。 */
    private fun mode(root: View?): String = root?.getTag(R.id.chrome_mode) as? String ?: MODE_NORMAL

    private fun hideExtras(root: View?) {
        topRow(root)?.visibility = View.GONE
        preview(root)?.visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(updater)
        post(updater)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updater)
        observedPlayer = null
        controlsPinned = false
        visibility = View.GONE
        controls(cardRoot())?.visibility = View.GONE
        hideExtras(cardRoot())
        restoreChrome()
        super.onDetachedFromWindow()
    }

    private fun refreshFromPlayer() {
        val root = cardRoot()
        val p = findPlayer()
        observedPlayer = p
        val duration = p?.duration ?: 0L
        val alive = p != null && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        val paused = alive && p != null && !p.isPlaying && !p.playWhenReady
        // 关掉「点一下画面暂停播放」时点出来的控件：没暂停也要显示。
        if (root == null || !(paused || (controlsPinned && alive)) || duration <= 0L) {
            visibility = View.GONE
            controls(root)?.visibility = View.GONE
            hideExtras(root)
            restoreChrome()
            return
        }
        // 画中画里只有那么点大，这一套控件都不出现。全屏则相反：控件本来就藏着，
        // 暂停时正要靠这里把进度条、快进后退、剩余时长和退出全屏放出来。
        if (mode(root) == MODE_PIP) {
            visibility = View.GONE
            controls(root)?.visibility = View.GONE
            hideExtras(root)
            chromeHidden = false
            return
        }

        hideChrome(root)
        placeUnderTags(root)
        placeControls(root)
        visibility = View.VISIBLE
        val position = p!!.currentPosition.coerceIn(0L, duration)
        if (!dragging) {
            progress = ((position * max.toDouble()) / duration).roundToInt().coerceIn(0, max)
        }
        placeTopRow(root, position, duration)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** 停在标签下面那一行；全屏时信息栏是收起的，就贴到屏幕底部。 */
    private fun placeUnderTags(root: View) {
        val gap = (10f * resources.displayMetrics.density).roundToInt()
        val bottomInset = (28f * resources.displayMetrics.density).roundToInt()
        val tags = root.findViewById<View>(R.id.tags)
        val wanted = if (tags == null || tags.height <= 0 || mode(root) == MODE_FULLSCREEN) root.height - height - bottomInset
        else bottomInRoot(tags, root) + gap
        val lowest = (root.height - height - bottomInset).coerceAtLeast(0)
        translationY = wanted.coerceIn(0, lowest).toFloat()
    }

    /** 暂停控制行（后退 / 播放 / 前进）贴在进度条正上方，左对齐。 */
    private fun placeControls(root: View) {
        val row = controls(root) ?: return
        if (row.visibility != View.VISIBLE) row.visibility = View.VISIBLE
        var rowHeight = row.height
        if (rowHeight <= 0) {
            row.measure(
                View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
            )
            rowHeight = row.measuredHeight
        }
        val gap = (2f * resources.displayMetrics.density).roundToInt()
        row.translationY = (translationY - rowHeight - gap).coerceAtLeast(0f)
    }

    private fun controls(root: View?): View? = root?.findViewById(R.id.pauseControls)

    private fun bottomInRoot(view: View, root: View): Int {
        var offset = view.bottom
        var node = view.parent as? View
        while (node != null && node !== root) {
            offset += node.top
            node = node.parent as? View
        }
        return offset
    }

    private fun hideChrome(root: View) {
        chromeHidden = true
        // 卡片被回收复用后适配器会重新把控件显示出来，所以每次都要重新盖上。
        val info = root.findViewById<View>(R.id.infoPanel)
        val actions = root.findViewById<View>(R.id.actionPanel)
        if (info?.visibility != View.INVISIBLE) info?.visibility = View.INVISIBLE
        if (actions?.visibility != View.INVISIBLE) actions?.visibility = View.INVISIBLE
    }

    /**
     * 恢复信息栏 / 操作栏。该不该显示由当前模式决定，而不是记住盖上之前的样子——
     * 在全屏或小窗里把它们放出来就穿帮了。
     */
    private fun restoreChrome() {
        if (!chromeHidden) return
        val root = cardRoot()
        val wanted = if (mode(root) == MODE_NORMAL) View.VISIBLE else View.GONE
        root?.findViewById<View>(R.id.infoPanel)?.visibility = wanted
        root?.findViewById<View>(R.id.actionPanel)?.visibility = wanted
        chromeHidden = false
    }

    private fun findPlayer(): Player? = cardRoot()?.findViewById<PlayerView>(R.id.playerView)?.player

    /**
     * 只认自己这张卡片。RecyclerView 上同时挂着别的卡片，
     * 一路往上找 playerView 会拿到别人的播放器。
     */
    private fun cardRoot(): View? {
        var node: View? = this
        while (node != null) {
            if (node.id == R.id.root) return node
            node = node.parent as? View
        }
        return null
    }

    companion object {
        const val MODE_NORMAL = "normal"
        const val MODE_FULLSCREEN = "fullscreen"
        const val MODE_PIP = "pip"
    }
}
