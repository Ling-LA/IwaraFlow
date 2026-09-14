package com.ling.iwaraflow

import android.util.Base64
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 点赞过的视频不该再出现在推荐里。服务端一页最多回 50 条，只读第一页的话
 * 点赞多的账号剩下的老点赞永远不会被标记成“已看”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LikedVideoSyncTest {
    private lateinit var server: MockWebServer
    private var total = 137
    private var failFromPage = Int.MAX_VALUE

    @Before fun startFixtureApi() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val page = url.queryParameter("page")?.toIntOrNull() ?: 0
                if (page >= failFromPage) return MockResponse().setResponseCode(500).setBody("{}")
                // 真实服务端行为：一页最多 50 条，并回报生效的 limit。
                val served = minOf(url.queryParameter("limit")?.toIntOrNull() ?: 50, 50)
                val results = JSONArray()
                for (index in page * served until minOf((page + 1) * served, total)) {
                    results.put(JSONObject().put("video", JSONObject()
                        .put("id", "liked-$index").put("title", "点赞 $index")
                        .put("user", JSONObject().put("id", "author-$page").put("name", "作者$page"))
                        .put("tags", JSONArray().put(JSONObject().put("id", "tag-$page")))))
                }
                val body = JSONObject()
                    .put("count", total).put("limit", served).put("page", page).put("results", results)
                return MockResponse().setResponseCode(200).setBody(body.toString())
            }
        }
        server.start()
    }

    @After fun stopFixtureApi() {
        server.shutdown()
    }

    private fun loggedInApi(): IwaraApi {
        val payload = JSONObject().put("exp", System.currentTimeMillis() / 1000L + 3600L).toString()
        val token = "header." + Base64.encodeToString(
            payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ) + ".signature"
        SecureSessionStore(RuntimeEnvironment.getApplication()).apply {
            refreshToken = token
            accessToken = token
        }
        return IwaraApi(RuntimeEnvironment.getApplication(), server.url("/").toString().trimEnd('/'))
    }

    private fun sync(api: IwaraApi, history: HistoryStore, prefs: AppPrefs) =
        LikedVideoSync(api, history, prefs)

    @Test fun everyLikedVideoIsMarkedSeenNotJustTheFirstPage() {
        val api = loggedInApi()
        val history = HistoryStore(RuntimeEnvironment.getApplication())
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        val liked = sync(api, history, prefs)
        try {
            assertEquals(137, liked.syncBlocking())
            assertTrue("第一页的点赞", history.isSeen("liked-0"))
            assertTrue("第二页的点赞", history.isSeen("liked-60"))
            assertTrue("最后一页的点赞", history.isSeen("liked-136"))
            assertTrue("同步完成要记时间戳", prefs.likedSyncAt > 0L)
        } finally { liked.close(); api.close(); history.close() }
    }

    /**
     * 登录之后先只种第一页：一个用了多年的账号，第一屏推荐就该已经个性化，
     * 而不是等完整同步翻完几十页、第二次刷新才像样。
     */
    @Test fun loggingInSeedsTheProfileFromTheFirstPageBeforeTheFullSync() {
        val api = loggedInApi()
        val history = HistoryStore(RuntimeEnvironment.getApplication())
        val prefs = AppPrefs(RuntimeEnvironment.getApplication()).apply { likedSyncAt = 0L }
        val liked = sync(api, history, prefs)
        try {
            val done = java.util.concurrent.CountDownLatch(1)
            liked.seedFirstPage { done.countDown() }
            assertTrue("种子应该很快回来", done.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val profile = history.preferenceProfile()
            assertTrue("第一页的作者要进画像", (profile.authorIdWeights["author-0"] ?: 0.0) > 0.0)
            assertTrue("第一页的点赞算已看", history.isSeen("liked-0"))
            assertFalse("只种第一页，剩下的交给后台同步", history.isSeen("liked-60"))
            assertEquals("这一步不算完整同步，时间戳不能写", 0L, prefs.likedSyncAt)
        } finally { liked.close(); api.close(); history.close() }
    }

    @Test fun theFirstRequestAsksForTheLimitTheServerHonors() {
        val api = loggedInApi()
        val history = HistoryStore(RuntimeEnvironment.getApplication())
        try {
            api.getFavoritesPageBlocking(0)
            assertEquals(IwaraApi.MAX_PAGE_LIMIT.toString(),
                server.takeRequest().requestUrl!!.queryParameter("limit"))
        } finally { api.close(); history.close() }
    }

    @Test fun aFailedPageKeepsTheStampSoTheNextStartRetries() {
        failFromPage = 1
        val api = loggedInApi()
        val history = HistoryStore(RuntimeEnvironment.getApplication())
        val prefs = AppPrefs(RuntimeEnvironment.getApplication()).apply { likedSyncAt = 0L }
        val liked = sync(api, history, prefs)
        try {
            assertEquals(50, liked.syncBlocking())
            assertTrue("拿到的那一页仍然算数", history.isSeen("liked-10"))
            assertEquals("没同步完就不能记时间戳", 0L, prefs.likedSyncAt)
        } finally { liked.close(); api.close(); history.close() }
    }

    @Test fun aFullyReadListStopsAtTheLastPartialPage() {
        total = 100
        val api = loggedInApi()
        val history = HistoryStore(RuntimeEnvironment.getApplication())
        try {
            assertTrue(api.getFavoritesPageBlocking(0).hasMore)
            assertFalse("整页读完但已经到总数，不该再翻页", api.getFavoritesPageBlocking(1).hasMore)
        } finally { api.close(); history.close() }
    }
}
