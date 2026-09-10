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

/** 关注列表分页：服务端把 limit 截断到 50，超过 50 位关注也必须全部读出来。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FollowingPaginationTest {
    private lateinit var server: MockWebServer
    private var total = 139

    @Before fun startFixtureApi() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val page = url.queryParameter("page")?.toIntOrNull() ?: 0
                // 真实服务端行为：无论请求多少，一页最多 50 条，并回报生效的 limit。
                val served = minOf(url.queryParameter("limit")?.toIntOrNull() ?: 50, 50)
                val start = page * served
                val results = JSONArray()
                for (index in start until minOf(start + served, total)) {
                    results.put(JSONObject().put("user", JSONObject()
                        .put("id", "author-$index")
                        .put("name", "作者 $index")
                        .put("username", "author$index")))
                }
                val body = JSONObject()
                    .put("count", total).put("limit", served).put("page", page)
                    .put("results", results)
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
        val encoded = Base64.encodeToString(
            payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val token = "header.$encoded.signature"
        SecureSessionStore(RuntimeEnvironment.getApplication()).apply {
            refreshToken = token
            accessToken = token
        }
        val root = server.url("/").toString().trimEnd('/')
        return IwaraApi(RuntimeEnvironment.getApplication(), root)
    }

    private fun collectAll(api: IwaraApi): List<IwaraAuthor> {
        val all = ArrayList<IwaraAuthor>()
        var page = 0
        while (true) {
            val result = api.getFollowingPageBlocking("fixture-user", page)
            all += result.users
            if (!result.hasMore) break
            page += 1
        }
        return all
    }

    @Test fun everyFollowedAuthorIsReadNotJustTheFirstPage() {
        val api = loggedInApi()
        try {
            val authors = collectAll(api)
            assertEquals(139, authors.size)
            assertEquals("author-0", authors.first().id)
            assertEquals("author-138", authors.last().id)
            assertEquals(authors.size, authors.map { it.id }.toSet().size)
        } finally { api.close() }
    }

    @Test fun pagesAreRequestedWithTheLimitTheServerActuallyHonors() {
        val api = loggedInApi()
        try {
            val first = api.getFollowingPageBlocking("fixture-user", 0)
            assertEquals(50, first.users.size)
            assertEquals(139, first.total)
            assertTrue("第一页之后必须继续翻页", first.hasMore)
            val requested = server.takeRequest().requestUrl!!.queryParameter("limit")
            assertEquals(IwaraApi.MAX_PAGE_LIMIT.toString(), requested)
        } finally { api.close() }
    }

    @Test fun theLastPartialPageEndsThePagination() {
        val api = loggedInApi()
        try {
            assertTrue(api.getFollowingPageBlocking("fixture-user", 1).hasMore)
            val last = api.getFollowingPageBlocking("fixture-user", 2)
            assertEquals(39, last.users.size)
            assertFalse("最后一页不应该再请求下一页", last.hasMore)
        } finally { api.close() }
    }

    @Test fun anExactlyFullLastPageStillTerminates() {
        total = 100
        val api = loggedInApi()
        try {
            val authors = collectAll(api)
            assertEquals(100, authors.size)
            assertFalse(api.getFollowingPageBlocking("fixture-user", 1).hasMore)
        } finally { api.close() }
    }
}
