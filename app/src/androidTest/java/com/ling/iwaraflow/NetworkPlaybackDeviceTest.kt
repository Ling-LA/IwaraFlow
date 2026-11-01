@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** Real TLS sockets, persistent media bytes and hardware playback; only neutral local media. */
@RunWith(AndroidJUnit4::class)
class NetworkPlaybackDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun onMain(condition: () -> Boolean): Boolean {
        var result = false
        main { result = condition() }
        return result
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun counter(name: String): Long = (PlaybackMetrics::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(null) as AtomicLong).get()

    @Test fun httpsCacheReplayAndPausedNetworkRecoveryKeepTransferCountersSeparate() {
        val context = ins.targetContext
        val bytes = ins.context.assets.open("navigation-fixture.mp4").use { it.readBytes() }
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            protocols = listOf(Protocol.HTTP_1_1)
        }
        val waitingForNetwork = CountDownLatch(1)
        val resumeNetwork = CountDownLatch(1)
        val gateUsed = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().contains("/waiting-") && gateUsed.compareAndSet(false, true)) {
                    waitingForNetwork.countDown()
                    if (!resumeNetwork.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                }
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                val start = range?.groupValues?.get(1)?.toInt() ?: 0
                if (start >= bytes.size) return MockResponse().setResponseCode(416)
                    .setHeader("Content-Range", "bytes */${bytes.size}")
                val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                return MockResponse().setResponseCode(if (range == null) 200 else 206)
                    .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                    .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${bytes.size}") }
                    .setBody(Buffer().write(bytes, start, end - start + 1))
            }
        }
        val originalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val prefs = AppPrefs(context)
        val oldMeteredLimit = prefs.meteredPreloadMb
        var host: Activity? = null
        var activePlayer: ExoPlayer? = null
        var metrics: PlaybackMetrics.Session? = null
        val cache = MediaPreloadCache(context)
        try {
            // Test-process-only trust. The production HTTPS data source and byte listeners stay intact.
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            prefs.meteredPreloadMb = 0
            server.start()
            val stamp = System.nanoTime()
            val cachedUrl = server.url("/cached-$stamp.mp4").toString()
            val waitingUrl = server.url("/waiting-$stamp.mp4").toString()
            cache.registerSources("network-cached-$stamp", listOf(VideoSource("fixture", cachedUrl, 1)))
            cache.registerSources("network-waiting-$stamp", listOf(VideoSource("fixture", waitingUrl, 1)))
            cache.keepPreloads(setOf(cachedUrl, waitingUrl))
            val networkBefore = counter("network")
            val preloadBefore = counter("preloadNetwork")
            cache.prefetchFull(cachedUrl)
            await("Real HTTPS preloading did not cache the complete fixture") { cache.progress(cachedUrl).fraction == 1.0 }
            assertTrue("Preload network bytes must be counted", counter("preloadNetwork") - preloadBefore >= bytes.size)
            assertEquals("Preloading must not count as playback networking", networkBefore, counter("network"))

            host = ins.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = host!!
            val cachedBefore = counter("cache")
            val preloadAfterDownload = counter("preloadNetwork")
            val requestsAfterDownload = server.requestCount
            lateinit var p: ExoPlayer
            val renderedFirstFrame = AtomicBoolean(false)
            main {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                p = ExoPlayer.Builder(activity).build().apply { volume = 0f }
                activePlayer = p
                metrics = PlaybackMetrics.track(p)
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { renderedFirstFrame.set(true) }
                })
                // Explicit receiver: a local variable named player previously captured this
                // assignment, leaving PlayerView unbound and the decoder without its surface.
                val playerView = PlayerView(activity).apply { this.player = p; useController = true }
                assertSame("The test must attach the decoder to a visible PlayerView", p, playerView.player)
                activity.setContentView(playerView)
                p.setMediaSource(cache.createMediaSource(cachedUrl)); p.prepare(); p.play()
            }
            await("ExoPlayer did not decode and advance the cached video") {
                onMain { renderedFirstFrame.get() && p.videoSize.width > 0 && p.isPlaying && p.currentPosition > 350 }
            }
            // CacheDataSource reports its accumulated cache reads when the source closes.
            main { p.pause(); p.stop() }
            await("Actual playback cache reads were not reported on source close") { counter("cache") > cachedBefore }
            assertEquals("Cached playback unexpectedly requested HTTPS", requestsAfterDownload, server.requestCount)
            assertEquals(networkBefore, counter("network"))
            assertEquals(preloadAfterDownload, counter("preloadNetwork"))

            // A second URL has a real connection but no response until the gate opens.
            // Pause during the wait; receiving data must not itself change the user's intent.
            main {
                metrics?.intentionalChange()
                p.setMediaSource(cache.createMediaSource(waitingUrl)); p.prepare(); p.play()
            }
            assertTrue("Player never requested the gated HTTPS URL", waitingForNetwork.await(10, TimeUnit.SECONDS))
            assertTrue(onMain { p.playbackState == Player.STATE_BUFFERING })
            main { p.pause() }
            assertFalse(onMain { p.playWhenReady })
            resumeNetwork.countDown()
            await("Player did not become ready after local networking recovered") {
                onMain { p.playbackState == Player.STATE_READY && p.videoSize.width > 0 }
            }
            var pausedAt = 0L
            main { pausedAt = p.currentPosition; assertFalse(p.playWhenReady); assertFalse(p.isPlaying) }
            SystemClock.sleep(500)
            main { assertEquals("Network recovery must preserve pause", pausedAt, p.currentPosition) }
            val playbackAfterRecovery = counter("network")
            assertTrue("Uncached playback must be measured as playback network bytes", playbackAfterRecovery > networkBefore)
            assertEquals("Playing must not inflate preloading bytes", preloadAfterDownload, counter("preloadNetwork"))
            main { p.play() }
            await("Explicit play did not resume after the paused network recovery") {
                onMain { p.isPlaying && p.currentPosition > pausedAt + 350 }
            }
            ProfileUiDeviceTest().screenshot("network-cache-and-pause-recovery")
        } finally {
            resumeNetwork.countDown()
            main { metrics?.close(); activePlayer?.release(); host?.finish() }
            cache.close()
            prefs.meteredPreloadMb = oldMeteredLimit
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
            server.shutdown()
        }
    }
}
