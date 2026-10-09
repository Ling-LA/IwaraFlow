package com.ling.iwaraflow

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Looper
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AvatarImagesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private class Pending : Disposable {
        override val job = CompletableDeferred<ImageResult>()
        override var isDisposed = false
        override fun dispose() { isDisposed = true; job.cancel() }
    }
    private class Requests {
        val calls = mutableListOf<Pair<ImageRequest, Pending>>()
        val loader = object : ImageLoader by Mockito.mock(ImageLoader::class.java) {
            override fun enqueue(request: ImageRequest): Disposable = Pending().also { calls += request to it }
        }
    }

    @Test fun repeatedTranslationAndProfileUpdatesKeepPendingAvatarRequest() {
        val view = ImageView(context)
        val requests = Requests()
        repeat(20) { AvatarImages.bind(view, "https://i.iwara.tv/image/avatar/one/avatar.jpg", requests.loader) }
        assertEquals(1, requests.calls.size)
        assertFalse(requests.calls.single().second.isDisposed)
    }

    @Test fun reusedRowCancelsOldUserAndEmptyAvatarClearsIt() {
        val view = ImageView(context)
        val requests = Requests()
        AvatarImages.bind(view, "https://i.iwara.tv/image/avatar/one/avatar.jpg", requests.loader)
        AvatarImages.bind(view, "https://i.iwara.tv/image/avatar/two/avatar.jpg", requests.loader)
        assertTrue(requests.calls[0].second.isDisposed)
        assertFalse(requests.calls[1].second.isDisposed)
        assertEquals(2, requests.calls.size)
        AvatarImages.bind(view, "", requests.loader)
        assertTrue(requests.calls[1].second.isDisposed)
        assertNotNull(view.drawable)
        assertNull(view.getTag(R.id.avatar_binding))
        assertEquals(2, requests.calls.size)
    }

    @Test fun failedRequestCanRetryWithoutARapidRebindStorm() {
        val view = ImageView(context)
        val requests = Requests()
        val url = "https://i.iwara.tv/image/avatar/one/avatar.jpg"
        AvatarImages.bind(view, url, requests.loader)
        val request = requests.calls.single().first
        request.listener!!.onError(request, ErrorResult(null, request, IOException("fixture")))
        repeat(10) { AvatarImages.bind(view, url, requests.loader) }
        assertEquals(1, requests.calls.size)
        SystemClock.sleep(AvatarImages.RETRY_DELAY_MS + 1)
        AvatarImages.bind(view, url, requests.loader)
        assertEquals(2, requests.calls.size)
        assertTrue(requests.calls[0].second.isDisposed)
    }

    @Test fun disposedRequestIsRestartedWhenSameAvatarIsBoundAgain() {
        val view = ImageView(context)
        val requests = Requests()
        val url = "https://i.iwara.tv/image/avatar/one/avatar.jpg"
        AvatarImages.bind(view, url, requests.loader)
        requests.calls.single().second.dispose()
        AvatarImages.bind(view, url, requests.loader)
        assertEquals(2, requests.calls.size)
        assertFalse(requests.calls.last().second.isDisposed)
    }

    @Test fun avatarsReuseMemoryAndPersistentDiskCacheAndNewFileIdsRefresh() {
        val server = MockWebServer().apply { start() }
        val directory = Files.createTempDirectory("avatar-cache").toFile()
        fun loader() = AvatarImages.createLoader(context, directory).newBuilder()
            .interceptorDispatcher(Dispatchers.IO).networkObserverEnabled(false).build()
        var images = loader()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val bytes = ByteArrayOutputStream().also { out ->
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(0xFF285C7B.toInt()); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
                }
            }.toByteArray()
            fun response() = MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "image/png")
                .setHeader("Cache-Control", "no-cache")
            val firstUrl = server.url("/image/avatar/one/avatar.png").toString()
            fun fetch(url: String): SuccessResult {
                val pending = worker.submit<ImageResult> {
                    runBlocking { images.execute(AvatarImages.request(context, url).build()) }
                }
                // Coil starts its request on Main. Let Robolectric dispatch that work while
                // the network/decode runs, rather than blocking Main with runBlocking.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!pending.isDone && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                val result = pending.get(1, TimeUnit.SECONDS)
                assertTrue("Expected image success: $result", result is SuccessResult)
                return result as SuccessResult
            }
            server.enqueue(response())
            assertEquals(DataSource.NETWORK, fetch(firstUrl).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, fetch(firstUrl).dataSource)
            images.shutdown()
            images = loader()
            assertEquals(DataSource.DISK, fetch(firstUrl).dataSource)
            assertEquals(1, server.requestCount)
            server.enqueue(response())
            assertEquals(DataSource.NETWORK, fetch(server.url("/image/avatar/two/avatar.png").toString()).dataSource)
            assertEquals(2, server.requestCount)
            assertEquals("https://www.iwara.tv/", server.takeRequest().getHeader("Referer"))
        } finally { images.shutdown(); worker.shutdownNow(); server.shutdown() }
    }
}
