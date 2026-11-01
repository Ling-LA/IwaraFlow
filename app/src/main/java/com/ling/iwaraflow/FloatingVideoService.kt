@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ling.iwaraflow

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** A permission-backed floating window; its three controls are independent of system PiP. */
class FloatingVideoService : Service() {
    private var player: ExoPlayer? = null
    private var cache: MediaPreloadCache? = null
    private var window: FrameLayout? = null
    private var controls: FloatingControls? = null
    private var returnIntent: Intent? = null
    private var videoId = ""
    private var closed = false
    private var api: IwaraApi? = null
    private var sourceRequest: java.io.Closeable? = null
    private var playbackMetrics: PlaybackMetrics.Session? = null
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recoveryAttempts = 0
    private var recovering = false
    private var playbackUrl = ""
    private var preferredQuality = "highest"
    private var status: TextView? = null
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
        playbackUrl = url; preferredQuality = intent.getStringExtra("quality") ?: AppPrefs(this).defaultQuality
        recoveryAttempts = 0; recovering = false
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n*density).toInt()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(24))
        val params = WindowManager.LayoutParams(width, width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(100) }
        val root = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dp(16).toFloat() }
            clipToOutline = true
        }
        window = root
        cache = MediaPreloadCache(this)
        val p = ExoPlayer.Builder(this).build().also { player = it }
        playbackMetrics = PlaybackMetrics.track(p)
        val video = (LayoutInflater.from(this).inflate(R.layout.view_floating_video, root, false) as PlayerView).apply { player = p }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(-1); textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xDD263845.toInt()); cornerRadius = dp(12).toFloat() }
            visibility = View.GONE
            root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) })
            setOnClickListener {
                if (!recovering) { recoveryAttempts = 0; recoverPlayback("手动重试") }
            }
        }
        val buttons = mutableListOf<View>()
        fun control(icon: Int, label: String, gravity: Int, action: () -> Unit): ImageButton = ImageButton(this).apply {
            setImageResource(icon); contentDescription = label
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
            root.addView(this, FrameLayout.LayoutParams(dp(48), dp(48), gravity).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            buttons += this
            setOnClickListener { action() }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> controls?.suspend()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controls?.resume()
                }
                false
            }
        }
        control(R.drawable.ic_floating_return, "返回软件", Gravity.TOP or Gravity.START) {
            val reopen = returnIntent ?: return@control
            savePosition()
            releaseWindow(); stopSelf()
            startActivity(reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        control(R.drawable.ic_floating_close, "关闭小窗", Gravity.TOP or Gravity.END) { savePosition(); releaseWindow(); stopSelf() }
        val toggle = control(R.drawable.ic_pause, "播放或暂停", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) {
            if (p.playWhenReady) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
            controls?.show()
        }
        toggle.setImageResource(if (intent.getBooleanExtra("playWhenReady", true)) R.drawable.ic_pause else R.drawable.ic_play)
        controls = FloatingControls(buttons)
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                toggle.setImageResource(if (ready && p.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { toggle.setImageResource(R.drawable.ic_play); controls?.show() }
                if (state == Player.STATE_READY) status?.visibility = View.GONE
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === p && !closed) recoverPlayback(error.errorCodeName)
            }
        })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        video.contentDescription = "小窗画面，轻触显示或隐藏控件，拖动移动小窗"
        video.setOnClickListener { controls?.toggle() }
        video.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
                    dragging = false; controls?.suspend()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.hypot(event.rawX - downX, event.rawY - downY) > slop) dragging = true
                    if (dragging) {
                        params.x = (startX + event.rawX - downX).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - width).coerceAtLeast(0))
                        params.y = (startY + event.rawY - downY).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                }
                MotionEvent.ACTION_UP -> { if (!dragging) video.performClick() else controls?.resume() }
                MotionEvent.ACTION_CANCEL -> controls?.resume()
            }; true
        }
        try {
            wm.addView(root, params)
            controls?.show()
            p.setMediaSource(cache!!.createMediaSource(url)); p.seekTo(intent.getLongExtra("position", 0))
            p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            p.setHandleAudioBecomingNoisy(true)
            p.playWhenReady = intent.getBooleanExtra("playWhenReady", true)
            p.prepare(); cache?.prefetchFull(url)
        } catch (_: Exception) { releaseWindow(); stopSelf() }
        return START_NOT_STICKY
    }

    /** Refresh an expired/failed source without changing the user's pause intent or position. */
    private fun recoverPlayback(reason: String) {
        val p = player ?: return
        if (closed || recovering) return
        NavigationDiagnostics.note(this, "小窗播放错误：$reason · 自动恢复 $recoveryAttempts/2")
        val local = playbackUrl.startsWith("file:") || playbackUrl.startsWith("content:")
        if (local || videoId.isBlank() || recoveryAttempts >= VideoAdapter.MAX_ERROR_RECOVERIES) {
            status?.text = if (local) "本地文件播放失败，请返回软件检查" else "播放失败，轻触重试"
            status?.visibility = View.VISIBLE
            controls?.show()
            return
        }
        recovering = true
        val position = p.currentPosition.coerceAtLeast(0L)
        recoveryAttempts++
        status?.text = "正在恢复播放…"
        status?.visibility = View.VISIBLE
        retryHandler.postDelayed({
            if (closed || player !== p) return@postDelayed
            val client = api ?: IwaraApi(this).also { api = it }
            sourceRequest = client.resolveSources(videoId) { result -> retryHandler.post {
                if (closed || player !== p) return@post
                sourceRequest = null; recovering = false
                result.onSuccess { sources ->
                    val source = client.chooseSource(sources, preferredQuality)
                    if (source == null) { recoverPlayback("没有可用清晰度"); return@onSuccess }
                    val shouldPlay = p.playWhenReady
                    playbackUrl = source.url
                    cache?.registerSources(videoId, sources)
                    cache?.keepPreloads(setOf(source.url))
                    playbackMetrics?.intentionalChange()
                    val media = cache?.createMediaSource(source.url) ?: return@onSuccess
                    p.setMediaSource(media); p.seekTo(position); p.prepare(); p.playWhenReady = shouldPlay
                    if (shouldPlay) cache?.prefetchFull(source.url)
                    NavigationDiagnostics.note(this, "小窗播放源已刷新 · 保留${if (shouldPlay) "播放" else "暂停"}状态")
                }.onFailure { error ->
                    NavigationDiagnostics.note(this, "小窗播放源刷新失败：${error.javaClass.simpleName}")
                    recoverPlayback("播放源刷新失败")
                }
            } }
        }, if (recoveryAttempts == 1) 500L else 1500L)
    }
    private fun savePosition() {
        val p = player ?: return
        if (videoId.isNotBlank()) lastPlayback = PlaybackState(videoId, p.currentPosition.coerceAtLeast(0L), p.playWhenReady)
    }
    private fun releaseWindow() {
        closed = true
        retryHandler.removeCallbacksAndMessages(null)
        sourceRequest?.close(); sourceRequest = null
        api?.close(); api = null
        playbackMetrics?.close(); playbackMetrics = null
        recovering = false; status = null
        controls?.close(); controls = null
        player?.release(); player = null
        cache?.close(); cache = null
        window?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }; window = null
        if (instance === this) instance = null
    }
    override fun onDestroy() { if (!closed) savePosition(); releaseWindow(); super.onDestroy() }
    data class PlaybackState(val videoId: String, val positionMs: Long, val playWhenReady: Boolean)

    companion object {
        private const val CHANNEL = "floating_video"
        private var instance: FloatingVideoService? = null
        private var lastPlayback: PlaybackState? = null
        fun restorePlayback(videoId: String?): PlaybackState? {
            instance?.let { it.savePosition(); it.releaseWindow(); it.stopSelf() }
            val saved = lastPlayback?.takeIf { it.videoId == videoId }
            if (saved != null) lastPlayback = null
            return saved
        }
        fun open(activity: Activity, adapter: VideoAdapter): Boolean {
            if (!Settings.canDrawOverlays(activity)) return false
            adapter.savePlaybackPosition()
            val item = adapter.activeItem() ?: return false
            val url = item.streamUrl ?: return false
            val intent = Intent(activity, FloatingVideoService::class.java)
                .putExtra("url", url).putExtra("id", item.id).putExtra("title", item.title).putExtra("position", item.resumePositionMs)
                .putExtra("playWhenReady", item.resumePlayWhenReady)
                .putExtra("quality", item.selectedQuality ?: AppPrefs(activity).defaultQuality)
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
