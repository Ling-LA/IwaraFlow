package com.ling.iwaraflow

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 全屏播放：收起卡片上的信息栏 / 操作栏和页面自己的顶栏，画面铺满。
 *
 * 横屏视频（宽大于高）顺便把屏幕转过去，竖着拿手机也能看满一整屏；竖屏视频本来就铺满，
 * 只把控件收起来，不转屏。暂停时进度条、快进后退、剩余时长、小窗和退出全屏会重新出现，
 * 那一套由 [PauseSeekBar] 按卡片的模式标记决定，见 [VideoAdapter.setChromeVisible]。
 */
object FullscreenMode {
    private val pendingRotations = java.util.WeakHashMap<ViewPager2, () -> Unit>()

    /** Layout-only page notifications must not activate, record or preload another video. */
    fun isChangingViewport(pager: ViewPager2): Boolean = pendingRotations.containsKey(pager)

    /** Keep a vertical page anchored while its viewport changes from tall to wide or back. */
    private fun preservePage(activity: Activity, adapter: VideoAdapter, landscape: Boolean) {
        val pager = sequenceOf(R.id.pager, R.id.searchPager, R.id.authorPager, R.id.savedPager)
            .mapNotNull { activity.findViewById<ViewPager2>(it) }.firstOrNull { it.adapter === adapter } ?: return
        pendingRotations.remove(pager)?.invoke()
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return
        val layout = recycler.layoutManager as? LinearLayoutManager ?: return
        val videoId = adapter.activeItem()?.id ?: adapter.items.getOrNull(pager.currentItem)?.id ?: return
        val inputEnabled = pager.isUserInputEnabled
        adapter.savePlaybackPosition()
        var finished = false
        var aligning = false
        lateinit var resized: View.OnLayoutChangeListener
        lateinit var aligned: ViewTreeObserver.OnPreDrawListener
        lateinit var detached: View.OnAttachStateChangeListener
        lateinit var timeout: Runnable
        fun release() {
            if (finished) return
            finished = true
            pager.removeOnLayoutChangeListener(resized)
            if (pager.viewTreeObserver.isAlive) pager.viewTreeObserver.removeOnPreDrawListener(aligned)
            pager.removeOnAttachStateChangeListener(detached)
            pager.removeCallbacks(timeout)
            recycler.suppressLayout(false)
            pager.isUserInputEnabled = inputEnabled
            pendingRotations.remove(pager)
        }
        fun align() {
            if (finished) return
            aligning = true
            val position = adapter.items.indexOfFirst { it.id == videoId }.takeIf { it >= 0 }
            if (position != null && pager.adapter === adapter) {
                pager.setCurrentItem(position, false)
                layout.scrollToPositionWithOffset(position, 0)
            }
            recycler.suppressLayout(false)
            pager.requestLayout()
        }
        fun targetSizeReached() = pager.width > 0 && pager.height > 0 && (pager.width > pager.height) == landscape
        resized = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!aligning && targetSizeReached()) align()
        }
        aligned = ViewTreeObserver.OnPreDrawListener {
            if (!aligning || finished) return@OnPreDrawListener true
            val position = adapter.items.indexOfFirst { it.id == videoId }
            val child = layout.findViewByPosition(position)
            val ready = position >= 0 && pager.currentItem == position && !recycler.isLayoutRequested && child != null &&
                layout.getDecoratedTop(child) == recycler.paddingTop &&
                child.height == recycler.height - recycler.paddingTop - recycler.paddingBottom
            if (ready || position < 0 || pager.adapter !== adapter) release() else align()
            ready || finished
        }
        detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) = release()
        }
        timeout = Runnable {
            if (!aligning) {
                align()
                pager.postDelayed(timeout, 1000)
            } else release()
        }
        // Install the guard before stopping the fling: even stopScroll can notify a selection.
        pendingRotations[pager] = { release() }
        recycler.stopScroll()
        pager.isUserInputEnabled = false
        recycler.suppressLayout(true)
        pager.addOnLayoutChangeListener(resized)
        pager.viewTreeObserver.addOnPreDrawListener(aligned)
        pager.addOnAttachStateChangeListener(detached)
        pager.post { if (targetSizeReached()) align() else if (!pager.isAttachedToWindow) release() }
        // Some multi-window configurations ignore requested orientation; never leave input locked.
        pager.postDelayed(timeout, 2000)
    }

    /**
     * 进入或退出全屏。[chrome] 是页面自己要一起收起的东西（顶栏、返回按钮等）。
     * 返回实际是否处于全屏，页面据此记状态。
     */
    fun apply(activity: Activity, adapter: VideoAdapter, chrome: List<View?>, enabled: Boolean): Boolean {
        val landscapeVideo = (adapter.activeVideoAspect() ?: 0f) > 1f
        preservePage(activity, adapter, enabled && landscapeVideo)
        adapter.setFullscreen(enabled, resumeOnExit = !PlaybackGuide.isActive(activity))
        chrome.forEach { it?.visibility = if (enabled) View.GONE else View.VISIBLE }
        activity.requestedOrientation = if (enabled && landscapeVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (PlaybackGuide.isActive(activity)) return enabled
        if (enabled && landscapeVideo) activity.window.decorView.post {
            if (adapter.isFullscreen && !PlaybackGuide.isActive(activity) && !activity.isFinishing && !activity.isDestroyed) PlaybackGuide.showOnce(activity, landscapeVideo)
        } else PlaybackGuide.dismiss(activity)
        return enabled
    }
}
