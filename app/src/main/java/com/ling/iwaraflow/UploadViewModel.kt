package com.ling.iwaraflow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel

/** UI ownership is separate from the durable foreground upload task. */
class UploadViewModel(app: Application) : AndroidViewModel(app) {
    private val task = UploadCoordinator.get(app)
    val state get() = task.state
    val rules get() = task.rules
    val rulesStatus get() = task.rulesStatus
    val uri get() = task.uri
    val filename get() = task.filename
    val size get() = task.size
    val busy get() = task.busy
    val draft get() = task.draft
    var publicationUncertain: Boolean
        get() = task.publicationUncertain
        set(value) { task.publicationUncertain = value }
    fun loggedIn() = task.loggedIn()
    fun loadRules() = task.loadRules()
    fun select(value: Uri, name: String, bytes: Long) = task.select(value, name, bytes)
    fun submit(draft: VideoUploadDraft) = task.submit(draft)
    fun saveDraft(draft: VideoUploadDraft) = task.saveDraft(draft)
    fun pause() = task.pause()
}
