package com.ling.iwaraflow

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.Observer

/** Started only by a foreground user action; interrupted publishing is never retried automatically. */
class UploadService : Service() {
    private val task by lazy { UploadCoordinator.get(application) }
    private val observer = Observer<UploadUiState> { value ->
        if (value != null) {
            getSystemService(NotificationManager::class.java).notify(ID, notification(value))
            if (!value.busy && value.phase != "queued") stopSelf()
        }
    }
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") { task.pause(); stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "视频上传", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(task.state.value ?: UploadUiState()))
        if (!started) { started = true; task.state.observeForever(observer); task.runTask() }
        return START_NOT_STICKY
    }
    private fun notification(state: UploadUiState): Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, UploadActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, ID + 1, Intent(this, UploadService::class.java).setAction("pause"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("IwaraFlow 视频上传").setContentText(state.message).setContentIntent(open)
            .setProgress(100, state.percent, state.percent < 0).setOngoing(state.busy)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { task.pause(); stopSelf() }
    override fun onDestroy() { task.state.removeObserver(observer); if (task.busy) task.pause(); super.onDestroy() }
    companion object { private const val ID = 89; private const val CHANNEL = "video_upload" }
}
