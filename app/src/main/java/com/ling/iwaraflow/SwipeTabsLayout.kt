package com.ling.iwaraflow

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 面板里「简介 / 评论」两页的容器：两页并排放在一起，手指横向拖动时跟着手指走，
 * 松手后顺滑地滑到目标那一页；点页签切换也是滑过去，而不是直接换掉。
 *
 * 两页都一直是 VISIBLE，靠 translationX 决定谁在屏幕里：第 i 页停在
 * `(i - 当前页) * 宽度 + 拖动位移` 处。没在屏幕里的那页偏在一侧，看不见也点不到。
 *
 * 拦截判定要偏保守：横向位移超过两倍触摸阈值、而且明显大于竖向位移才算切页，
 * 否则评论列表上下滚到一半就会被误判。
 */
class SwipeTabsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 页面停下来之后回调，参数是当前页的序号。 */
    var onPageSettled: ((index: Int) -> Unit)? = null

    var currentPage = 0
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragOffset = 0f
    private var tracker: VelocityTracker? = null

    private fun pages(): List<View> = (0 until childCount).map { getChildAt(it) }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) applyOffset(0f)
    }

    /** 按当前页和拖动位移摆好每一页。 */
    private fun applyOffset(offset: Float) {
        dragOffset = offset
        val w = width.takeIf { it > 0 } ?: return
        pages().forEachIndexed { index, page ->
            page.animate().cancel()
            page.translationX = (index - currentPage) * w + offset
        }
    }

    /** 切到第 [index] 页。[animate] 为 false 时直接就位（打开面板时用）。 */
    fun setPage(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        if (target == currentPage && dragOffset == 0f) {
            if (!animate) applyOffset(0f)
            return
        }
        val w = width.takeIf { it > 0 } ?: run {
            currentPage = target
            post { applyOffset(0f) }
            onPageSettled?.invoke(target)
            return
        }
        if (!animate) {
            currentPage = target
            applyOffset(0f)
            onPageSettled?.invoke(target)
            return
        }
        val from = currentPage
        currentPage = target
        // 先把每一页放回「按旧的当前页」的位置，再动画滑到新位置。
        pages().forEachIndexed { i, page ->
            page.animate().cancel()
            page.translationX = (i - from) * w + dragOffset
            page.animate().translationX((i - target) * w.toFloat())
                .setDuration(ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
        }
        dragOffset = 0f
        onPageSettled?.invoke(target)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseTracker()
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyOffset(clampOffset(event.x - downX))
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val velocity = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                releaseTracker()
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val far = abs(dx) >= width * SWITCH_FRACTION
                val flung = abs(velocity) >= minFlingVelocity
                val target = if (far || flung) currentPage + (if (dx < 0) 1 else -1) else currentPage
                settleTo(target.coerceIn(0, childCount - 1))
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                settleTo(currentPage)
            }
        }
        return true
    }

    /** 拖到头的那一侧不再跟手，免得把空白拉出来。 */
    private fun clampOffset(raw: Float): Float {
        val w = width.takeIf { it > 0 } ?: return 0f
        val min = if (currentPage >= childCount - 1) 0f else -w.toFloat()
        val max = if (currentPage <= 0) 0f else w.toFloat()
        return raw.coerceIn(min, max)
    }

    private fun settleTo(target: Int) {
        val w = width.takeIf { it > 0 } ?: return
        val changed = target != currentPage
        currentPage = target
        pages().forEachIndexed { i, page ->
            page.animate().translationX((i - target) * w.toFloat())
                .setDuration(ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
        }
        dragOffset = 0f
        if (changed) onPageSettled?.invoke(target)
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }

    companion object {
        /** 松手时横向至少滑过容器宽度的这个比例才算切页。 */
        const val SWITCH_FRACTION = 0.28f
        const val ANIM_MS = 220L
    }
}
