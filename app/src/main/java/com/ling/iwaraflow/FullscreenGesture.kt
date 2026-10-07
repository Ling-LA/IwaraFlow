package com.ling.iwaraflow

/** Resolve the zone once on DOWN, so crossing a boundary never triggers another action. */
internal object FullscreenGesture {
    enum class Action { DISLIKE, DOUBLE_REACTION, SPEED }
    fun action(x: Float, y: Float, width: Int, height: Int, horizontal: Boolean): Action {
        val fraction = if (horizontal) x / width.coerceAtLeast(1) else y / height.coerceAtLeast(1)
        return when { fraction < 1f / 3 -> Action.DISLIKE; fraction < 2f / 3 -> Action.DOUBLE_REACTION; else -> Action.SPEED }
    }
}
