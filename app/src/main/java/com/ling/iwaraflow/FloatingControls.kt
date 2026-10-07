package com.ling.iwaraflow

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityManager

/** One inactivity timer shared by all floating controls; dragging never changes playback. */
internal class FloatingControls(private val buttons: List<View>) {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private val hide = Runnable { setVisible(false) }
    val visible: Boolean get() = buttons.any { it.visibility == View.VISIBLE }
    private fun setVisible(value: Boolean) {
        buttons.forEach { it.visibility = if (value) View.VISIBLE else View.INVISIBLE }
    }
    fun show() { if (!closed) { setVisible(true); resume() } }
    fun toggle() {
        if (closed) return
        suspend()
        if (visible) setVisible(false) else show()
    }
    fun suspend() { handler.removeCallbacks(hide) }
    fun resume() {
        suspend()
        if (closed || !visible) return
        val accessibility = buttons.firstOrNull()?.context?.getSystemService(AccessibilityManager::class.java)
        // Keep controls reachable while a screen reader is exploring the overlay.
        if (accessibility?.isTouchExplorationEnabled == true) return
        val delay = if (android.os.Build.VERSION.SDK_INT >= 29) accessibility?.getRecommendedTimeoutMillis(
            HIDE_DELAY_MS.toInt(), AccessibilityManager.FLAG_CONTENT_CONTROLS)?.toLong() ?: HIDE_DELAY_MS else HIDE_DELAY_MS
        handler.postDelayed(hide, delay)
    }
    fun close() { closed = true; suspend() }
    companion object { const val HIDE_DELAY_MS = 3000L }
}
