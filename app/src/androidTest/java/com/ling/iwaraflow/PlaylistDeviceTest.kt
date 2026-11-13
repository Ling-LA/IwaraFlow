package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
import java.io.File
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
        val previousAutoNext = prefs.autoNext
        val previousRefresh = session.refreshToken
        val previousAccess = session.accessToken
        val previousVerified = session.secret("verified_user_id")
        val account = "playlist-device-${SystemClock.uptimeMillis()}"
        val fixture = VideoItem("legacy-landscape", "山川与晨光 · 旧版收藏", "自然记录者", listOf("nature"), 12)
        val second = fixture.copy(id = "new-ocean", title = "海边的色彩 · 新收藏")
        val catalog = linkedMapOf<String, String>()
        val members = linkedMapOf<String, MutableSet<String>>()
        val delayedWrites = AtomicBoolean(false)
        val deleted = mutableListOf<Pair<String, String>>()
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
                    path.size == 2 && path[0] == "playlist" && path[1] in catalog && request.method == "PUT" -> {
                        catalog[path[1]] = JSONObject(request.body.readUtf8()).getString("title")
                        MockResponse().setResponseCode(204)
                    }
                    path.size == 2 && path[0] == "playlist" && path[1] in catalog && request.method == "DELETE" -> {
                        catalog.remove(path[1]); members.remove(path[1])
                        MockResponse().setResponseCode(204)
                    }
                    path.size == 3 && path[0] == "playlist" && path[1] in catalog -> {
                        if (request.method == "POST") members.getValue(path[1]).add(path[2]) else {
                            members.getValue(path[1]).remove(path[2]); deleted += path[1] to path[2]
                        }
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
        // Replace dependencies before onResume or queued page callbacks can observe a
        // half-injected fixture. The production account-isolation checks stay intact.
        fun injectVideoPage(opened: SavedVideosActivity) {
            assertFalse("Fixture page finished before dependency injection", opened.isFinishing)
            (field(opened, "playlistFavorites").get(opened) as PlaylistFavorites).close()
            (field(opened, "api").get(opened) as IwaraApi).close()
            val api = localApi(); field(opened, "api").set(opened, api)
            val favorites = PlaylistFavorites(opened, api, field(opened, "history").get(opened) as HistoryStore)
            field(opened, "playlistFavorites").set(opened, favorites)
            val feed = opened.playbackGuideAdapter
            (field(feed, "favoriteController").get(feed) as? PlaylistFavorites)?.close()
            (field(feed, "favoriteSubscription").get(feed) as? java.io.Closeable)?.close()
            field(feed, "favoriteController").set(feed, null); field(feed, "favoriteSubscription").set(feed, null)
            field(feed, "api").set(feed, api)
            val panel = (field(opened, "comments").get(opened) as CommentsHost).panel
            field(panel, "api").set(panel, api)
            loginFixture()
            field(opened, "playlistPageSession").set(opened, favorites.store.snapshot())
            invoke(opened, "loadLibraryPage", true)
        }
        fun openFolder(activity: PlaylistFoldersActivity, index: Int): SavedVideosActivity {
            val app = activity.application
            var injectionFailure: Throwable? = null
            val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
                private var injected = false
                override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {}
                override fun onActivityStarted(activity: Activity) {
                    if (activity is SavedVideosActivity && !injected) {
                        injected = true
                        runCatching { injectVideoPage(activity) }.onFailure { injectionFailure = it }
                    }
                }
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            val monitor = instrumentation.addMonitor(SavedVideosActivity::class.java.name, null, false)
            main { app.registerActivityLifecycleCallbacks(lifecycle) }
            try {
                // Check and click in one main-loop turn; a preview update can otherwise
                // detach the holder between a separate await and performClick.
                await("Folder card $index did not become clickable") {
                    val list = descendants(activity.window.decorView).filterIsInstance<RecyclerView>().first()
                    val holder = list.findViewHolderForAdapterPosition(index)
                    if (!activity.hasWindowFocus() || list.isComputingLayout ||
                        holder?.bindingAdapterPosition != index || !holder.itemView.isLaidOut) false
                    else {
                        session.clearAuthentication()
                        assertTrue(holder.itemView.performClick())
                        true
                    }
                }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? SavedVideosActivity
                instrumentation.waitForIdleSync()
                injectionFailure?.let { throw AssertionError("Could not inject the local playlist API", it) }
                return requireNotNull(opened) { "Folder card did not open its video page" }
            } finally {
                main { app.unregisterActivityLifecycleCallbacks(lifecycle) }
                instrumentation.removeMonitor(monitor)
            }
        }
        var folders: PlaylistFoldersActivity? = null
        var videos: SavedVideosActivity? = null
        val clip = File.createTempFile("playlist-device-", ".mp4", context.cacheDir).apply {
            instrumentation.context.assets.open("navigation-fixture.mp4").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
        try {
            // Start logged out; no initial Activity request can reach an authenticated public endpoint.
            session.clearAuthentication(); prefs.accountId = account; prefs.isolateLibrary = true; prefs.autoNext = false
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
            fun pickerPanel(): View? = windowRoots().flatMap(::descendants)
                .firstOrNull { it.tag == "playlist_picker_panel" && it.isShown }
            fun dragPickerHandle(fraction: Float) {
                var x = 0f; var y = 0f; var distance = 0f
                main {
                    val panel = requireNotNull(pickerPanel())
                    val handle = panel.findViewWithTag<View>("playlist_drag_handle")
                    assertTrue("The handle needs a usable touch target", handle.height >= 24 * context.resources.displayMetrics.density - 1)
                    val location = IntArray(2); handle.getLocationOnScreen(location)
                    x = location[0] + handle.width / 2f; y = location[1] + handle.height / 2f
                    distance = panel.height * fraction
                }
                val started = SystemClock.uptimeMillis()
                fun inject(action: Int, dy: Float) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y + dy, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Could not inject the handle gesture", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, 0f)
                for (step in 1..12) {
                    SystemClock.sleep(50)
                    inject(MotionEvent.ACTION_MOVE, distance * step / 12)
                }
                inject(MotionEvent.ACTION_UP, distance)
            }
            dragPickerHandle(.08f)
            await("Short handle drag must rebound without closing the picker") {
                pickerPanel()?.let { kotlin.math.abs(it.translationY) < 1f } == true
            }
            main { texts().filterIsInstance<CheckBox>().first { it.text.startsWith("默认收藏夹") }.performClick() }
            dragPickerHandle(.4f)
            await("Dragging the picker handle down did not dismiss it") { pickerPanel() == null }
            assertNull("Dismissing an unsubmitted draft must not report a save", result)
            synchronized(catalog) { assertTrue("Dismissing an unsubmitted draft must not write official memberships", members.values.none { second.id in it }) }
            main { favorites.showPicker(activity, second) { result = it } }
            await("Reopening the picker did not reload official memberships") {
                texts().filterIsInstance<CheckBox>().any { it.text.startsWith("默认收藏夹") && !it.isChecked }
            }
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
                assertFalse("The picker must not be drag-dismissed while a save is pending", requireNotNull(pickerPanel()).findViewWithTag<View>("playlist_drag_handle").isEnabled)
                assertFalse(texts().first { it.contentDescription == "关闭收藏夹" }.isEnabled)
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

            videos = openFolder(activity, 0)
            val videoActivity = requireNotNull(videos)
            assertEquals("folder-1", videoActivity.intent.getStringExtra(SavedVideosActivity.EXTRA_PLAYLIST_ID))
            await("Official playlist page did not render both saved videos") {
                videoActivity.findViewById<RecyclerView>(R.id.savedVideos).adapter?.itemCount == 2
            }
            ProfileUiDeviceTest().screenshot("playlist-videos-018")

            // Exercise the real player star, not the controller directly. Both videos use a
            // bundled neutral clip and all authenticated traffic stays on the local fixture.
            main {
                @Suppress("UNCHECKED_CAST")
                val rows = field(videoActivity, "items").get(videoActivity) as List<VideoItem>
                rows.forEach { it.sources = listOf(VideoSource("fixture", Uri.fromFile(clip).toString(), 1)) }
                val selected = rows.indexOfFirst { it.id == second.id }
                videoActivity.findViewById<RecyclerView>(R.id.savedVideos)
                    .findViewHolderForAdapterPosition(selected)!!.itemView.performClick()
            }
            lateinit var star: View
            await("Saved video did not open with its selected favorite star") {
                // ViewPager2 keeps neighbouring cards attached. isShown alone also matches
                // their off-screen stars; the interaction must target its current page.
                val pager = videoActivity.findViewById<ViewPager2>(R.id.savedPager)
                val holder = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
                val current = holder?.itemView?.findViewById<View>(R.id.favorite)
                if (current != null) star = current
                current?.isShown == true && holder != null &&
                    (field(holder, "bound").get(holder) as? VideoItem)?.id == second.id &&
                    videoActivity.playbackGuideAdapter.activeItem()?.let { it.id == second.id && it.localFavorite } == true
            }
            val downAt = SystemClock.uptimeMillis()
            main {
                MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, star.width / 2f, star.height / 2f, 0).let {
                    star.dispatchTouchEvent(it); it.recycle()
                }
            }
            SystemClock.sleep(650)
            main {
                MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, star.width / 2f, star.height / 2f, 0).let {
                    star.dispatchTouchEvent(it); it.recycle()
                }
            }
            await("Long-pressing the saved star did not open folder management") {
                texts().filterIsInstance<CheckBox>().let { boxes -> boxes.size == 2 && boxes.all { it.isChecked } }
            }
            synchronized(catalog) { assertTrue("Long press must not remove any official membership", deleted.isEmpty()) }
            main { texts().first { it.contentDescription == "关闭收藏夹" }.performClick() }
            await("Folder picker did not close") { texts().none { it.contentDescription == "关闭收藏夹" } }
            main { star.performClick() }
            await("Short-tapping the saved star did not cancel its global favorite state") {
                videoActivity.playbackGuideAdapter.activeItem()?.localFavorite == false
            }
            synchronized(catalog) {
                assertEquals(setOf("folder-1" to second.id, "folder-2" to second.id), deleted.toSet())
                assertEquals(2, deleted.size)
                assertTrue(members.values.none { second.id in it })
                assertEquals(setOf(fixture.id), members.getValue("folder-1"))
            }
            main { videoActivity.findViewById<View>(R.id.savedFeedBack).performClick() }
            await("Returning to the playlist kept the removed video in its list") {
                videoActivity.findViewById<View>(R.id.savedListPage).isShown &&
                    videoActivity.findViewById<RecyclerView>(R.id.savedVideos).adapter?.itemCount == 1 &&
                    descendants(videoActivity.findViewById(R.id.savedVideos)).filterIsInstance<TextView>().any { it.text.toString() == fixture.title }
            }
            ProfileUiDeviceTest().screenshot("playlist-short-tap-removal-0181")
            main { videoActivity.findViewById<View>(R.id.savedBack).performClick() }
            await("Returning to folders did not refresh the default-folder preview after cancellation") {
                val list = descendants(activity.window.decorView).filterIsInstance<RecyclerView>().first()
                val card = list.findViewHolderForAdapterPosition(0)?.itemView
                val labels = card?.let(::descendants)?.filterIsInstance<TextView>()?.map { it.text.toString() }.orEmpty()
                activity.window.decorView.isShown && fixture.title in labels && second.title !in labels &&
                    favorites.store.folders().firstOrNull { it.id == "folder-1" }?.count == 1
            }

            fun longFolder(index: Int) {
                await("Folder card $index did not become long-clickable") {
                    val list = descendants(activity.window.decorView).filterIsInstance<RecyclerView>().first()
                    val holder = list.findViewHolderForAdapterPosition(index)
                    if (!activity.hasWindowFocus() || list.isComputingLayout ||
                        holder?.bindingAdapterPosition != index || !holder.itemView.isLaidOut) false
                    else {
                        assertTrue("Visible folder card did not handle a long press", holder.itemView.performLongClick())
                        true
                    }
                }
            }
            fun choose(label: String) {
                var x = 0f; var y = 0f
                await("Visible dialog action '$label' did not appear") {
                    val target = windowRoots().asReversed().flatMap(::descendants).filterIsInstance<TextView>()
                        .firstOrNull { it.isShown && it.isEnabled && it.text.toString() == label }
                    val bounds = android.graphics.Rect()
                    if (target != null && target.isLaidOut && target.rootView.hasWindowFocus() &&
                        target.getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
                        // getGlobalVisibleRect is relative to the root/window. Dialog windows
                        // have a nonzero screen origin, so input injection needs screen coordinates.
                        val screen = IntArray(2); target.getLocationOnScreen(screen)
                        x = screen[0] + target.width / 2f; y = screen[1] + target.height / 2f
                        android.util.Log.i("PlaylistDeviceTest", "tap '$label' screen=($x,$y) windowBounds=$bounds")
                        true
                    } else false
                }
                // AlertDialog menu rows dispatch through ListView's item-click listener;
                // TextView.performClick() silently does nothing on those child labels.
                val started = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Could not tap '$label'", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
            }
            longFolder(0)
            ProfileUiDeviceTest().screenshot("playlist-folder-actions-0182")
            choose("重命名")
            try {
                await("Rename menu action did not open its prefilled editor") {
                    texts().filterIsInstance<EditText>().any { it.contentDescription == "修改收藏夹名称" }
                }
            } catch (error: AssertionError) {
                main { android.util.Log.e("PlaylistDeviceTest", "Rename failure visible texts: " + texts().map { it.text.toString() }) }
                ProfileUiDeviceTest().screenshot("playlist-rename-failure-0182")
                throw error
            }
            ProfileUiDeviceTest().screenshot("playlist-rename-editor-0182")
            main {
                val input = texts().filterIsInstance<EditText>().single { it.contentDescription == "修改收藏夹名称" }
                assertEquals("默认收藏夹", input.text.toString()); input.setText("风景精选")
            }
            choose("保存")
            await("Renaming did not preserve the official folder ID") {
                favorites.store.folders().any { it.id == "folder-1" && it.title == "风景精选" } &&
                    texts().any { it.text.toString() == "风景精选" }
            }
            assertEquals("folder-1", favorites.store.dedicatedMigrationTarget(favorites.store.snapshot()))

            fun openManagedFolder(index: Int): SavedVideosActivity {
                val opened = openFolder(activity, index)
                videos = opened
                await("Managed folder did not load its video") { opened.findViewById<RecyclerView>(R.id.savedVideos).adapter?.itemCount == 1 }
                main { assertTrue("Manual sync button should be removed", texts().none { it.text.toString() == "同步收藏夹" }) }
                return opened
            }
            val source = openManagedFolder(0)
            main { source.findViewById<RecyclerView>(R.id.savedVideos).findViewHolderForAdapterPosition(0)!!.itemView.performLongClick() }
            choose("移动到其他收藏夹")
            await("Move destination picker did not load") { texts().any { it.text.toString() == "移动到收藏夹" } }
            val targetTitle = synchronized(catalog) { catalog.getValue("folder-2") }
            choose(targetTitle)
            await("Move did not remove the source row after confirming the destination") {
                source.findViewById<RecyclerView>(R.id.savedVideos).adapter?.itemCount == 0 &&
                    favorites.store.memberships(fixture.id) == setOf("folder-2")
            }
            synchronized(catalog) {
                assertTrue(members.getValue("folder-1").isEmpty()); assertEquals(setOf(fixture.id), members["folder-2"])
            }
            main { source.findViewById<View>(R.id.savedBack).performClick() }
            await("Folder preview did not follow the moved item") { favorites.store.folders().firstOrNull { it.id == "folder-2" }?.count == 1 }
            val target = openManagedFolder(1)
            main { target.findViewById<RecyclerView>(R.id.savedVideos).findViewHolderForAdapterPosition(0)!!.itemView.performLongClick() }
            choose("从当前收藏夹移除")
            await("Removing within the target folder did not update its list and global star") {
                target.findViewById<RecyclerView>(R.id.savedVideos).adapter?.itemCount == 0 && !favorites.isFavorite(fixture.id)
            }
            assertEquals("removed_by_user", favorites.store.dedicatedMigrationItem(favorites.store.snapshot(), fixture.id))
            main { target.findViewById<View>(R.id.savedBack).performClick() }
            await("Returning after removal did not refresh the folder count") { favorites.store.folders().firstOrNull { it.id == "folder-2" }?.count == 0 }
            longFolder(0); choose("删除收藏夹")
            await("Delete menu action did not open its separate confirmation") { texts().any { it.text.toString() == "删除收藏夹？" } }
            synchronized(catalog) { assertTrue("Merely opening confirmation must not delete", "folder-1" in catalog) }
            choose("取消")
            await("Cancelling deletion did not close the confirmation") { texts().none { it.text.toString() == "删除收藏夹？" } }
            synchronized(catalog) { assertTrue("Cancel must preserve the entire folder", "folder-1" in catalog) }
            longFolder(0); choose("删除收藏夹"); choose("删除")
            await("Confirmed folder deletion did not remove its card") { favorites.store.folders().none { it.id == "folder-1" } }
            assertEquals("folder-1", favorites.store.dedicatedMigrationTarget(favorites.store.snapshot()))
            main { favorites.migrateLegacyIfNeeded() }
            SystemClock.sleep(300)
            synchronized(catalog) { assertEquals(2, createCount); assertEquals(setOf("folder-2"), catalog.keys) }
            ProfileUiDeviceTest().screenshot("playlist-manage-rename-move-delete-0182")
        } finally {
            main { videos?.finish(); folders?.finish() }
            instrumentation.waitForIdleSync()
            HistoryStore(context).use { it.accountId = account; it.libraryDatabase.delete("favorites", null, null) }
            session.refreshToken = previousRefresh; session.accessToken = previousAccess
            session.putSecret("verified_user_id", previousVerified)
            prefs.accountId = previousAccount; prefs.isolateLibrary = previousIsolation; prefs.autoNext = previousAutoNext
            server.shutdown()
            clip.delete()
        }
    }
}
