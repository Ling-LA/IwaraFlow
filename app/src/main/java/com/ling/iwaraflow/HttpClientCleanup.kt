package com.ling.iwaraflow

import okhttp3.OkHttpClient
import java.util.concurrent.Executors

/** Closing a TLS socket writes close_notify and therefore must not run in Activity.onDestroy. */
internal object HttpClientCleanup {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "IwaraFlow-http-cleanup").apply { isDaemon = true }
    }

    fun close(client: OkHttpClient) {
        executor.execute {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
