package com.ling.iwaraflow

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import java.security.MessageDigest

/** Android permits clipboard reads only once our foreground window has focus. */
class ClipboardLinkHandler : Application.ActivityLifecycleCallbacks {
    private val listeners = mutableMapOf<Activity, ViewTreeObserver.OnWindowFocusChangeListener>()
    private var inspectedTimestamp = Long.MIN_VALUE

    override fun onActivityResumed(activity: Activity) {
        val decor = activity.window.decorView
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && !activity.isInPictureInPictureMode) {
                removeListener(activity)
                check(activity)
            }
        }
        listeners[activity] = listener
        decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        decor.post {
            if (listeners[activity] === listener && activity.hasWindowFocus() && !activity.isInPictureInPictureMode) {
                removeListener(activity)
                check(activity)
            }
        }
    }

    internal fun check(activity: Activity, attempt: Int = 0) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = AppPrefs(activity)
        if (!prefs.autoOpenClipboardLinks) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        // Description reads do not trigger Android's clipboard-access notification.
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull() ?: return
        if (description.extras?.getBoolean(OWN_COPY) == true || description.timestamp == inspectedTimestamp) return
        if (description.timestamp > 0 && description.timestamp == prefs.inspectedClipboardTimestamp) return
        if (!description.hasMimeType("text/*")) return
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (description.classificationStatus != android.content.ClipDescription.CLASSIFICATION_COMPLETE) {
                if (attempt < 3) activity.window.decorView.postDelayed({
                    if (activity.hasWindowFocus() && !activity.isInPictureInPictureMode) check(activity, attempt + 1)
                }, 300L)
                return
            }
            if (description.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_URL) <= 0f) return
        }
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        // The clipboard can change during classification. Never process an older snapshot.
        if (clip.description.timestamp != description.timestamp) return
        inspectedTimestamp = description.timestamp
        prefs.inspectedClipboardTimestamp = description.timestamp
        if (clip.description.extras?.getBoolean(OWN_COPY) == true) return
        // Only the current primary clip, never clipboard history or other clip items.
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val link = IwaraSharedLink.parse(item.text?.toString() ?: item.uri?.toString().orEmpty()) ?: return
        val token = fingerprint(link, clip.description.timestamp)
        if (token == prefs.handledClipboardLink) return
        runCatching {
            activity.startActivity(Intent(activity, MainActivityV3::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(IwaraSharedLink.EXTRA_URL, link.url))
        }.onSuccess { prefs.handledClipboardLink = token }
    }

    private fun removeListener(activity: Activity) {
        listeners.remove(activity)?.let { activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
    }
    override fun onActivityPaused(activity: Activity) = removeListener(activity)
    override fun onActivityDestroyed(activity: Activity) = removeListener(activity)
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    companion object {
        const val OWN_COPY = "com.ling.iwaraflow.OWN_COPY"
        internal fun fingerprint(link: IwaraSharedLink, timestamp: Long): String =
            MessageDigest.getInstance("SHA-256").digest("${link.url}\n$timestamp".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
