package com.ling.iwaraflow

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import java.lang.ref.WeakReference

/** Uses the existing player Activity, leaving Settings and its unsaved form in the task. */
interface PlaybackGuideHost {
    val playbackGuideAdapter: VideoAdapter
    fun setPlaybackGuideFullscreen(enabled: Boolean)
}

object PlaybackGuideNavigation : Application.ActivityLifecycleCallbacks {
    private var lastPlayer: WeakReference<Activity>? = null
    private var returnPage: WeakReference<Activity>? = null
    private var destination: WeakReference<Activity>? = null
    private var pending = false

    fun open(settings: Activity) {
        val player = lastPlayer?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it.taskId == settings.taskId }
        returnPage = WeakReference(settings)
        destination = player?.let(::WeakReference)
        pending = true
        settings.startActivity(Intent(settings, player?.javaClass ?: MainActivityV3::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    }

    private fun remember(activity: Activity) {
        if (activity is PlaybackGuideHost && activity.findViewById<View>(R.id.playerView)?.isShown == true)
            lastPlayer = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            remember(activity)
            if (!pending || activity !is PlaybackGuideHost ||
                (destination?.get()?.let { it !== activity } == true)) return@post
            pending = false
            destination = WeakReference(activity)
            PlaybackGuide.show(activity, false) {
                val settings = returnPage?.get()
                returnPage = null; destination = null
                if (settings != null && !settings.isFinishing && !settings.isDestroyed) {
                    activity.startActivity(Intent(activity, settings.javaClass)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
                } else {
                    activity.startActivity(Intent(activity, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
                }
            }
        }
    }
    override fun onActivityPaused(activity: Activity) { remember(activity) }
    override fun onActivityDestroyed(activity: Activity) {
        PlaybackGuide.dismiss(activity)
        if (lastPlayer?.get() === activity) lastPlayer = null
        if (destination?.get() === activity) { destination = null; returnPage = null; pending = false }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}
