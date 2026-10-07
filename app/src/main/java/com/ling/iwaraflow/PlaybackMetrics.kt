package com.ling.iwaraflow

import android.os.SystemClock
import androidx.media3.datasource.*
import java.util.concurrent.atomic.AtomicLong

/** Bounded, process-local counters. No URLs, titles, accounts or video IDs. */
object PlaybackMetrics {
    private val firstFrames = ArrayDeque<Long>()
    private val buffers = ArrayDeque<Long>()
    private val network = AtomicLong()
    private val cache = AtomicLong()
    private val began = SystemClock.elapsedRealtime()
    private val cpuBegan = android.os.Process.getElapsedCpuTime()
    @Synchronized fun firstFrame(ms: Long) { firstFrames.addLast(ms.coerceAtLeast(0)); if (firstFrames.size > 200) firstFrames.removeFirst() }
    @Synchronized fun buffered(ms: Long) { buffers.addLast(ms.coerceAtLeast(0)); if (buffers.size > 500) buffers.removeFirst() }
    fun cached(bytes: Long) { cache.addAndGet(bytes.coerceAtLeast(0)) }
    fun transfer(delegate: TransferListener): TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, spec: DataSpec, isNetwork: Boolean) = delegate.onTransferInitializing(source, spec, isNetwork)
        override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) = delegate.onTransferStart(source, spec, isNetwork)
        override fun onBytesTransferred(source: DataSource, spec: DataSpec, isNetwork: Boolean, bytes: Int) {
            if (isNetwork) network.addAndGet(bytes.toLong())
            delegate.onBytesTransferred(source, spec, isNetwork, bytes)
        }
        override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) = delegate.onTransferEnd(source, spec, isNetwork)
    }
    internal fun percentile(values: List<Long>, p: Double): Long = if (values.isEmpty()) 0 else values.sorted()[kotlin.math.ceil(p * values.size).toInt().coerceIn(1, values.size) - 1]
    @Synchronized fun summary(): String {
        val n = network.get(); val c = cache.get(); val runtime = Runtime.getRuntime()
        return "本次进程（上限 200 次首帧 / 500 次缓冲）：\n" +
            "首帧 ${firstFrames.size} 次 · P50 ${percentile(firstFrames.toList(), .5)} ms · P95 ${percentile(firstFrames.toList(), .95)} ms\n" +
            "起播后缓冲 ${buffers.size} 次 · ${buffers.sum() / 1000.0} 秒\n" +
            "媒体网络 ${n / 1048576.0} MiB · 缓存读取 ${c / 1048576.0} MiB（均含预加载）\n" +
            "Java 堆 ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} MiB · 存活 ${(SystemClock.elapsedRealtime() - began) / 1000} 秒 · CPU ${(android.os.Process.getElapsedCpuTime() - cpuBegan) / 1000.0} 秒"
    }
}
