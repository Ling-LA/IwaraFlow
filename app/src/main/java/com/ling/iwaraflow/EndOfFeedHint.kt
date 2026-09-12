package com.ling.iwaraflow

import android.content.Context
import android.widget.EdgeEffect
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 竖滑视频流滑到底再往上拉时明确告诉用户“没有更多视频了”，而不是只有一个回弹。
 *
 * 挂在 ViewPager2 内部 RecyclerView 的边缘效果上：只有底边被拉动、而且页面确认后面
 * 没有更多数据时才提示；提示有节流，一次连续拉动只弹一次。
 */
object EndOfFeedHint {
    const val MESSAGE = "没有更多视频了"
    private const val THROTTLE_MS = 2_500L

    fun install(pager: ViewPager2, hasMore: () -> Boolean) {
        val list = pager.getChildAt(0) as? RecyclerView ?: return
        var lastShown = 0L
        list.edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
                if (direction != DIRECTION_BOTTOM) return EdgeEffect(view.context)
                return object : EdgeEffect(view.context) {
                    override fun onPull(deltaDistance: Float, displacement: Float) {
                        super.onPull(deltaDistance, displacement)
                        maybeShow(view.context)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onPull(deltaDistance: Float) {
                        super.onPull(deltaDistance)
                        maybeShow(view.context)
                    }

                    private fun maybeShow(context: Context) {
                        if (hasMore()) return
                        val now = System.currentTimeMillis()
                        if (now - lastShown < THROTTLE_MS) return
                        lastShown = now
                        show(context)
                    }
                }
            }
        }
    }

    fun show(context: Context) {
        Toast.makeText(context, MESSAGE, Toast.LENGTH_SHORT).show()
    }
}
