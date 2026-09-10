package com.ling.iwaraflow

import android.media.MediaCodecList

/**
 * The first ExoPlayer built in a process pays for enumerating the device's codecs; every later
 * player reuses that in-process result and is fast, which is why only cold start feels slow while
 * switching pages afterward loads instantly. Touching the codec list once on a background thread,
 * as early as possible, moves that one-time cost off the critical path.
 */
object MediaCodecWarmup {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
        }, "IwaraFlow-codec-warmup").apply { isDaemon = true }.start()
    }
}
