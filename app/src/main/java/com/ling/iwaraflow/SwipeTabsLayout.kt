package com.ling.iwaraflow

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 能左右滑动切页签的容器：里面装着评论列表和简介页，本身不动，只把明显的横向滑动
 * 截下来交给 [onSwipe]（-1 = 向左滑，+1 = 向右滑）。竖向滚动照常交给列表。
 *
 * 判定要偏保守：横向位移得超过两倍触摸阈值，而且明显大于竖向位移，
 * 否则评论列表上下滚到一半会被误判成切页。
 */
class SwipeTabsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onSwipe: ((direction: Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var intercepting = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; intercepting = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!intercepting && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    intercepting = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepting) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                intercepting = false
                if (abs(dx) >= SWIPE_DISTANCE_DP * resources.displayMetrics.density) {
                    onSwipe?.invoke(if (dx < 0) -1 else 1)
                }
            }
            MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return true
    }

    companion object {
        /** 松手时横向至少滑了这么远才算切页（dp）。 */
        const val SWIPE_DISTANCE_DP = 56f
    }
}
