package com.ling.iwaraflow

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
            val futures: List<Future<Pair<Int, VideoItem>>> = items.mapIndexed { index, item ->
                probes.submit<Pair<Int, VideoItem>> { Pair(index, inspectOne(item, quality)) }
            }
            val accepted: List<VideoItem> = futures
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .sortedBy { pair -> pair.first }
                .map { pair -> pair.second }
                .filter { item -> item.playbackIssue == null }
                .take(maxItems)
            callback(accepted)
        }
    }

    /** Author pages keep every item so unavailable works can show the server/resource reason. */
    fun inspectAll(items: List<VideoItem>, quality: String, callback: (List<VideoItem>) -> Unit) {
        coordinator.execute {
            val futures: List<Future<Pair<Int, VideoItem>>> = items.mapIndexed { index, item ->
                probes.submit<Pair<Int, VideoItem>> { Pair(index, inspectOne(item, quality)) }
            }
            val inspected: List<VideoItem> = futures
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .sortedBy { pair -> pair.first }
                .map { pair -> pair.second }
            callback(inspected)
        }
    }

    private fun inspectOne(item: VideoItem, quality: String): VideoItem {
        item.playbackIssue = null
        return try {
            val sources = item.sources?.takeIf { it.isNotEmpty() } ?: api.resolveSourcesBlocking(item.id)
            val preferred = api.chooseSource(sources, item.selectedQuality ?: quality)
            val ordered = buildList {
                if (preferred != null) add(preferred)
                sources.forEach { source -> if (source.url != preferred?.url) add(source) }
            }
            val working = ordered.firstOrNull { source -> probe(source.url) }
            if (working == null) {
                item.playbackIssue = "视频资源无法连接"
            } else {
                item.sources = sources
                item.streamUrl = working.url
                if (preferred == null || working.url != preferred.url) item.selectedQuality = working.name
            }
            item
        } catch (e: Exception) {
            val raw = e.message.orEmpty()
            item.playbackIssue = when {
                item.isPrivate || raw.contains("friend", true) || raw.contains("好友") -> "仅限好友观看"
                raw.contains("processing", true) || raw.contains("处理") -> "视频仍在处理中"
                raw.contains("deleted", true) || raw.contains("不存在") || raw.contains("404") -> "视频已删除或不存在"
                raw.contains("403") || raw.contains("forbidden", true) -> "没有观看权限"
                raw.isNotBlank() -> raw.take(60)
                else -> "暂时无法播放"
            }
            item
        }
    }

    private fun probe(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-65535")
            .header("Accept", "*/*")
            .header("Referer", "https://www.iwara.tv/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) return@use false
                response.body?.source()?.request(1) == true
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
