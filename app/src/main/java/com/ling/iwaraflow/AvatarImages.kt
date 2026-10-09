package com.ling.iwaraflow

import android.content.Context
import android.os.SystemClock
import android.widget.ImageView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.dispose
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Public avatar files have versioned URLs. Keep their small images out of the thumbnail queue. */
object AvatarImages {
    private const val SIZE = 256
    internal const val RETRY_DELAY_MS = 3_000L
    @Volatile private var shared: ImageLoader? = null
    private class Binding(val url: String) {
        var disposable: Disposable? = null
        var failedAt: Long? = null
    }

    private fun loader(context: Context): ImageLoader = shared ?: synchronized(this) {
        shared ?: createLoader(context.applicationContext).also { shared = it }
    }

    internal fun createLoader(context: Context, directory: File = File(context.cacheDir, "avatars")): ImageLoader =
        ImageLoader.Builder(context.applicationContext)
            .okHttpClient { createClient() }
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(12 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(directory).maxSizeBytes(32L * 1024 * 1024).build() }
            // Iwara changes the avatar file id in the URL when the image changes. Reopening a
            // profile should reuse these public files even when the CDN asks for revalidation.
            .respectCacheHeaders(false)
            .build()

    internal fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
        // Read the currently selected proxy, including settings changed after the first avatar.
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) {
                ProxySelector.getDefault()?.connectFailed(uri, address, error)
            }
        })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .tag(RequestScheduler.Priority::class.java, RequestScheduler.Priority.INTERACTIVE)
                .header("Referer", "https://www.iwara.tv/")
                .build())
        }
        .addInterceptor(RequestScheduler)
        .build()

    internal fun request(context: Context, url: String): ImageRequest.Builder = ImageRequest.Builder(context)
        .data(url).size(SIZE).transformations(CircleCropTransformation())
        .placeholder(R.drawable.bg_avatar_placeholder).error(R.drawable.bg_avatar_placeholder)
        .crossfade(false)

    /** A translation/count refresh must not cancel and restart the same pending avatar. */
    fun bind(view: ImageView, rawUrl: String, imageLoader: ImageLoader? = null) {
        val url = rawUrl.trim()
        val previous = view.getTag(R.id.avatar_binding) as? Binding
        if (url.isNotEmpty() && previous?.url == url && previous.disposable?.isDisposed == false &&
            (previous.failedAt == null || SystemClock.uptimeMillis() - previous.failedAt!! < RETRY_DELAY_MS)) return
        previous?.disposable?.dispose()
        view.dispose()
        view.setTag(R.id.avatar_binding, null)
        if (url.isEmpty()) {
            view.setImageResource(R.drawable.bg_avatar_placeholder)
            return
        }
        val binding = Binding(url)
        view.setTag(R.id.avatar_binding, binding)
        val request = request(view.context, url).target(view).listener(
            onError = { _, _ -> binding.failedAt = SystemClock.uptimeMillis() },
            onSuccess = { _, _ -> binding.failedAt = null }
        ).build()
        binding.disposable = (imageLoader ?: loader(view.context)).enqueue(request)
    }
}
