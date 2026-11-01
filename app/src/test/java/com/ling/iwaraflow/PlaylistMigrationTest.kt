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
class PlaylistMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val item = VideoItem("legacy-video", "旧版风景收藏", "自然记录者", listOf("nature"), 12)

    @Before fun setup() {
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).apply { accountId = "account-a"; isolateLibrary = false }
        val token = "header." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis() / 1000 + 3600)
            .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "migration-fixture"; accessToken = token; putSecret("verified_user_id", "account-a") }
        HistoryStore(context).use { it.libraryDatabase.delete("favorites", null, null) }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Migration callback did not arrive", condition())
    }
    private inner class Fixture : Closeable {
        val catalog = linkedMapOf<String, String>()
        val members = linkedMapOf<String, MutableSet<String>>()
        val creates = ArrayList<String>()
        val mutations = ArrayList<String>()
        var loseCreateResponse = false
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!; val path = url.pathSegments
                    fun row(id: String) = JSONObject().put("id", id).put("title", catalog[id]).put("numVideos", members[id]?.size ?: 0)
                    return when {
                        path == listOf("playlists") && request.method == "POST" -> {
                            val title = JSONObject(request.body.readUtf8()).getString("title")
                            creates += title
                            val id = "created-${creates.size}"; catalog[id] = title; members[id] = linkedSetOf()
                            if (loseCreateResponse) { loseCreateResponse = false; MockResponse().setResponseCode(503).setBody("{}") }
                            else MockResponse().setBody(row(id).toString())
                        }
                        path == listOf("playlists") -> MockResponse().setBody(JSONObject().put("count", catalog.size).put("page", 0).put("limit", 50)
                            .put("results", JSONArray(catalog.keys.map(::row))).toString())
                        path == listOf("light", "playlists") -> MockResponse().setBody(JSONArray(catalog.keys.map {
                            row(it).put("added", url.queryParameter("id") in members[it].orEmpty())
                        }).toString())
                        path.size == 3 && path[0] == "playlist" && path[1] in catalog -> {
                            mutations += "${request.method} ${url.encodedPath}"
                            if (request.method == "POST") members.getValue(path[1]).add(path[2])
                            else members.getValue(path[1]).remove(path[2])
                            MockResponse().setResponseCode(204)
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
        val snapshot get() = store.snapshot()
        fun folder(id: String, title: String, vararg videos: String) { catalog[id] = title; members[id] = videos.toMutableSet() }
        fun legacy(vararg videos: VideoItem) = videos.forEach { history.setLocalFavorite(it, true) }
        fun runMigration(retryUnconfirmed: Boolean = false): Result<Int> {
            val done = CompletableFuture<Result<Int>>()
            favorites.importLegacy(retryUnconfirmed) { done.complete(it) }
            waitUntil { done.isDone }
            return done.get()
        }
        override fun close() { favorites.close(); api.close(); history.close(); server.close() }
    }

    @Test fun migrationAlwaysCreatesDedicatedFolderIgnoringFirstRememberedAndSameNameFolders() {
        Fixture().use { f ->
            f.folder("category", "1", "existing-original")
            f.folder("old-default", "默认收藏夹", item.id)
            f.folder("old-legacy-default", "默认收藏夹（旧版收藏）")
            f.store.setDefault(f.snapshot, "category"); f.legacy(item)
            assertEquals(1, f.runMigration().getOrThrow())
            assertEquals(listOf("默认收藏夹（旧版收藏） 2"), f.creates)
            assertEquals("created-1", f.store.dedicatedMigrationTarget(f.snapshot))
            assertEquals(setOf("existing-original"), f.members["category"])
            assertEquals(setOf(item.id), f.members["old-default"])
            assertEquals(listOf("POST /playlist/created-1/${item.id}"), f.mutations)
        }
    }

    @Test fun completedV1MigrationCopiesToNewDefaultWithoutDeletingOriginalsOrReaddingRemovedItems() {
        Fixture().use { f ->
            val removed = item.copy(id = "removed-after-v1")
            val officialOriginals = (1..7).map { "original-$it" }.toSet()
            f.folder("category", "1", *(officialOriginals + item.id).toTypedArray())
            f.legacy(item, removed)
            f.store.claimMigration(f.snapshot); f.store.setMigrationTarget(f.snapshot, "category")
            f.store.markMigrated(f.snapshot, item.id); f.store.markMigrated(f.snapshot, removed.id)
            context.getSharedPreferences(PlaylistStore.FILE, 0).edit().putBoolean("account-a:migration_complete", true).commit()
            assertFalse("The old completion flag must not suppress the corrected migration", f.store.migrationComplete())
            f.favorites.migrateLegacyIfNeeded(); waitUntil { f.store.migrationComplete() }
            assertEquals(setOf(item.id), f.members["created-1"])
            assertEquals(officialOriginals + item.id, f.members["category"])
            assertEquals("skipped_removed", f.store.dedicatedMigrationItem(f.snapshot, removed.id))
            assertEquals("added", f.store.dedicatedMigrationItem(f.snapshot, item.id))
            assertEquals(listOf("POST /playlist/created-1/${item.id}"), f.mutations)
            assertEquals(2, f.history.localFavorites().size)
        }
    }

    @Test fun lostCreateResponseRecoversOnlyNewIdWithoutRepeatingCreate() {
        Fixture().use { f ->
            f.folder("category", "1", "original"); f.legacy(item); f.loseCreateResponse = true
            assertTrue(f.runMigration().isFailure)
            assertNotNull(f.store.migrationCreation(f.snapshot)); assertTrue(f.mutations.isEmpty())
            assertEquals(1, f.runMigration().getOrThrow())
            assertEquals(1, f.creates.size); assertEquals("created-1", f.store.dedicatedMigrationTarget(f.snapshot))
            assertEquals(setOf("original"), f.members["category"])
        }
    }

    @Test fun ambiguousCreationRecoveryNeverAdoptsAnExistingOrSameNameFolder() {
        Fixture().use { f ->
            f.folder("old", "默认收藏夹"); f.folder("new-a", "默认收藏夹"); f.folder("new-b", "默认收藏夹")
            f.legacy(item); f.store.prepareMigrationCreation(f.snapshot, "默认收藏夹", setOf("old"))
            assertTrue(f.runMigration(retryUnconfirmed = true).isFailure)
            assertTrue(f.creates.isEmpty()); assertTrue(f.mutations.isEmpty())
            assertTrue(f.store.dedicatedMigrationTarget(f.snapshot).isBlank())
        }
    }

    @Test fun deletedDedicatedFolderIsNotRecreatedOrReplacedWithExistingCategory() {
        Fixture().use { f ->
            f.folder("category", "1"); f.legacy(item); f.store.setDedicatedMigrationTarget(f.snapshot, "deleted")
            assertTrue(f.runMigration().isFailure)
            assertTrue(f.creates.isEmpty()); assertTrue(f.mutations.isEmpty())
            assertEquals("deleted", f.store.dedicatedMigrationTarget(f.snapshot))
        }
    }

    @Test fun durableV2CheckpointDoesNotReaddUserRemovedVideoOnManualRetry() {
        Fixture().use { f ->
            f.legacy(item); assertEquals(1, f.runMigration().getOrThrow())
            f.members.getValue("created-1").clear()
            assertEquals(0, f.runMigration().getOrThrow())
            assertTrue(f.members.getValue("created-1").isEmpty())
            assertEquals(1, f.creates.size); assertEquals(1, f.mutations.size)
            assertTrue(f.history.isLocalFavorite(item.id))
        }
    }

    @Test fun uncertainEmptyCreateIsRetriedOnlyAfterExplicitRecoveryConfirmation() {
        Fixture().use { f ->
            f.legacy(item); f.store.prepareMigrationCreation(f.snapshot, "默认收藏夹", emptySet())
            assertTrue(f.runMigration().isFailure)
            assertTrue(f.creates.isEmpty()); assertTrue(f.mutations.isEmpty())
            assertEquals(1, f.runMigration(retryUnconfirmed = true).getOrThrow())
            assertEquals(listOf("默认收藏夹"), f.creates)
        }
    }
    @Test fun explicitRemovalBeforeMigrationReachesItemSuppressesFutureImportWithoutDeletingArchive() {
        Fixture().use { f ->
            f.folder("category", "1", item.id, "another-video"); f.legacy(item)
            val done = CompletableFuture<Result<PlaylistFavorites.Change>>()
            f.favorites.remove(item) { done.complete(it) }; waitUntil { done.isDone }
            assertEquals(PlaylistFavorites.Change(false, true), done.get().getOrThrow())
            assertEquals("removed_by_user", f.store.dedicatedMigrationItem(f.snapshot, item.id))
            assertEquals(0, f.runMigration().getOrThrow())
            assertTrue(f.members.getValue("created-1").isEmpty())
            assertEquals(setOf("another-video"), f.members["category"])
            assertEquals(listOf("DELETE /playlist/category/${item.id}"), f.mutations)
            assertTrue(f.history.isLocalFavorite(item.id))
        }
    }
}
