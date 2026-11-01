package com.ling.iwaraflow

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class FloatingRecoveryTest {
    private fun set(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun recover(service: FloatingVideoService) = service.javaClass.getDeclaredMethod("recoverPlayback", String::class.java)
        .apply { isAccessible = true }.invoke(service, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
    private class Fixture {
        val controller = Robolectric.buildService(FloatingVideoService::class.java).create()
        val service = controller.get()
        val player = mock(ExoPlayer::class.java)
        val cache = mock(MediaPreloadCache::class.java)
        val source = VideoSource("720", "https://media.example.test/fresh", 720)
        val media = mock(MediaSource::class.java)
        var requests = 0; var cancelled = false
        lateinit var reply: (Result<List<VideoSource>>) -> Unit
        val api = mock(IwaraApi::class.java) { call ->
            when (call.method.name) {
                "resolveSources" -> { requests++; reply = call.getArgument(1); Closeable { cancelled = true } }
                "chooseSource" -> source
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        init {
            `when`(player.currentPosition).thenReturn(13_500L)
            `when`(player.playWhenReady).thenReturn(false)
            `when`(cache.createMediaSource(source.url)).thenReturn(media)
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        set(f.service, "player", f.player); set(f.service, "cache", f.cache); set(f.service, "api", f.api)
        set(f.service, "videoId", "fixture"); set(f.service, "playbackUrl", "https://media.example.test/expired")
        try { block(f) } finally { f.controller.destroy() }
    }
    @Test fun refreshedSourcePreservesPausedPosition() = fixture { f ->
        recover(f.service); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        f.reply(Result.success(listOf(f.source))); shadowOf(Looper.getMainLooper()).idle()
        verify(f.player).setMediaSource(f.media)
        verify(f.player).seekTo(13_500L)
        verify(f.player).playWhenReady = false
        verify(f.player, never()).play()
    }
    @Test fun closingCancelsPendingRecoveryAndLateReplyCannotRestartPlayback() = fixture { f ->
        recover(f.service); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        f.service.javaClass.getDeclaredMethod("releaseWindow").apply { isAccessible = true }.invoke(f.service)
        assertTrue(f.cancelled)
        f.reply(Result.success(listOf(f.source))); shadowOf(Looper.getMainLooper()).idle()
        verify(f.player, never()).setMediaSource(f.media)
    }
    @Test fun repeatedSourceFailuresHaveBoundedAutomaticRetries() = fixture { f ->
        recover(f.service); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        f.reply(Result.failure(IOException("offline"))); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        assertEquals(2, f.requests)
        f.reply(Result.failure(IOException("still offline"))); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, f.requests)
        verify(f.player, never()).prepare()
    }
}
