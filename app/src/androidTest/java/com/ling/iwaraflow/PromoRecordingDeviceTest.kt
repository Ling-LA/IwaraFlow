@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Opt-in recording aid. Real phone/UI/player, explicitly neutral local demonstration data.
 * Never installed into or connected to the signed app's account, playlists or private files.
 */
@RunWith(AndroidJUnit4::class)
class PromoRecordingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)

    @Test fun keepNeutralSceneReadyForManualRecording() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Recording is explicitly opt-in", arguments.getString("recordPromo") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.ling.iwaraflow.qa") { "Use the isolated QA package, never the signed user app" }
        val scene = arguments.getString("scene") ?: "player"
        require(scene in setOf("player", "interest")) { "Supported recording scenes: player, interest" }
        val seconds = arguments.getString("seconds")?.toLongOrNull()?.coerceIn(10, 600) ?: 600L
        val stop = File(context.filesDir, "promo-stop").apply { delete() }
        val ready = File(context.filesDir, "promo-ready.json").apply { delete() }
        val account = "neutral-promo-${SystemClock.uptimeMillis()}"
        fun fixtureToken(kind: String) = "fixture." + Base64.encodeToString(
            JSONObject().put("sub", account).put("kind", kind).put("exp", System.currentTimeMillis() / 1000 + 86400)
                .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        val refreshFixture = fixtureToken("refresh")
        val accessFixture = fixtureToken("access")
        val prefs = AppPrefs(context)
        val session = SecureSessionStore(context)
        // QA-only seed account. No credential is read from the real application's package.
        session.clearAuthentication()
        prefs.accountId = account; prefs.isolateInterests = true; prefs.isolateLibrary = true
        prefs.autoNext = false; prefs.autoPip = false; prefs.autoOpenClipboardLinks = false
        prefs.preloadNext = false; prefs.translateDanmaku = false; prefs.danmakuEnabled = true
        context.getSharedPreferences(AppPrefs.FILE, 0).edit()
            .putBoolean(PlaybackGuide.key(false), true).putBoolean(PlaybackGuide.key(true), true)
            .putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).commit()
        WarmRecommendationStore.clear(context)

        val landscape = arguments.getString("landscape") == "true"
        val asset = if (landscape) "browse-landscape-fixture.mp4" else "navigation-fixture.mp4"
        // The recorder can import original scenic media without adding large files to APKs.
        // A malformed custom file fails readiness visibly; it never silently becomes a test chart.
        val custom = File(context.filesDir, "promo-custom.mp4")
        val media = if (custom.isFile && custom.length() > 0) custom else {
            File(context.filesDir, "promo-neutral-${if (landscape) "landscape" else "portrait"}.mp4").also { target ->
                instrumentation.context.assets.open(asset).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
        }
        val mediaUrl = Uri.fromFile(media).toString()
        val titles = listOf("光影与色彩", "午后的旋律", "自然的节奏", "城市的色彩", "一段轻松时光", "动画与音乐")
        val tags = listOf("nature", "music", "landscape", "art", "travel", "animation")
        val items = titles.mapIndexed { index, title ->
            VideoItem("promo-$index", "$title · 中性演示", "示例创作者", listOf(tags[index], "animation"), 128 + index * 64,
                authorId = "neutral-author", authorUsername = "demo_creator", views = 12000 + index * 1200,
                createdAt = System.currentTimeMillis() - index * 86_400_000L,
                sources = listOf(VideoSource("本地演示", mediaUrl, 720)), streamUrl = mediaUrl).apply {
                description = "这是用于真机操作录制的中性演示数据。展示真实播放器、手势、评论与收藏界面，不代表官网推荐结果或真实用户内容。"
            }
        }
        HistoryStore(context).use { history ->
            history.accountId = account
            listOf("nature", "music", "animation").forEach { history.setManualTagPreference(it, 1) }
            history.setManualTagPreference("sports", -1)
            items.forEach { history.recordInteraction(it, "like", 1.0) }
            history.mute(HistoryStore.MUTE_TAG, "spoiler")
        }

        // Only these local HTTPS fixture endpoints can accept the demonstrator's actions.
        // Unknown paths fail closed; there is no proxy/fallback to the official service.
        val folders = linkedMapOf("demo-default" to "默认收藏夹", "demo-travel" to "旅行与风景")
        val members = linkedMapOf("demo-default" to linkedSetOf<String>(), "demo-travel" to linkedSetOf<String>())
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        fun author() = JSONObject().put("id", "neutral-author").put("name", "示例创作者").put("username", "demo_creator")
        fun folder(id: String) = JSONObject().put("id", id).put("title", folders[id]).put("numVideos", members[id]?.size ?: 0)
        fun response(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
        val comments = listOf("这个配色看着很舒服。", "音乐与画面的节奏很合适。", "谢谢分享，期待下一段作品。")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(folders) {
                val url = request.requestUrl!!; val path = url.pathSegments
                when {
                    path == listOf("user", "token") && request.method == "POST" ->
                        response(JSONObject().put("accessToken", accessFixture))
                    path == listOf("playlists") && request.method == "POST" -> {
                        val id = "demo-folder-${folders.size}"; folders[id] = JSONObject(request.body.readUtf8()).getString("title")
                        members[id] = linkedSetOf(); response(folder(id))
                    }
                    path == listOf("playlists") -> response(JSONObject().put("count", folders.size).put("page", 0).put("limit", 50)
                        .put("results", JSONArray(folders.keys.map(::folder))))
                    path == listOf("light", "playlists") -> response(JSONArray(folders.keys.map {
                        folder(it).put("added", url.queryParameter("id") in members[it].orEmpty())
                    }))
                    path.size == 3 && path[0] == "playlist" && path[1] in folders -> {
                        if (request.method == "POST") members.getValue(path[1]).add(path[2]) else members.getValue(path[1]).remove(path[2])
                        MockResponse().setResponseCode(204)
                    }
                    path.size == 3 && path[0] == "video" && path[2] == "like" -> MockResponse().setResponseCode(204)
                    path.size == 3 && path[0] == "video" && path[2] == "comments" && request.method == "GET" ->
                        response(JSONObject().put("count", comments.size).put("page", 0).put("limit", 50).put("results",
                            JSONArray(comments.mapIndexed { index, text -> JSONObject().put("id", "demo-comment-$index")
                                .put("body", text).put("user", author()).put("createdAt", "2026-11-01T00:00:00Z") })))
                    path == listOf("user") -> response(JSONObject().put("user", JSONObject().put("id", account).put("name", "中性演示账号").put("username", "neutral_demo")))
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"中性录制场景未开放此操作"}""")
                }
            }
        }
        server.start()
        val localUrl = server.url("/")
        val api = IwaraApi(context, localUrl.toString().trimEnd('/')).also {
            field(it, "client").set(it, OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain ->
                    val url = chain.request().url
                    check(url.host == localUrl.host && url.port == localUrl.port) { "Recording API must remain local" }
                    chain.proceed(chain.request())
                }.sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
        }
        // Block default clients created during Activity startup or an unexpected navigation.
        // A local unavailable proxy fails closed; it cannot forward fixture credentials to
        // Iwara, update services, or another remote host. Only this QA process is affected.
        val previousProxy = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = if (uri.host in setOf("localhost", "127.0.0.1", "::1"))
                listOf(Proxy.NO_PROXY) else listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        var activity: Activity? = null
        try {
            if (scene == "interest") {
                activity = instrumentation.startActivitySync(Intent(context, InterestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val home = instrumentation.startActivitySync(Intent(context, MainActivityV3::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivityV3
                activity = home
                main {
                    invoke(home, "invalidateRequests")
                    (field(home, "api").get(home) as IwaraApi).close()
                    field(home, "api").set(home, api)
                    (field(home, "likedSync").get(home) as LikedVideoSync).close()
                    (field(home, "playableGate").get(home) as PlayableVideoGate).close()
                    field(home, "playableGate").set(home, PlayableVideoGate(api))
                    (field(home, "updates").get(home) as UpdateManager).close()
                    val engine = field(home, "recommender").get(home) as RecommendationEngine
                    engine.close()
                    val ranker = field(engine, "ranker").get(engine) as RecommendationRanker
                    items.forEachIndexed { index, item -> ranker.remember(item) { it.matchedTags += tags[index] } }
                    field(home, "launchUpdateChecked").setBoolean(home, true)
                    val adapter = home.playbackGuideAdapter
                    (field(adapter, "favoriteController").get(adapter) as? PlaylistFavorites)?.close()
                    (field(adapter, "favoriteSubscription").get(adapter) as? java.io.Closeable)?.close()
                    field(adapter, "favoriteController").set(adapter, null); field(adapter, "favoriteSubscription").set(adapter, null)
                    field(adapter, "api").set(adapter, api)
                    adapter.syncPlaylistFavorites(home)
                    adapter.onUpcomingChanged = null
                    val panel = (field(home, "comments").get(home) as CommentsHost).panel
                    field(panel, "api").set(panel, api)
                    synchronized(SecureSessionStore.accountLock) {
                        session.refreshToken = refreshFixture
                        session.accessToken = accessFixture
                        session.putSecret("verified_user_id", account)
                    }
                    field(home, "mode").set(home, "recommend")
                    invoke(home, "setBrowseSurface", false); invoke(home, "styleRecommendTab")
                    invoke(home, "restoreHomeSession", FeedSessionStore.Session(items, 0, 0, false))
                }
                // Exercise the production refresh check and our local token endpoint before
                // declaring readiness. A malformed refresh fixture must fail here, not while
                // the recorder is holding the screen for a double reaction.
                session.accessToken = null
                check(api.uploadAccessToken() == accessFixture && api.playlistAccountBlocking() == account) {
                    "Neutral fixture authentication is not ready"
                }
            }
            main { activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val deadline = SystemClock.uptimeMillis() + seconds * 1000
            var playerReady = scene == "interest"
            val readinessDeadline = SystemClock.uptimeMillis() + 15000
            while (!stop.exists() && SystemClock.uptimeMillis() < deadline) {
                val probe = File(context.filesDir, "promo-probe")
                if (probe.exists()) {
                    PhoneUiProbeTest().capture()
                    probe.delete()
                }
                main {
                    val home = activity as? MainActivityV3
                    if (home != null && !home.isDestroyed) {
                        val pager = home.findViewById<ViewPager2>(R.id.pager)
                        val holder = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
                        val player = holder?.let { field(it, "player").get(it) as? ExoPlayer }
                        // Repetition changes only this test player's fixture; swipes/gestures
                        // and their production handlers remain untouched.
                        player?.repeatMode = Player.REPEAT_MODE_ONE
                        if (player?.playbackState == Player.STATE_READY) playerReady = true
                    }
                }
                if (scene == "player") check(api.isLoggedIn() && session.refreshToken == refreshFixture) {
                    "Neutral recording session unexpectedly expired; discard this capture and restart the fixture"
                }
                if (playerReady && !ready.exists()) ready.writeText(JSONObject().put("ready", true).put("scene", scene)
                    .put("package", context.packageName).put("source", "real_phone_neutral_demo_data")
                    .put("authentication", if (scene == "player") "validated_local_fixture" else "not_required")
                    .put("media_source", if (media == custom) "original_custom_clip" else asset)
                    .put("landscape_fixture", landscape).put("max_seconds", seconds).toString())
                check(playerReady || SystemClock.uptimeMillis() < readinessDeadline) { "Neutral player did not become ready" }
                SystemClock.sleep(250)
            }
        } finally {
            // Keep the last screen visible for the recorder; stop/restart QA when changing scenes.
            api.close(); server.close()
            ProxySelector.setDefault(previousProxy)
            File(context.filesDir, "promo-stopped").writeText("$scene:${System.currentTimeMillis()}")
        }
    }
}
