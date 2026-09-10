package com.ling.iwaraflow

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

/** 搜索结果页要的三种结果：视频名、角色名（标签）和作者名各自走不同的 Iwara 接口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchApiTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun startFixtureApi() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                val body = when {
                    url.encodedPath.endsWith("/search") && url.queryParameter("type") == "users" ->
                        JSONObject().put("count", 1).put("results", JSONArray().put(
                            JSONObject().put("user", JSONObject()
                                .put("id", "user-1").put("name", "Fixture 作者").put("username", "fixture")
                                .put("avatar", JSONObject().put("id", "avatar-1").put("name", "face")))
                        ))
                    url.encodedPath.endsWith("/search") ->
                        JSONObject().put("count", 1).put("results", JSONArray().put(video("search-1")))
                    else ->
                        JSONObject().put("count", 1).put("results", JSONArray().put(video("tag-1")))
                }
                return MockResponse().setResponseCode(200).setBody(body.toString())
            }
        }
        server.start()
    }

    @After fun stopFixtureApi() {
        server.shutdown()
    }

    private fun video(id: String): JSONObject = JSONObject()
        .put("id", id).put("title", "标题 $id").put("numLikes", 3).put("numViews", 9)
        .put("user", JSONObject().put("id", "user-1").put("name", "Fixture 作者").put("username", "fixture"))

    private fun api(): IwaraApi =
        IwaraApi(RuntimeEnvironment.getApplication(), server.url("/").toString().trimEnd('/'))

    @Test fun authorSearchReadsUsersAndTheirAvatars() {
        val api = api()
        try {
            val authors = api.searchUsersBlocking("fixture", 0, 24)
            val url = requests.single().requestUrl!!
            assertEquals("users", url.queryParameter("type"))
            assertEquals("fixture", url.queryParameter("query"))
            assertEquals(1, authors.size)
            assertEquals("Fixture 作者", authors.single().name)
            assertEquals("fixture", authors.single().username)
            assertTrue(authors.single().avatarUrl.endsWith("/image/avatar/avatar-1/face.jpg"))
        } finally { api.close() }
    }

    @Test fun characterSearchGoesThroughTheTagFilter() {
        val api = api()
        try {
            val videos = api.getVideosByTagBlocking("ganyu", 0, 24)
            val url = requests.single().requestUrl!!
            assertTrue(url.encodedPath.endsWith("/videos"))
            assertEquals("ganyu", url.queryParameter("tags"))
            assertEquals("all", url.queryParameter("rating"))
            assertEquals(listOf("tag-1"), videos.map { it.id })
        } finally { api.close() }
    }

    @Test fun titleSearchStillUsesTheVideoSearchEndpoint() {
        val api = api()
        try {
            val videos = api.searchVideosBlocking("fixture", 0, 24)
            val url = requests.single().requestUrl!!
            assertEquals("videos", url.queryParameter("type"))
            assertEquals(listOf("search-1"), videos.map { it.id })
        } finally { api.close() }
    }

    @Test fun listRequestsNeverAskForMoreThanTheServerReturns() {
        val api = api()
        try {
            api.searchUsersBlocking("fixture", 0, 200)
            api.getVideosByTagBlocking("ganyu", 0, 200)
            assertEquals(
                listOf(IwaraApi.MAX_PAGE_LIMIT.toString(), IwaraApi.MAX_PAGE_LIMIT.toString()),
                requests.map { it.requestUrl!!.queryParameter("limit") }
            )
        } finally { api.close() }
    }
}
