package com.ling.iwaraflow

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MediaPreloadCache(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    private val cache = SimpleCache(
        File(appContext.cacheDir, "video_preload"),
        LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L),
        databaseProvider
    )

    private val upstreamFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
        .setDefaultRequestProperties(mapOf("Referer" to "https://www.iwara.tv/"))
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(20_000)

    private val cacheFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)

    fun createMediaSource(url: String): MediaSource =
        mediaSourceFactory.createMediaSource(MediaItem.fromUri(url))

    /** Cache only the beginning of a video so the next swipe can start immediately. */
    fun prefetch(url: String, bytes: Long = 2L * 1024L * 1024L) {
        executor.execute {
            runCatching {
                val dataSpec = DataSpec.Builder()
                    .setUri(url)
                    .setPosition(0)
                    .setLength(bytes)
                    .build()
                CacheWriter(cacheFactory.createDataSource(), dataSpec, null, null).cache()
            }
        }
    }

    fun close() {
        executor.shutdownNow()
        runCatching { cache.release() }
    }
}
