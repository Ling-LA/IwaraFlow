package com.ling.iwaraflow

import android.os.Looper
import android.util.Base64
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
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
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistManagementTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val item = VideoItem("video", "Fixture", "Author", emptyList(), 10, thumbnailUrl = "https://image.test/old.jpg")

    @Before fun setup() {
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).apply { accountId = "account-a"; isolateLibrary = false }
        val token = "header." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis() / 1000 + 3600)
            .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "management-fixture"; accessToken = token; putSecret("verified_user_id", "account-a") }
        HistoryStore(context).use { it.libraryDatabase.delete("favorites", null, null) }
    }

    private fun <T> await(start: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val done = CompletableFuture<Result<T>>()
        start { done.complete(it) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done.get(1, TimeUnit.SECONDS)
    }

    private inner class Fixture : Closeable {
        val catalog = linkedMapOf("a" to "原夹", "b" to "目标夹", "c" to "其他夹")
        val members = linkedMapOf("a" to linkedSetOf(item.id, "other-video"), "b" to linkedSetOf<String>(), "c" to linkedSetOf(item.id))
        val writes = ArrayList<String>()
        var reject: String? = null
        var loseResponse: String? = null
        var failReadAfterAdd = false
        var accountSwitchAfterAdd = false
        private var nextReadFails = false
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.pathSegments
                    val action = "${request.method} ${request.requestUrl!!.encodedPath}"
                    fun row(id: String) = JSONObject().put("id", id).put("title", catalog[id]).put("numVideos", members[id]?.size ?: 0)
                    fun json(value: Any) = MockResponse().setBody(value.toString())
                    if (request.method != "GET") {
                        writes += action
                        if (reject == action) return MockResponse().setResponseCode(403).setBody("{}")
                    }
                    return when {
                        path == listOf("playlists") -> json(JSONObject().put("count", catalog.size).put("limit", 50).put("page", 0)
                            .put("results", JSONArray(catalog.keys.map(::row))))
                        path == listOf("light", "playlists") -> {
                            if (nextReadFails) { nextReadFails = false; MockResponse().setResponseCode(503).setBody("{}") }
                            else json(JSONArray(catalog.keys.map { row(it).put("added", request.requestUrl!!.queryParameter("id") in members[it].orEmpty()) }))
                        }
                        path.size == 2 && path[0] == "playlist" && request.method == "PUT" -> {
                            catalog[path[1]] = JSONObject(request.body.readUtf8()).getString("title")
                            MockResponse().setResponseCode(if (loseResponse == action) 503 else 204)
                        }
                        path.size == 2 && path[0] == "playlist" && request.method == "DELETE" -> {
                            catalog.remove(path[1]); members.remove(path[1])
                            MockResponse().setResponseCode(if (loseResponse == action) 503 else 204)
                        }
                        path.size == 3 && path[0] == "playlist" && path[1] in catalog -> {
                            if (request.method == "POST") {
                                members.getValue(path[1]).add(path[2])
                                if (failReadAfterAdd) { nextReadFails = true; failReadAfterAdd = false }
                                if (accountSwitchAfterAdd) AppPrefs(context).accountId = "account-b"
                            } else members.getValue(path[1]).remove(path[2])
                            MockResponse().setResponseCode(if (loseResponse == action) 503 else 204)
                        }
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
        }
        private val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
        val history = HistoryStore(context)
        val favorites = PlaylistFavorites(context, api, history)
        val store get() = favorites.store
        fun move() = await<PlaylistFavorites.Change> { favorites.moveToFolder(item, "a", "b", it) }
        fun seedCache() {
            val s = store.snapshot()
            catalog.forEach { (id, title) ->
                val folder = PlaylistFolder(id, title, members.getValue(id).size, item.thumbnailUrl)
                store.cachePage(s, 0, PlaylistVideoPage(folder, members.getValue(id).map { item.copy(id = it) }, folder.count, false))
            }
        }
        override fun close() { favorites.close(); api.close(); history.close(); server.close() }
    }

    @Test fun renameReadsBackAndKeepsIdentity() {
        Fixture().use { f ->
            f.seedCache()
            // Match the fixture's metadata, so only the title changes rather than its cover.
            val s = f.store.snapshot()
            f.store.replaceFolders(s, f.store.folders().map { it.copy(thumbnail = "") })
            val old = f.store.folders().first { it.id == "a" }
            f.store.cachePage(s, 0, PlaylistVideoPage(old, listOf(item), 2, true))
            val result = await<PlaylistFolder> { f.favorites.renameFolder("a", " 新名称 ", it) }.getOrThrow()
            assertEquals("a", result.id); assertEquals("新名称", result.title)
            assertEquals("新名称", f.store.page("a", 0)!!.folder.title)
            assertEquals(setOf(item.id, "other-video"), f.members["a"])
            assertEquals(listOf("PUT /playlist/a"), f.writes)
        }
    }

    @Test fun rejectedRenameKeepsOfficialName() {
        Fixture().use { f ->
            f.reject = "PUT /playlist/a"
            assertTrue(await<PlaylistFolder> { f.favorites.renameFolder("a", "拒绝的改名", it) }.isFailure)
            assertEquals("原夹", f.store.folders().first { it.id == "a" }.title)
            f.reject = null; f.loseResponse = "PUT /playlist/a"
            assertEquals("读回确认", await<PlaylistFolder> { f.favorites.renameFolder("a", "读回确认", it) }.getOrThrow().title)
        }
    }

    @Test fun renamedDefaultKeepsItsStableId() {
        Fixture().use { f ->
            val s = f.store.snapshot(); f.store.setDefault(s, "a"); f.store.setDedicatedMigrationTarget(s, "a")
            await<PlaylistFolder> { f.favorites.renameFolder("a", "我的新名称", it) }.getOrThrow()
            await<PlaylistFavorites.Change> { f.favorites.add(item.copy(id = "new-video"), it) }.getOrThrow()
            assertEquals("a", f.store.defaultId()); assertEquals("a", f.store.dedicatedMigrationTarget(s))
            assertEquals(listOf("PUT /playlist/a", "POST /playlist/a/new-video"), f.writes)
        }
    }

    @Test fun deleteClearsOnlyItsCacheAndDefault() {
        Fixture().use { f ->
            f.seedCache(); val s = f.store.snapshot()
            f.store.setDefault(s, "a"); f.store.setDedicatedMigrationTarget(s, "a")
            f.loseResponse = "DELETE /playlist/a"
            await<Unit> { f.favorites.deleteFolder("a", it) }.getOrThrow()
            assertNull(f.store.page("a", 0)); assertFalse(f.store.folders().any { it.id == "a" })
            assertEquals(setOf("c"), f.store.memberships(item.id)); assertTrue(f.favorites.isFavorite(item.id))
            assertTrue(f.store.defaultId().isBlank())
            assertEquals("a", f.store.dedicatedMigrationTarget(s))
            assertEquals(setOf(item.id), f.members["c"])
            await<Unit> { f.favorites.deleteFolder("a", it) }.getOrThrow()
            assertEquals("Retrying a confirmed deletion must not send it twice", listOf("DELETE /playlist/a"), f.writes)
            f.history.setLocalFavorite(item, true)
            assertTrue("Legacy migration must not recreate a deliberately deleted destination",
                await<Int> { f.favorites.importLegacy(callback = it) }.isFailure)
            assertEquals(listOf("DELETE /playlist/a"), f.writes)
        }
    }

    @Test fun foreignFolderCannotBeMutated() {
        Fixture().use { f ->
            assertTrue(await<PlaylistFolder> { f.favorites.renameFolder("not-owned", "title", it) }.isFailure)
            assertTrue(await<PlaylistFavorites.Change> { f.favorites.moveToFolder(item, "a", "not-owned", it) }.isFailure)
            assertTrue(f.writes.isEmpty()); assertTrue(item.id in f.members.getValue("a"))
        }
    }

    @Test fun removeOnlyCurrentFolderKeepsOtherCopies() {
        Fixture().use { f ->
            f.seedCache(); f.history.setLocalFavorite(item, true)
            val result = await<PlaylistFavorites.Change> { f.favorites.removeFromFolder(item, "a", it) }.getOrThrow()
            assertTrue(result.saved); assertTrue(result.changed)
            assertEquals(setOf("other-video"), f.members["a"]); assertEquals(setOf(item.id), f.members["c"])
            assertEquals(setOf("c"), f.store.memberships(item.id)); assertNull(f.store.page("a", 0))
            assertTrue(f.store.folders().first { it.id == "a" }.thumbnail.isBlank())
            assertEquals("removed_by_user", f.store.dedicatedMigrationItem(f.store.snapshot(), item.id))
            assertTrue(f.history.isLocalFavorite(item.id))
            assertEquals(listOf("DELETE /playlist/a/video"), f.writes)
        }
    }

    @Test fun moveAddsBeforeDeletingAndRetryIsIdempotent() {
        Fixture().use { f ->
            f.seedCache(); assertTrue(f.move().getOrThrow().saved)
            assertEquals(listOf("POST /playlist/b/video", "DELETE /playlist/a/video"), f.writes)
            assertEquals(setOf("b", "c"), f.store.memberships(item.id))
            assertEquals(setOf("other-video"), f.members["a"]); assertEquals(setOf(item.id), f.members["b"])
            assertNull(f.store.page("a", 0)); assertNull(f.store.page("b", 0))
            assertEquals(1, f.store.folders().first { it.id == "a" }.count)
            assertEquals(1, f.store.folders().first { it.id == "b" }.count)
            assertFalse(f.move().getOrThrow().changed); assertEquals(2, f.writes.size)
        }
    }

    @Test fun targetFailureNeverRemovesSource() {
        Fixture().use { f ->
            f.reject = "POST /playlist/b/video"
            assertTrue(f.move().isFailure)
            assertTrue(item.id in f.members.getValue("a")); assertTrue(f.members.getValue("b").isEmpty())
            assertEquals(listOf("POST /playlist/b/video"), f.writes)
        }
    }

    @Test fun uncertainTargetReadRetainsSourceUntilRetry() {
        Fixture().use { f ->
            f.failReadAfterAdd = true
            assertTrue(f.move().isFailure)
            assertTrue(item.id in f.members.getValue("a")); assertTrue(item.id in f.members.getValue("b"))
            assertEquals(0L, f.store.checkedAt(item.id)); assertEquals(1, f.writes.size)
            f.move().getOrThrow()
            assertEquals(listOf("POST /playlist/b/video", "DELETE /playlist/a/video"), f.writes)
        }
    }

    @Test fun failedSourceRemovalKeepsBothCopies() {
        Fixture().use { f ->
            f.reject = "DELETE /playlist/a/video"
            assertTrue(f.move().isFailure)
            assertEquals(setOf("a", "b", "c"), f.store.memberships(item.id))
            f.reject = null; f.move().getOrThrow()
            assertEquals(listOf("POST /playlist/b/video", "DELETE /playlist/a/video", "DELETE /playlist/a/video"), f.writes)
            assertEquals(setOf("b", "c"), f.store.memberships(item.id))
        }
    }

    @Test fun accountSwitchStopsMoveBeforeSourceDeletion() {
        Fixture().use { f ->
            f.accountSwitchAfterAdd = true
            assertTrue(f.move().isFailure)
            assertEquals(listOf("POST /playlist/b/video"), f.writes)
            assertTrue(item.id in f.members.getValue("a"))
            assertTrue(f.store.memberships(item.id).isEmpty()); assertTrue(f.store.folders().isEmpty())
        }
    }
}
