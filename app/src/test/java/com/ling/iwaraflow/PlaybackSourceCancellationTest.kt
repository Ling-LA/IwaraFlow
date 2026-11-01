package com.ling.iwaraflow

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PlaybackSourceCancellationTest {
    @Test fun swipingCancelsOldSocketsAndSkipsObsoleteQueuedSources() {
        MockWebServer().use { server ->
            val occupied = CountDownLatch(2)
            val obsoleteReceived = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/video/slow-") -> {
                        occupied.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    request.path == "/video/obsolete" -> { obsoleteReceived.set(true); MockResponse().setResponseCode(500) }
                    request.path == "/video/current" -> MockResponse().setBody("""{"id":"current","fileUrl":"${server.url("/source/current?expires=123")}"}""")
                    else -> MockResponse().setBody("""[{"name":"720","src":{"view":"https://media.example.test/current.mp4"}}]""")
                }
            }
            server.start()
            val api = IwaraApi(RuntimeEnvironment.getApplication(), server.url("/").toString().trimEnd('/'))
            val cancelledCallback = AtomicBoolean(false)
            val complete = CountDownLatch(1)
            var current: Result<List<VideoSource>>? = null
            val first = api.resolveSources("slow-a") { cancelledCallback.set(true) }
            val second = api.resolveSources("slow-b") { cancelledCallback.set(true) }
            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS))
                api.resolveSources("obsolete") { cancelledCallback.set(true) }.close()
                api.resolveSources("current") { current = it; complete.countDown() }
                first.close(); second.close()
                assertTrue("The current video must not wait for old 35-second read timeouts", complete.await(5, TimeUnit.SECONDS))
                assertEquals("720", current!!.getOrThrow().single().name)
                assertFalse(obsoleteReceived.get())
                assertFalse(cancelledCallback.get())
            } finally { first.close(); second.close(); api.close() }
        }
    }
}
