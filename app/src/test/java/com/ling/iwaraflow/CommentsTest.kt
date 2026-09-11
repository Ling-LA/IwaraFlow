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
import java.io.IOException

/**
 * 评论直接对接 Iwara 官网：列表读 `/video/{id}/comments`，回复列表带 `parent=`，
 * 发评论 / 回复 POST 到同一个地址。面板的几何（多高、画面从哪开始）是纯函数，
 * 横竖屏各验一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommentsTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun startFixtureApi() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                if (request.method == "POST") {
                    // 复制一份再读：原始 body 留给测试断言用。
                    val sent = JSONObject(request.body.clone().readUtf8())
                    val posted = comment("posted-1", sent.getString("body"), replies = 0)
                        .put("parent", if (sent.has("parentId")) JSONObject().put("id", sent.getString("parentId")) else JSONObject.NULL)
                    return MockResponse().setResponseCode(201).setBody(posted.toString())
                }
                val parent = url.queryParameter("parent")
                val results = JSONArray()
                if (parent == null) {
                    results.put(comment("c1", "第一条", replies = 2)).put(comment("c2", "第二条", replies = 0))
                } else {
                    results.put(comment("r1", "回复 $parent", replies = 0).put("parent", JSONObject().put("id", parent)))
                }
                val body = JSONObject().put("count", if (parent == null) 57 else 1)
                    .put("page", 0).put("limit", 32).put("results", results)
                return MockResponse().setResponseCode(200).setBody(body.toString())
            }
        }
        server.start()
    }

    @After fun stopFixtureApi() {
        server.shutdown()
    }

    private fun comment(id: String, body: String, replies: Int): JSONObject = JSONObject()
        .put("id", id).put("body", body).put("numReplies", replies)
        .put("createdAt", "2024-03-01T10:00:00.000Z")
        .put("user", JSONObject().put("id", "user-1").put("name", "评论者").put("username", "commenter")
            .put("avatar", JSONObject().put("id", "avatar-1").put("name", "face")))

    private fun api(): IwaraApi =
        IwaraApi(RuntimeEnvironment.getApplication(), server.url("/").toString().trimEnd('/'))

    private fun loggedInApi(): IwaraApi {
        val payload = JSONObject().put("exp", System.currentTimeMillis() / 1000L + 3600L).toString()
        val token = "header." + Base64.encodeToString(
            payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ) + ".signature"
        SecureSessionStore(RuntimeEnvironment.getApplication()).apply { refreshToken = token; accessToken = token }
        return api()
    }

    @Test fun theOfficialCommentListIsReadWithItsAuthorsAndReplyCounts() {
        val api = api()
        try {
            val page = api.getCommentPageBlocking("video-1", 0)
            val url = requests.single().requestUrl!!
            assertTrue(url.encodedPath.endsWith("/video/video-1/comments"))
            assertNull("顶层评论不带 parent", url.queryParameter("parent"))
            assertEquals(57, page.total)
            assertTrue("57 条只回了 2 条，后面还有", page.hasMore)
            assertEquals(listOf("c1", "c2"), page.comments.map { it.id })
            assertEquals("第一条", page.comments[0].body)
            assertEquals(2, page.comments[0].replyCount)
            assertEquals("评论者", page.comments[0].author.name)
            assertTrue(page.comments[0].author.avatarUrl.endsWith("/image/avatar/avatar-1/face.jpg"))
            assertTrue("时间要解析出来", page.comments[0].createdAt > 0L)
        } finally { api.close() }
    }

    @Test fun repliesAreReadUnderTheirParent() {
        val api = api()
        try {
            val page = api.getCommentPageBlocking("video-1", 0, parentId = "c1")
            assertEquals("c1", requests.single().requestUrl!!.queryParameter("parent"))
            assertEquals(listOf("r1"), page.comments.map { it.id })
            assertEquals("c1", page.comments.single().parentId)
        } finally { api.close() }
    }

    @Test fun postingAReplyGoesToTheWebsiteWithTheParentId() {
        val api = loggedInApi()
        try {
            val posted = api.postCommentBlocking("video-1", "  说得对  ", parentId = "c1")
            val request = requests.single { it.method == "POST" }
            assertTrue(request.path!!.endsWith("/video/video-1/comments"))
            assertTrue("要带登录 token", request.getHeader("Authorization").orEmpty().startsWith("Bearer "))
            val sent = JSONObject(request.body.clone().readUtf8())
            assertEquals("说得对", sent.getString("body"))
            assertEquals("c1", sent.getString("parentId"))
            assertEquals("posted-1", posted.id)
            assertEquals("c1", posted.parentId)
        } finally { api.close() }
    }

    @Test fun aTopLevelCommentCarriesNoParentId() {
        val api = loggedInApi()
        try {
            api.postCommentBlocking("video-1", "新评论")
            val sent = JSONObject(requests.single { it.method == "POST" }.body.clone().readUtf8())
            assertFalse("顶层评论不能带 parentId", sent.has("parentId"))
        } finally { api.close() }
    }

    @Test fun postingWithoutLoginIsRefusedBeforeTouchingTheNetwork() {
        val api = api()
        try {
            val error = runCatching { api.postCommentBlocking("video-1", "hi") }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains("登录"))
            assertTrue("没登录就不该发请求", requests.isEmpty())
        } finally { api.close() }
    }

    // ---------------------------------------------------------------- 面板几何

    private val w = 1080
    private val h = 2400
    private val bar = 150

    @Test fun aLandscapeVideoGetsAPanelThatStartsRightUnderThePicture() {
        val aspect = 16f / 9f
        val rendered = CommentsPanel.renderedHeight(w, h, aspect)
        val panel = CommentsPanel.panelHeight(w, h, bar, aspect)
        val top = CommentsPanel.videoTop(w, h, bar, aspect, panel)
        assertEquals(607, rendered)
        assertEquals("顶栏和画面以下全给面板", h - bar - rendered, panel)
        assertTrue("不能超过上限", panel <= (h * CommentsPanel.MAX_FRACTION).toInt())
        assertEquals("画面顶边贴住顶栏", bar, top)
        assertEquals("画面底边 = 面板顶边", h - panel, top + rendered)
    }

    @Test fun aPortraitVideoShrinksIntoTheSpaceAboveAMinimumHeightPanel() {
        val aspect = 9f / 16f
        val panel = CommentsPanel.panelHeight(w, h, bar, aspect)
        val top = CommentsPanel.videoTop(w, h, bar, aspect, panel)
        assertEquals("竖屏视频铺满，剩不下空间，面板取最小高度", (h * CommentsPanel.MIN_FRACTION).toInt(), panel)
        assertEquals("画面从顶栏底边开始", bar, top)
        assertTrue("画面区域要比面板高，不然评论没法看", h - panel - top > panel / 2)
    }

    @Test fun aGapPullsThePanelDownAndThePictureFollows() {
        val aspect = 16f / 9f
        val rendered = CommentsPanel.renderedHeight(w, h, aspect)
        val flush = CommentsPanel.panelHeight(w, h, bar, aspect)
        val panel = CommentsPanel.panelHeight(w, h, bar, aspect, gap = 60)
        assertEquals("面板矮 60，顶边就低 60", flush - 60, panel)
        val top = CommentsPanel.videoTop(w, h, bar, aspect, panel)
        assertEquals("画面跟着下移，离顶栏 60", bar + 60, top)
        assertEquals("画面底边仍然贴着面板", h - panel, top + rendered)
    }

    @Test fun anUnknownAspectIsTreatedAsPortrait() {
        assertEquals(CommentsPanel.panelHeight(w, h, bar, 9f / 16f), CommentsPanel.panelHeight(w, h, bar, null))
        assertEquals(bar, CommentsPanel.videoTop(w, h, bar, null, CommentsPanel.panelHeight(w, h, bar, null)))
    }

    @Test fun aVeryWideVideoDoesNotPushThePanelPastTheCap() {
        val aspect = 4f
        val panel = CommentsPanel.panelHeight(w, h, bar, aspect)
        assertEquals((h * CommentsPanel.MAX_FRACTION).toInt(), panel)
        val top = CommentsPanel.videoTop(w, h, bar, aspect, panel)
        assertEquals("画面矮，顶边下移到贴住面板", h - panel - CommentsPanel.renderedHeight(w, h, aspect), top)
        assertTrue(top >= bar)
    }

    @Test fun relativeTimesReadNaturally() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", CommentListAdapter.relativeTime(now - 10_000L, now))
        assertEquals("5 分钟前", CommentListAdapter.relativeTime(now - 5 * 60_000L, now))
        assertEquals("3 小时前", CommentListAdapter.relativeTime(now - 3 * 3_600_000L, now))
        assertEquals("2 天前", CommentListAdapter.relativeTime(now - 2 * 86_400_000L, now))
        assertTrue(CommentListAdapter.relativeTime(now - 90 * 86_400_000L, now).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertEquals("", CommentListAdapter.relativeTime(0L, now))
    }
}
