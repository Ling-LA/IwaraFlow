package com.ling.iwaraflow

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** A permission-backed floating window; its three controls are independent of system PiP. */
class FloatingVideoService : Service() {
    private var player: ExoPlayer? = null
    private var cache: MediaPreloadCache? = null
    private var window: FrameLayout? = null
    private var returnIntent: Intent? = null
    private var videoId = ""
    private var closed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: run { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "小窗播放", NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>("return") ?: Intent(this, MainActivityV3::class.java)
        returnIntent = target
        val pending = PendingIntent.getActivity(this, 71, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(71, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("IwaraFlow 小窗播放").setContentText(intent.getStringExtra("title"))
            .setContentIntent(pending).setOngoing(true).build())
        releaseWindow()
        closed = false; instance = this
        videoId = intent.getStringExtra("id").orEmpty()
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n*density).toInt()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(24))
        val params = WindowManager.LayoutParams(width, width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(100) }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        window = root
        cache = MediaPreloadCache(this)
        val p = ExoPlayer.Builder(this).build().also { player = it }
        val video = PlayerView(this).apply { useController = false; player = p }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        fun control(text: String, label: String, gravity: Int, action: () -> Unit): TextView = TextView(this).apply {
            this.text = text; contentDescription = label; textSize = 25f; setTextColor(-1)
            this.gravity = Gravity.CENTER; setBackgroundColor(0x66000000)
            root.addView(this, FrameLayout.LayoutParams(dp(48), dp(48), gravity))
            setOnClickListener { action() }
        }
        control("↖", "返回软件", Gravity.TOP or Gravity.START) {
            val reopen = returnIntent ?: return@control
            savePosition()
            releaseWindow(); stopSelf()
            startActivity(reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        control("×", "关闭小窗", Gravity.TOP or Gravity.END) { savePosition(); releaseWindow(); stopSelf() }
        val toggle = control("Ⅱ", "播放或暂停", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) {
            if (p.playWhenReady) p.pause() else p.play()
        }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { toggle.text = if (playing) "Ⅱ" else "▶" }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) toggle.text = "▶" }
        })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        video.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downX).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - width).coerceAtLeast(0))
                    params.y = (startY + event.rawY - downY).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(root, params) }
                }
            }; true
        }
        try {
            wm.addView(root, params)
            p.setMediaSource(cache!!.createMediaSource(url)); p.seekTo(intent.getLongExtra("position", 0))
            p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            p.prepare(); p.play(); cache?.prefetchFull(url)
        } catch (_: Exception) { releaseWindow(); stopSelf() }
        return START_NOT_STICKY
    }
    private fun savePosition() { if (videoId.isNotBlank()) lastPosition = videoId to (player?.currentPosition ?: 0L) }
    private fun releaseWindow() {
        closed = true
        player?.release(); player = null
        cache?.close(); cache = null
        window?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }; window = null
        if (instance === this) instance = null
    }
    override fun onDestroy() { if (!closed) savePosition(); releaseWindow(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "floating_video"
        private var instance: FloatingVideoService? = null
        private var lastPosition: Pair<String, Long>? = null
        fun restorePosition(videoId: String?): Long? {
            instance?.let { it.savePosition(); it.releaseWindow(); it.stopSelf() }
            return lastPosition?.takeIf { it.first == videoId }?.second.also {
                if (it != null) lastPosition = null
            }
        }
        fun open(activity: Activity, adapter: VideoAdapter): Boolean {
            if (!Settings.canDrawOverlays(activity)) return false
            adapter.savePlaybackPosition()
            val item = adapter.activeItem() ?: return false
            val url = item.streamUrl ?: return false
            val intent = Intent(activity, FloatingVideoService::class.java)
                .putExtra("url", url).putExtra("id", item.id).putExtra("title", item.title).putExtra("position", item.resumePositionMs)
                .putExtra("return", Intent(activity.intent).setClass(activity, activity.javaClass))
            return runCatching {
                activity.startForegroundService(intent)
                adapter.pauseAll(); activity.moveTaskToBack(true); true
            }.getOrDefault(false)
        }
        fun request(activity: Activity, adapter: VideoAdapter, fallback: () -> Unit) {
            if (open(activity, adapter)) return
            if (Settings.canDrawOverlays(activity)) { fallback(); return }
            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle("开启小窗播放")
                .setMessage("允许悬浮窗后，小窗提供左上返回、右上关闭和底部播放暂停。授权后返回，再点击小窗即可。")
                .setPositiveButton("去授权") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
                }.setNeutralButton("使用系统画中画") { _, _ -> fallback() }.setNegativeButton("取消", null).show()
        }
    }
}
