@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ling.iwaraflow

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.concurrent.atomic.AtomicLong

/** Bounded, process-local counters. No URLs, titles, accounts or video IDs. */
object PlaybackMetrics {
    private val firstFrames = ArrayDeque<Long>()
    private val buffers = ArrayDeque<Long>()
    private val seeks = ArrayDeque<Long>()
    private val sourceQueue = ArrayDeque<Long>()
    private val sourceTime = ArrayDeque<Long>()
    private var sourceFailures = 0L
    private var sourceCancellations = 0L
    private var droppedFrames = 0L
    private val errors = linkedMapOf<String, Long>()
    private var decoder = "尚未启动"
    private val network = AtomicLong()
    private val cache = AtomicLong()
    private val preloadNetwork = AtomicLong()
    private val preloadCache = AtomicLong()
    private val began = SystemClock.elapsedRealtime()
    private val cpuBegan = android.os.Process.getElapsedCpuTime()
    @Synchronized fun firstFrame(ms: Long) { firstFrames.addLast(ms.coerceAtLeast(0)); if (firstFrames.size > 200) firstFrames.removeFirst() }
    @Synchronized fun buffered(ms: Long) { buffers.addLast(ms.coerceAtLeast(0)); if (buffers.size > 500) buffers.removeFirst() }
    @Synchronized private fun sought(ms: Long) { seeks.addLast(ms.coerceAtLeast(0)); if (seeks.size > 500) seeks.removeFirst() }
    @Synchronized fun sourceResolved(queueMs: Long, resolveMs: Long, success: Boolean, cancelled: Boolean) {
        if (cancelled) { sourceCancellations++; return }
        sourceQueue.addLast(queueMs.coerceAtLeast(0)); sourceTime.addLast(resolveMs.coerceAtLeast(0))
        if (sourceQueue.size > 200) { sourceQueue.removeFirst(); sourceTime.removeFirst() }
        if (!success) sourceFailures++
    }
    @Synchronized private fun error(name: String) { errors[name] = (errors[name] ?: 0L) + 1 }
    @Synchronized private fun dropped(count: Int) { droppedFrames += count.coerceAtLeast(0) }
    @Synchronized private fun decoder(name: String) { decoder = name.take(120) }
    fun track(player: ExoPlayer): Session = Session(player)

    /** Feed and floating playback share the same counters and buffering classification. */
    class Session internal constructor(private val player: ExoPlayer) : java.io.Closeable {
        private val started = SystemClock.elapsedRealtime()
        private var first = false
        private val buffering = PlaybackBufferTracker(SystemClock::elapsedRealtime) { ms, intentional ->
            if (intentional) sought(ms) else buffered(ms)
        }
        private fun update() = buffering.update(player.playbackState,
            player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        private val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!first) { first = true; firstFrame(SystemClock.elapsedRealtime() - started); buffering.firstFrame() }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) buffering.intentionalChange()
            }
            override fun onPlaybackStateChanged(playbackState: Int) = update()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = update()
            override fun onPlayerError(error: PlaybackException) { PlaybackMetrics.error(error.errorCodeName) }
        }
        private val analytics = object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) = dropped(droppedFrames)
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) = decoder(decoderName)
        }
        init { player.addListener(listener); player.addAnalyticsListener(analytics) }
        fun intentionalChange() = buffering.intentionalChange()
        override fun close() {
            buffering.finish()
            player.removeListener(listener); player.removeAnalyticsListener(analytics)
        }
    }
    fun cached(bytes: Long, preload: Boolean = false) { (if (preload) preloadCache else cache).addAndGet(bytes.coerceAtLeast(0)) }
    fun transfer(delegate: TransferListener, preload: Boolean = false): TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, spec: DataSpec, isNetwork: Boolean) = delegate.onTransferInitializing(source, spec, isNetwork)
        override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) = delegate.onTransferStart(source, spec, isNetwork)
        override fun onBytesTransferred(source: DataSource, spec: DataSpec, isNetwork: Boolean, bytes: Int) {
            if (isNetwork) (if (preload) preloadNetwork else network).addAndGet(bytes.toLong().coerceAtLeast(0))
            delegate.onBytesTransferred(source, spec, isNetwork, bytes)
        }
        override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) = delegate.onTransferEnd(source, spec, isNetwork)
    }
    internal fun percentile(values: List<Long>, p: Double): Long = if (values.isEmpty()) 0 else values.sorted()[kotlin.math.ceil(p * values.size).toInt().coerceIn(1, values.size) - 1]
    @Synchronized fun summary(): String {
        val n = network.get(); val c = cache.get(); val runtime = Runtime.getRuntime()
        return "本次进程（上限 200 次首帧 / 500 次缓冲）：\n" +
            "首帧 ${firstFrames.size} 次 · P50 ${percentile(firstFrames.toList(), .5)} ms · P95 ${percentile(firstFrames.toList(), .95)} ms\n" +
            "播放源解析 ${sourceTime.size} 次 · 排队 P50/P95 ${percentile(sourceQueue.toList(), .5)}/${percentile(sourceQueue.toList(), .95)} ms · 请求 P50/P95 ${percentile(sourceTime.toList(), .5)}/${percentile(sourceTime.toList(), .95)} ms · 失败 $sourceFailures · 取消 $sourceCancellations\n" +
            "起播后等待 ${buffers.size} 次 · ${buffers.sum() / 1000.0} 秒（不含暂停、主动跳转/换源）\n" +
            "主动跳转/换源 ${seeks.size} 次 · ${seeks.sum() / 1000.0} 秒 · 解码丢帧 $droppedFrames · 解码器 $decoder\n" +
            "播放错误 ${errors.values.sum()} 次${errors.entries.joinToString(prefix = if (errors.isEmpty()) "" else " · ") { "${it.key}:${it.value}" }}\n" +
            "实际播放网络 ${n / 1048576.0} MiB · 缓存读取 ${c / 1048576.0} MiB\n" +
            "预缓存网络 ${preloadNetwork.get() / 1048576.0} MiB · 已缓存读取 ${preloadCache.get() / 1048576.0} MiB\n" +
            "Java 堆 ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} MiB · 存活 ${(SystemClock.elapsedRealtime() - began) / 1000} 秒 · CPU ${(android.os.Process.getElapsedCpuTime() - cpuBegan) / 1000.0} 秒"
    }
}

/** Wall-clock waiting only while playback is requested; seeks are reported separately. */
internal class PlaybackBufferTracker(private val now: () -> Long, private val report: (Long, Boolean) -> Unit) {
    private var rendered = false
    private var began: Long? = null
    private var intentional = false
    fun firstFrame() { rendered = true }
    fun intentionalChange() { intentional = true }
    fun update(state: Int, shouldPlay: Boolean) {
        if (rendered && state == Player.STATE_BUFFERING && shouldPlay) {
            if (began == null) began = now()
        } else finish()
        if (state == Player.STATE_READY || state == Player.STATE_ENDED) intentional = false
    }
    fun finish() { began?.let { report((now() - it).coerceAtLeast(0), intentional) }; began = null }
}
