package com.ling.iwaraflow

import android.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class VideoLikeApiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences(VideoLikeStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).accountId = "account-a"
        val token = "header." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000 + 3600).toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "fixture-refresh"; accessToken = token }
    }
    private fun like(api: IwaraApi, enabled: Boolean): Result<Unit> {
        val answer = CompletableFuture<Result<Unit>>()
        api.likeVideo("video", enabled) { answer.complete(it) }
        return answer.get(5, TimeUnit.SECONDS)
    }
    @Test fun successfulWritePersistsBeforeCallbackAndMissingListStatusDoesNotClearIt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"results":[{"id":"video","title":"Example","numLikes":11}]}"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                assertTrue(like(api, true).isSuccess)
                assertTrue(api.getVideosBlocking("trending").single().liked)
                val write = server.takeRequest()
                assertEquals("POST", write.method); assertEquals("/video/video/like", write.path)
                val read = server.takeRequest()
                assertTrue(read.path!!.startsWith("/videos?")); assertNotNull(read.getHeader("Authorization"))
                assertTrue(VideoLikeStore(context).apply(VideoItem("video", "", "", emptyList(), 0)).liked)
            } finally { api.close() }
        }
    }
    @Test fun failedWriteDoesNotChangeSharedState() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            val states = VideoLikeStore(context)
            states.confirm(states.snapshot(), "video", true, 11)
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try { assertTrue(like(api, false).isFailure); assertTrue(states.apply(VideoItem("video", "", "", emptyList(), 0)).liked) }
            finally { api.close() }
        }
    }
    @Test fun unlikeWinsOverCachedFavoritesMembership() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"results":[{"video":{"id":"video","title":"Example","numLikes":11}}],"count":1}"""))
            val states = VideoLikeStore(context); states.confirm(states.snapshot(), "video", true, 11)
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                assertTrue(like(api, false).isSuccess)
                val fresh = api.getFavoritesPageBlocking().videos.single()
                assertFalse(fresh.liked); assertEquals(10, fresh.likes)
                assertEquals("DELETE", server.takeRequest().method)
            } finally { api.close() }
        }
    }
    @Test fun aDetailRequestStartedBeforeTheLikeCannotRestoreItsOldState() {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val complete = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    entered.countDown(); complete.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"id":"video","title":"Example","liked":false,"numLikes":10}""")
                }
            }
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                val answer = CompletableFuture<Result<VideoItem>>()
                api.getVideo("video") { answer.complete(it) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val states = VideoLikeStore(context); states.confirm(states.snapshot(), "video", true, 11)
                complete.countDown()
                assertTrue(answer.get(5, TimeUnit.SECONDS).getOrThrow().liked)
            } finally { complete.countDown(); api.close() }
        }
    }
    @Test fun playbackSourceResolutionAlsoRecoversExistingServerLikes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"video","liked":true,"numLikes":42}"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                // No media URL in this fixture: status still must be recovered before playback fails.
                assertTrue(runCatching { api.resolveSourcesBlocking("video") }.isFailure)
                val restored = VideoLikeStore(context).apply(VideoItem("video", "", "", emptyList(), 0))
                assertTrue(restored.liked); assertEquals(42, restored.likes)
                assertEquals(1, server.requestCount)
            } finally { api.close() }
        }
    }

}
