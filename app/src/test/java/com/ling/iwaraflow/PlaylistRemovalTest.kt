package com.ling.iwaraflow

import android.os.Looper
import android.util.Base64
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistRemovalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val item = VideoItem("only-this-video", "中性视频", "示例作者", listOf("nature"), 10)
    private val both = """[{"id":"a","title":"风景","added":true},{"id":"b","title":"音乐","added":true}]"""
    private val remaining = """[{"id":"a","title":"风景","added":false},{"id":"b","title":"音乐","added":true}]"""
    private val none = """[{"id":"a","title":"风景","added":false},{"id":"b","title":"音乐","added":false}]"""
    @Before fun setup() {
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).apply { accountId = "account-a"; isolateLibrary = false }
        val token = "header." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000 + 3600)
            .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "removal-fixture"; accessToken = token; putSecret("verified_user_id", "account-a") }
        HistoryStore(context).use { it.libraryDatabase.delete("favorites", null, null) }
    }
    private fun await(done: CompletableFuture<Result<PlaylistFavorites.Change>>): Result<PlaylistFavorites.Change> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done.get(1, TimeUnit.SECONDS)
    }
    private fun remove(favorites: PlaylistFavorites): Result<PlaylistFavorites.Change> {
        val done = CompletableFuture<Result<PlaylistFavorites.Change>>()
        favorites.remove(item) { done.complete(it) }; return await(done)
    }
    private fun response(body: String) = MockResponse().setBody(body)

    @Test fun removesOnlyTargetVideoFromAllAuthoritativeFoldersAndRechecksBeforeSuccess() {
        MockWebServer().use { server ->
            server.enqueue(response(both)); server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(response(none)); server.enqueue(response(none))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    favorites.store.putFolder(favorites.store.snapshot(), PlaylistFolder("a", "风景", 4))
                    favorites.store.putFolder(favorites.store.snapshot(), PlaylistFolder("b", "音乐", 5))
                    assertEquals(PlaylistFavorites.Change(false, true), remove(favorites).getOrThrow())
                    assertFalse(favorites.isFavorite(item.id))
                    assertEquals(listOf(3, 4), favorites.store.folders().map { it.count })
                    assertEquals(PlaylistFavorites.Change(false, false), remove(favorites).getOrThrow())
                    val requests = (0 until 5).map { server.takeRequest() }
                    assertEquals(listOf("GET", "DELETE", "DELETE", "GET", "GET"), requests.map { it.method })
                    assertEquals(listOf("/playlist/a/${item.id}", "/playlist/b/${item.id}"), requests.filter { it.method == "DELETE" }.map { it.path })
                } finally { favorites.close(); api.close() }
            }
        }
    }

    @Test fun partialFailureKeepsRemainingStarAndRetryOnlyDeletesRemainingMembership() {
        MockWebServer().use { server ->
            server.enqueue(response(both)); server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(response(remaining)); server.enqueue(response(remaining)); server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(response(none))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    assertTrue(remove(favorites).isFailure); assertTrue(favorites.isFavorite(item.id))
                    assertEquals(setOf("b"), favorites.store.memberships(item.id))
                    assertEquals(PlaylistFavorites.Change(false, true), remove(favorites).getOrThrow())
                    assertFalse(favorites.isFavorite(item.id))
                    val deletes = (0 until 7).map { server.takeRequest() }.filter { it.method == "DELETE" }.map { it.path }
                    assertEquals(listOf("/playlist/a/${item.id}", "/playlist/b/${item.id}", "/playlist/b/${item.id}"), deletes)
                } finally { favorites.close(); api.close() }
            }
        }
    }

    @Test fun uncertainVerificationRetainsConservativeStarAndDoesNotDuplicateMutationOnRetry() {
        MockWebServer().use { server ->
            server.enqueue(response(both)); server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(response(none))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    assertTrue(remove(favorites).isFailure); assertTrue(favorites.isFavorite(item.id))
                    assertEquals(0L, favorites.store.checkedAt(item.id))
                    assertEquals(PlaylistFavorites.Change(false, false), remove(favorites).getOrThrow())
                    assertFalse(favorites.isFavorite(item.id)); assertEquals(5, server.requestCount)
                } finally { favorites.close(); api.close() }
            }
        }
    }

    @Test fun accountSwitchDuringRemovalCannotDeleteSecondFolderOrUpdateNewAccount() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") { AppPrefs(context).accountId = "account-b"; return MockResponse().setResponseCode(204) }
                    return response(both)
                }
            }
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    assertTrue(remove(favorites).isFailure)
                    assertFalse(favorites.isFavorite(item.id)); assertEquals(2, server.requestCount)
                    assertTrue(favorites.store.memberships(item.id).isEmpty())
                } finally { favorites.close(); api.close() }
            }
        }
    }
}
