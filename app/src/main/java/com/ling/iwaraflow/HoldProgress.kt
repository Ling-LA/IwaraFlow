package com.ling.iwaraflow

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer

/** Draw once per display frame; a separate deadline means slow frames never extend a hold. */
internal class HoldProgress(private val update: (Float) -> Unit, private val complete: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val frames = Choreographer.getInstance()
    private var startedAt = 0L
    private var duration = 1L
    var running = false
        private set
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            update(((SystemClock.uptimeMillis() - startedAt) / duration.toFloat()).coerceIn(0f, 1f))
            frames.postFrameCallback(this)
        }
    }
    private val finish = Runnable {
        if (running) {
            cancel()
            update(1f)
            complete()
        }
    }
    fun start(durationMs: Long) {
        cancel()
        duration = durationMs.coerceAtLeast(1L)
        startedAt = SystemClock.uptimeMillis()
        running = true
        update(0f)
        frames.postFrameCallback(frame)
        handler.postDelayed(finish, duration)
    }
    fun cancel() {
        running = false
        frames.removeFrameCallback(frame)
        handler.removeCallbacks(finish)
    }
}
