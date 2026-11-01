package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real folder UI + migration against a local HTTPS implementation of the verified official protocol. */
@RunWith(AndroidJUnit4::class)
class PlaylistDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
    }
    private fun windowRoots(): List<View> {
        if (android.os.Build.VERSION.SDK_INT >= 29) return WindowInspector.getGlobalWindowViews()
        // WindowInspector became public in API 29. API 28 instrumentation reads the same
        // application-local window list; it never examines another process or surface.
        val type = Class.forName("android.view.WindowManagerGlobal")
        val global = type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (type.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>).toList()
    }
    private fun texts() = windowRoots().flatMap(::descendants).filterIsInstance<TextView>().filter { it.isShown }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    @Test fun migratePickCreateAndBrowseOfficialFolders() {
        val context = instrumentation.targetContext
        val prefs = AppPrefs(context)
        val session = SecureSessionStore(context)
        val previousAccount = prefs.accountId
        val previousIsolation = prefs.isolateLibrary
        val previousRefresh = session.refreshToken
        val previousAccess = session.accessToken
        val previousVerified = session.secret("verified_user_id")
        val account = "playlist-device-${SystemClock.uptimeMillis()}"
        val fixture = VideoItem("legacy-landscape", "山川与晨光 · 旧版收藏", "自然记录者", listOf("nature"), 12)
        val second = fixture.copy(id = "new-ocean", title = "海边的色彩 · 新收藏")
        val catalog = linkedMapOf<String, String>()
        val members = linkedMapOf<String, MutableSet<String>>()
        val delayedWrites = AtomicBoolean(false)
        var createCount = 0
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        fun row(id: String) = JSONObject().put("id", id).put("title", catalog[id]).put("numVideos", members[id]?.size ?: 0)
        fun json(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(catalog) {
                val path = request.requestUrl!!.pathSegments
                when {
                    path == listOf("playlists") && request.method == "POST" -> {
                        val title = JSONObject(request.body.readUtf8()).getString("title")
                        val id = "folder-${++createCount}"; catalog[id] = title; members[id] = linkedSetOf()
                        json(row(id))
                    }
                    path == listOf("playlists") -> json(JSONObject().put("count", catalog.size).put("page", 0).put("limit", 50)
                        .put("results", JSONArray(catalog.keys.map(::row))))
                    path == listOf("light", "playlists") -> {
                        val videoId = request.requestUrl!!.queryParameter("id")
                        json(JSONArray(catalog.keys.map { row(it).put("added", videoId in members[it].orEmpty()) }))
                    }
                    path.size == 3 && path[0] == "playlist" && path[1] in catalog -> {
                        if (request.method == "POST") members.getValue(path[1]).add(path[2]) else members.getValue(path[1]).remove(path[2])
                        MockResponse().setResponseCode(204).apply { if (delayedWrites.get()) setHeadersDelay(1, TimeUnit.SECONDS) }
                    }
                    path.size == 2 && path[0] == "playlist" && path[1] in catalog -> {
                        val videos = members.getValue(path[1]).map { id ->
                            val video = if (id == fixture.id) fixture else second
                            JSONObject().put("id", video.id).put("title", video.title).put("numLikes", video.likes).put("numViews", 1200)
                                .put("createdAt", "2026-10-15T08:00:00Z")
                                .put("user", JSONObject().put("id", "neutral-author").put("name", video.author).put("username", "nature"))
                                .put("tags", JSONArray().put(JSONObject().put("id", "nature")))
                        }
                        json(JSONObject().put("playlist", row(path[1])).put("count", videos.size)
                            .put("page", 0).put("limit", 50).put("results", JSONArray(videos)))
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        // MockWebServer.url resolves its listening address. Keep that work on the test
        // worker before constructing/replacing the Activity's API on the main thread.
        val localApiRoot = server.url("/").toString().trimEnd('/')
        fun localApi(): IwaraApi = IwaraApi(context, localApiRoot).also { api ->
            field(api, "client").set(api, OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
        }
        fun loginFixture() {
            session.refreshToken = "local-playlist-fixture"
            session.accessToken = "fixture." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000 + 3600)
                .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
            session.putSecret("verified_user_id", account)
            prefs.accountId = account
        }
        var folders: PlaylistFoldersActivity? = null
        var videos: SavedVideosActivity? = null
        try {
            // Start logged out; no initial Activity request can reach an authenticated public endpoint.
            session.clearAuthentication(); prefs.accountId = account; prefs.isolateLibrary = true
            HistoryStore(context).use { it.accountId = account; it.setLocalFavorite(fixture, true) }
            folders = instrumentation.startActivitySync(Intent(context, PlaylistFoldersActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaylistFoldersActivity
            val activity = folders!!
            lateinit var favorites: PlaylistFavorites
            main {
                (field(activity, "favorites").get(activity) as PlaylistFavorites).close()
                (field(activity, "api").get(activity) as IwaraApi).close()
                val api = localApi(); field(activity, "api").set(activity, api)
                favorites = PlaylistFavorites(activity, api, field(activity, "history").get(activity) as HistoryStore)
                field(activity, "favorites").set(activity, favorites)
                loginFixture(); favorites.migrateLegacyIfNeeded(); invoke(activity, "reload")
            }
            await("Automatic legacy migration did not complete") { favorites.store.migrationComplete() }
            await("Folder overview did not render the migrated preview") { texts().any { it.text.toString() == fixture.title } }
            assertEquals(1, createCount)
            HistoryStore(context).use { it.accountId = account; assertTrue(it.isLocalFavorite(fixture.id)) }
            ProfileUiDeviceTest().screenshot("playlist-folders-migrated-018")
            var result: Result<PlaylistFavorites.Change>? = null
            main { favorites.showPicker(activity, second) { result = it } }
            await("Picker did not render the official default folder") { texts().filterIsInstance<CheckBox>().any { it.text.startsWith("默认收藏夹") } }
            main {
                texts().filterIsInstance<CheckBox>().first { it.text.startsWith("默认收藏夹") }.performClick()
                texts().first { it.text.toString() == "＋ 新建收藏夹" }.performClick()
            }
            await("Create folder dialog did not appear") { texts().filterIsInstance<EditText>().isNotEmpty() }
            main {
                texts().filterIsInstance<EditText>().first().setText("旅行与风景")
                texts().first { it.text.toString() == "创建" }.performClick()
            }
            await("New folder was not included in picker") { texts().filterIsInstance<CheckBox>().any { it.text.startsWith("旅行与风景") } }
            main {
                val boxes = texts().filterIsInstance<CheckBox>()
                assertTrue("Creating a folder discarded the previous selection", boxes.first { it.text.startsWith("默认收藏夹") }.isChecked)
                assertTrue(boxes.first { it.text.startsWith("旅行与风景") }.isChecked)
            }
            ProfileUiDeviceTest().screenshot("playlist-picker-create-018")
            delayedWrites.set(true)
            main {
                texts().first { it.text.toString() == "完成" }.performClick()
                assertTrue("Checkboxes must be disabled while the selection is saving", texts().filterIsInstance<CheckBox>().all { !it.isEnabled })
            }
            await("Picker did not confirm successful official writes") { result != null }
            assertTrue(result!!.getOrThrow().saved)
            synchronized(catalog) { assertTrue(members.values.all { second.id in it }); assertEquals(2, createCount) }
            fun defaultPreviewHasBothVideos(): Boolean {
                val list = descendants(activity.window.decorView).filterIsInstance<RecyclerView>().first()
                val card = list.findViewHolderForAdapterPosition(0)?.itemView ?: return false
                val labels = descendants(card).filterIsInstance<TextView>().map { it.text.toString() }
                return "默认收藏夹" in labels && fixture.title in labels && second.title in labels
            }
            await("Saving a video did not reload the invalidated default-folder preview") { defaultPreviewHasBothVideos() }
            main { invoke(activity, "reload") }
            await("Overview did not show both folders and the updated default-folder preview") {
                texts().any { it.text.toString() == "旅行与风景" } && defaultPreviewHasBothVideos()
            }
            ProfileUiDeviceTest().screenshot("playlist-folders-synced-018")

            // Use the actual folder-card route, then inject the local API before authenticating.
            main { session.clearAuthentication() }
            val monitor = instrumentation.addMonitor(SavedVideosActivity::class.java.name, null, false)
            main {
                val list = descendants(activity.window.decorView).filterIsInstance<RecyclerView>().first()
                list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            }
            videos = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? SavedVideosActivity
            instrumentation.removeMonitor(monitor)
            val videoActivity = requireNotNull(videos) { "Folder card did not open its video page" }
            main {
                assertEquals("folder-1", videoActivity.intent.getStringExtra(SavedVideosActivity.EXTRA_PLAYLIST_ID))
                (field(videoActivity, "playlistFavorites").get(videoActivity) as PlaylistFavorites).close()
                (field(videoActivity, "api").get(videoActivity) as IwaraApi).close()
                val api = localApi(); field(videoActivity, "api").set(videoActivity, api)
                field(videoActivity, "playlistFavorites").set(videoActivity,
                    PlaylistFavorites(videoActivity, api, field(videoActivity, "history").get(videoActivity) as HistoryStore))
                loginFixture(); invoke(videoActivity, "loadLibraryPage", true)
            }
            await("Official playlist page did not render both saved videos") {
                videoActivity.findViewById<RecyclerView>(R.id.savedVideos).adapter?.itemCount == 2
            }
            ProfileUiDeviceTest().screenshot("playlist-videos-018")
        } finally {
            main { videos?.finish(); folders?.finish() }
            instrumentation.waitForIdleSync()
            HistoryStore(context).use { it.accountId = account; it.libraryDatabase.delete("favorites", null, null) }
            session.refreshToken = previousRefresh; session.accessToken = previousAccess
            session.putSecret("verified_user_id", previousVerified)
            prefs.accountId = previousAccount; prefs.isolateLibrary = previousIsolation
            server.shutdown()
        }
    }
}
