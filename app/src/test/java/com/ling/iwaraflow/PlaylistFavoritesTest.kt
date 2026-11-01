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
class PlaylistFavoritesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val item = VideoItem("video", "景色", "Author", listOf("nature"), 1)
    @Before fun setup() {
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).accountId = "account-a"
        val token = "header." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000 + 3600).toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "fixture-refresh"; accessToken = token; putSecret("verified_user_id", "account-a") }
        AppPrefs(context).isolateLibrary = false
        HistoryStore(context).use { it.libraryDatabase.delete("favorites", null, null) }
    }
    private fun <T> await(future: CompletableFuture<T>): T {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return future.get(1, TimeUnit.SECONDS)
    }
    private fun waitUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(condition())
    }
    @Test fun quickSaveUsesExistingFolderAndConfirmsOnlyAfterSuccessfulWrite() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"existing","title":"旅行","added":false}]"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    val done = CompletableFuture<Result<PlaylistFavorites.Change>>()
                    favorites.add(item) { done.complete(it) }
                    val change = await(done).getOrThrow()
                    assertTrue(change.saved); assertTrue(change.changed); assertTrue(favorites.isFavorite(item.id))
                    server.takeRequest(); assertEquals("/playlist/existing/video", server.takeRequest().path)
                    assertEquals(2, server.requestCount)
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun alreadySavedDoubleReactionDoesNotSendAnotherMutation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"existing","title":"旅行","added":true}]"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    val done = CompletableFuture<Result<PlaylistFavorites.Change>>()
                    favorites.add(item) { done.complete(it) }
                    assertEquals(PlaylistFavorites.Change(true, false), await(done).getOrThrow())
                    assertEquals(1, server.requestCount)
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun failedWriteLeavesStarOffAndFolder403DoesNotFallBackToCache() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"existing","title":"旅行","added":false}]"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    val save = CompletableFuture<Result<PlaylistFavorites.Change>>()
                    favorites.add(item) { save.complete(it) }
                    assertTrue(await(save).isFailure); assertFalse(favorites.isFavorite(item.id))
                    favorites.store.cachePage(favorites.store.snapshot(), 0, PlaylistVideoPage(PlaylistFolder("existing", "旅行"), listOf(item), 1, false))
                    val page = CompletableFuture<Result<PlaylistVideoPage>>()
                    favorites.getFolderPage("existing", 0) { page.complete(it) }
                    assertTrue(await(page).isFailure)
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun automaticMigrationCreatesDefaultOnceAndKeepsDurableCheckpointAndOriginal() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"count":0,"limit":50,"page":0,"results":[]}"""))
            server.enqueue(MockResponse().setBody("""{"id":"default","title":"默认收藏夹","numVideos":0}"""))
            server.enqueue(MockResponse().setBody("""[{"id":"default","title":"默认收藏夹","added":false}]"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                history.setLocalFavorite(item, true)
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    favorites.migrateLegacyIfNeeded()
                    waitUntil { favorites.store.migrationComplete() }
                    assertTrue(favorites.isFavorite(item.id)); assertTrue(history.isLocalFavorite(item.id))
                    assertEquals("added", PlaylistStore(context).dedicatedMigrationItem(favorites.store.snapshot(), item.id))
                    favorites.store.confirmMemberships(favorites.store.snapshot(), item.id, emptySet())
                    favorites.migrateLegacyIfNeeded(); shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(favorites.isFavorite(item.id)); assertEquals(4, server.requestCount)
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun partialMigrationRetriesOnlyRemainingVideos() {
        MockWebServer().use { server ->
            val writes = HashMap<String, Int>(); var rejectOnce = true; var created = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/playlists" && request.method == "POST") {
                        created = true; return MockResponse().setBody("""{"id":"dedicated","title":"默认收藏夹"}""")
                    }
                    if (path == "/playlists") return MockResponse().setBody("""{"count":${if (created) 3 else 2},"limit":50,"page":0,"results":[{"id":"folder","title":"旅行"},{"id":"other-folder","title":"新默认"}${if (created) ",{\"id\":\"dedicated\",\"title\":\"默认收藏夹\"}" else ""}]}""")
                    if (path == "/light/playlists") return MockResponse().setBody("""[{"id":"dedicated","title":"默认收藏夹","added":false}]""")
                    if (path.startsWith("/playlist/dedicated/")) {
                        val id = path.substringAfterLast('/'); writes[id] = (writes[id] ?: 0) + 1
                        if (writes.size == 2 && rejectOnce) { rejectOnce = false; return MockResponse().setResponseCode(503).setBody("{}") }
                        return MockResponse().setResponseCode(204)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                history.setLocalFavorite(item, true); history.setLocalFavorite(item.copy(id = "other"), true)
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    val first = CompletableFuture<Result<Int>>(); favorites.importLegacy { first.complete(it) }
                    assertTrue(await(first).isFailure); assertFalse(favorites.store.migrationComplete())
                    assertEquals("account-a", favorites.store.migrationOwner())
                    favorites.store.setDefault(favorites.store.snapshot(), "other-folder")
                    val retry = CompletableFuture<Result<Int>>(); favorites.importLegacy { retry.complete(it) }
                    assertTrue(await(retry).isSuccess); assertTrue(favorites.store.migrationComplete())
                    assertEquals(listOf(1, 2), writes.values.sorted()); assertEquals(2, history.localFavorites().size)
                    assertEquals("dedicated", favorites.store.dedicatedMigrationTarget(favorites.store.snapshot()))
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun isolatedMigrationPinsAccount() {
        AppPrefs(context).isolateLibrary = true
        AppPrefs(context).accountId = ""
        HistoryStore(context).use { stale ->
            stale.setLocalFavorite(item.copy(id = "anonymous-only"), true)
            AppPrefs(context).accountId = "account-a"
            HistoryStore(context).use { source -> source.setLocalFavorite(item, true) }
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"count":1,"results":[{"id":"folder","title":"旅行"}]}"""))
                server.enqueue(MockResponse().setBody("""{"id":"dedicated","title":"默认收藏夹"}"""))
                server.enqueue(MockResponse().setBody("""[{"id":"dedicated","title":"默认收藏夹","added":false}]"""))
                server.enqueue(MockResponse().setResponseCode(204))
                val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
                val favorites = PlaylistFavorites(context, api, stale)
                try {
                    favorites.migrateLegacyIfNeeded(); waitUntil { favorites.store.migrationComplete() }
                    assertEquals("account-a", stale.accountId)
                    server.takeRequest(); server.takeRequest(); server.takeRequest()
                    assertEquals("/playlist/dedicated/video", server.takeRequest().path)
                    assertFalse(favorites.isFavorite("anonymous-only")); assertEquals(4, server.requestCount)
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun verifiedSessionMismatchCannotImportPreviousAccountsLibrary() {
        HistoryStore(context).use { history ->
            history.setLocalFavorite(item, true)
            SecureSessionStore(context).putSecret("verified_user_id", "account-b")
            MockWebServer().use { server ->
                val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    val done = CompletableFuture<Result<Int>>(); favorites.importLegacy { done.complete(it) }
                    assertTrue(await(done).isFailure); assertEquals(0, server.requestCount)
                    assertEquals("account-b", AppPrefs(context).accountId); assertEquals("account-b", history.accountId)
                } finally { favorites.close(); api.close() }
            }
        }
    }
    @Test fun accountSwitchBeforeUiDeliveryDoesNotReportOldSaveAsNewAccountSuccess() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"folder","title":"旅行","added":false}]"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            HistoryStore(context).use { history ->
                val favorites = PlaylistFavorites(context, api, history)
                try {
                    val done = CompletableFuture<Result<PlaylistFavorites.Change>>()
                    favorites.add(item) { done.complete(it) }
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!favorites.isFavorite(item.id) && System.nanoTime() < until) Thread.sleep(10)
                    assertTrue(favorites.isFavorite(item.id))
                    AppPrefs(context).accountId = "account-b"
                    assertTrue(await(done).isFailure); assertFalse(favorites.isFavorite(item.id))
                } finally { favorites.close(); api.close() }
            }
        }
    }
}
