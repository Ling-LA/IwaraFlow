package com.ling.iwaraflow

import android.os.Looper
import androidx.media3.common.Player
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ClientBoundaryTest {
    @Test fun refreshedSignaturesReuseOnlyTheSameFileAndQuality() {
        fun source(url: String, quality: String = "1080") = VideoSource(quality, url, 1080)
        val first = "https://cdn.example/video.mp4?token=one&expires=1&version=7"
        val second = "https://cdn.example/video.mp4?token=two&expires=2&version=7"
        val changed = "https://cdn.example/video.mp4?token=two&expires=2&version=8"
        MediaCacheIdentity.register("video", listOf(source(first), source(second), source(changed)))
        val originalKey = MediaCacheIdentity.key(first)
        assertEquals(originalKey, MediaCacheIdentity.key(second))
        assertNotEquals(originalKey, MediaCacheIdentity.key(changed))
        MediaCacheIdentity.register("different-video", listOf(source(first)))
        assertNotEquals(originalKey, MediaCacheIdentity.key(first))
        MediaCacheIdentity.register("video", listOf(source(first, "540")))
        assertNotEquals(originalKey, MediaCacheIdentity.key(first))
        assertEquals("https://unknown.example/path?token=x", MediaCacheIdentity.key("https://unknown.example/path?token=x"))
    }

    @Test fun previewReturnsMetadataBeforeSlowProbeAndCancellationStopsItsCallback() {
        PlayableVideoGate.clearProcessCache()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("fixture").setBodyDelay(3, TimeUnit.SECONDS))
            val gate = PlayableVideoGate(mock(IwaraApi::class.java))
            val item = VideoItem("slow-preview", "Fixture", "", emptyList(), 0,
                sources = listOf(VideoSource("Source", server.url("/slow").toString(), 10000)))
            var shown = false; var changed = false
            try {
                gate.preview(listOf(item), "highest", { changed = true }) { shown = true }
                assertTrue("Metadata should not wait for the CDN probe", shown)
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                gate.cancelPending()
                val done = CountDownLatch(1)
                gate.inspectAll(emptyList(), "highest") { done.countDown() }
                assertTrue("New query must not be stuck behind cancelled probes", done.await(1, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idle(); assertFalse(changed)
                assertNull(item.playbackIssue)
            } finally { gate.close() }
        }
    }

    @Test fun closingCommentsCancelsPendingTranslationAndDiscardsLoadingState() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
            Translator.configure(TranslationConfig(provider = Translator.PROVIDER_OPENAI,
                aiVendor = Translator.AI_VENDOR_CUSTOM, endpoint = server.url("/v1").toString(), model = "fixture", key = "fixture"))
            try {
                val adapter = CommentListAdapter({}, { _, _ -> })
                val comment = IwaraComment("comment", "動画を見てくれてありがとう。", IwaraAuthor("a", "name", "name"), 0)
                adapter.replaceAll(listOf(comment)); adapter.translationEnabled = true
                adapter.javaClass.getDeclaredMethod("requestTranslation", IwaraComment::class.java)
                    .apply { isAccessible = true }.invoke(adapter, comment)
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val requests = adapter.javaClass.getDeclaredField("translationRequests").apply { isAccessible = true }.get(adapter) as Map<*, *>
                val request = requests.values.single() as RequestCancellation
                adapter.translationEnabled = false
                assertTrue(request.cancelled); assertTrue(requests.isEmpty())
                val translations = adapter.javaClass.getDeclaredField("translations").apply { isAccessible = true }.get(adapter) as Map<*, *>
                assertTrue(translations.isEmpty())
            } finally { Translator.configure(TranslationConfig()) }
        }
    }

    @Test fun seekingBackRestartsTheDanmakuClockAndOldPlayerIsDetached() {
        val player = mock(Player::class.java)
        doReturn(50_000L).`when`(player).currentPosition
        val view = DanmakuView(RuntimeEnvironment.getApplication()); view.player = player
        val listener = org.mockito.ArgumentCaptor.forClass(Player.Listener::class.java)
        verify(player).addListener(listener.capture())
        val position = mock(Player.PositionInfo::class.java)
        listener.value.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(0L, view.javaClass.getDeclaredField("baseline").apply { isAccessible = true }.getLong(view))
        view.player = null; verify(player).removeListener(listener.value)
    }

    @Test fun verifiedUploadOwnerSurvivesNewTokenButRejectsOtherAccounts() {
        val app = RuntimeEnvironment.getApplication()
        val secrets = SecureSessionStore(app)
        secrets.refreshToken = "old-token"; secrets.putSecret("verified_user_id", "creator")
        mockConstruction(IwaraApi::class.java).use {
            val coordinator = UploadCoordinator::class.java.getDeclaredConstructor(android.app.Application::class.java)
                .apply { isAccessible = true }.newInstance(app)
            val owner = coordinator.javaClass.getDeclaredField("owner").apply { isAccessible = true }
            owner.set(coordinator, "user:creator")
            val valid = coordinator.javaClass.getDeclaredMethod("validAccount").apply { isAccessible = true }
            assertEquals(true, valid.invoke(coordinator))
            secrets.refreshToken = "new-token"
            assertEquals(false, valid.invoke(coordinator))
            secrets.putSecret("verified_user_id", "creator")
            assertEquals(true, valid.invoke(coordinator))
            secrets.putSecret("verified_user_id", "other")
            assertEquals(false, valid.invoke(coordinator))
        }
    }
}
