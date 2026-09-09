package com.ling.iwaraflow

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Filters feed candidates before they reach ViewPager2.
 * A video is accepted only after its real CDN source returns media bytes.
 */
class PlayableVideoGate(private val api: IwaraApi) {
    private val coordinator = Executors.newSingleThreadExecutor()
    private val probes = Executors.newFixedThreadPool(5)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun filterPlayable(
        items: List<VideoItem>,
        quality: String,
        maxItems: Int = items.size,
        callback: (List<VideoItem>) -> Unit
    ) {
        coordinator.execute {
            val futures = items.mapIndexed { index, item ->
                probes.submit<Pair<Int, VideoItem?>> {
                    index to validate(item, quality)
                }
            }
            val accepted = futures.mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }.sortedBy { it.first }
                .mapNotNull { it.second }
                .take(maxItems)
            callback(accepted)
        }
    }

    private fun validate(item: VideoItem, quality: String): VideoItem? {
        return runCatching {
            val sources = item.sources?.takeIf { it.isNotEmpty() } ?: api.resolveSourcesBlocking(item.id)
            if (sources.isEmpty()) return null

            val preferred = api.chooseSource(sources, item.selectedQuality ?: quality)
            val ordered = buildList {
                if (preferred != null) add(preferred)
                sources.forEach { source -> if (source.url != preferred?.url) add(source) }
            }
            val working = ordered.firstOrNull { probe(it.url) } ?: return null

            item.sources = sources
            item.streamUrl = working.url
            if (preferred == null || working.url != preferred.url) {
                item.selectedQuality = working.name
            }
            item
        }.getOrNull()
    }

    private fun probe(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-65535")
            .header("Accept", "*/*")
            .header("Referer", "https://www.iwara.tv/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36"
            )
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) return@use false
                val source = response.body?.source() ?: return@use false
                source.request(16)
                source.buffer.size > 0L
            }
        }.getOrDefault(false)
    }

    fun close() {
        coordinator.shutdownNow()
        probes.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
