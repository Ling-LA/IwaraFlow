package com.ling.iwaraflow

import android.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PlaylistApiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).accountId = "account-a"
        val token = "header." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000 + 3600).toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "fixture-refresh"; accessToken = token }
    }
    @Test fun officialFolderAndDetailEnvelopesAndPaginationAreDecoded() {
        MockWebServer().use { server ->
            val folder = """{"id":"folder","title":"旅行","numVideos":51,"thumbnail":{"file":{"id":"file"},"thumbnail":4}}"""
            server.enqueue(MockResponse().setBody("""{"count":51,"limit":1,"page":0,"results":[$folder]}"""))
            server.enqueue(MockResponse().setBody("""{"playlist":$folder,"results":[{"id":"video","title":"景色"}],"count":51,"limit":1,"page":0}"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                val folders = api.getPlaylistFoldersBlocking("account-a")
                assertEquals("旅行", folders.folders.single().title); assertTrue(folders.hasMore)
                assertTrue(folders.folders.single().thumbnail.endsWith("/file/thumbnail-04.jpg"))
                val page = api.getPlaylistPageBlocking("folder")
                assertEquals("video", page.items.single().id); assertEquals(51, page.total); assertTrue(page.hasMore)
                assertEquals("/playlists?user=account-a&page=0&limit=50", server.takeRequest().path)
                assertEquals("/playlist/folder?page=0&limit=50", server.takeRequest().path)
            } finally { api.close() }
        }
    }
    @Test fun membershipUsesLightEndpointAndCreateOnlySendsOfficialTitle() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"folder","title":"旅行","added":true}]"""))
            server.enqueue(MockResponse().setBody("""{"id":"new","title":"默认收藏夹","numVideos":0}"""))
            server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                assertTrue(api.getPlaylistMembershipsBlocking("video").single().added)
                assertEquals("new", api.createPlaylistBlocking(" 默认收藏夹 ").id)
                api.setPlaylistVideoBlocking("new", "video", true); api.setPlaylistVideoBlocking("new", "video", false)
                val membership = server.takeRequest(); assertEquals("/light/playlists?id=video", membership.path); assertNotNull(membership.getHeader("Authorization"))
                val create = server.takeRequest(); assertEquals("POST", create.method); assertEquals("/playlists", create.path)
                assertEquals(setOf("title"), JSONObject(create.body.readUtf8()).keys().asSequence().toSet())
                assertEquals("POST", server.takeRequest().method); assertEquals("DELETE", server.takeRequest().method)
            } finally { api.close() }
        }
    }
    @Test fun rejectedWritesAndAmbiguousCreateAreNotSuccess() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Denied"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"count":0,"results":[]}"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                assertTrue(runCatching { api.setPlaylistVideoBlocking("folder", "video", true) }.isFailure)
                val error = runCatching { api.createPlaylistBlocking("Folder") }.exceptionOrNull()
                assertTrue(error!!.message!!.contains("确认")); assertEquals(3, server.requestCount)
            } finally { api.close() }
        }
    }
    @Test fun successfulEmptyCreateResponseIsResolvedWithoutRepeatingPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"count":1,"results":[{"id":"new","title":"Folder"}]}"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                assertEquals("new", api.createPlaylistBlocking("Folder").id)
                assertEquals("POST", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
            } finally { api.close() }
        }
    }
    @Test fun mutationRedirectIsNotFollowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try { assertTrue(runCatching { api.createPlaylistBlocking("Folder") }.isFailure); assertEquals(1, server.requestCount) }
            finally { api.close() }
        }
    }

    @Test fun renameAndDeleteUseOfficialFolderRoutes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                api.renamePlaylistBlocking("folder", " 新名称 ")
                api.deletePlaylistBlocking("folder")
                val rename = server.takeRequest()
                assertEquals("PUT", rename.method); assertEquals("/playlist/folder", rename.path)
                val body = JSONObject(rename.body.readUtf8())
                assertEquals(setOf("title"), body.keys().asSequence().toSet()); assertEquals("新名称", body.getString("title"))
                val delete = server.takeRequest()
                assertEquals("DELETE", delete.method); assertEquals("/playlist/folder", delete.path); assertEquals(0L, delete.bodySize)
                assertTrue(runCatching { api.renamePlaylistBlocking("folder", " ") }.isFailure)
                assertEquals("Invalid names must not issue a request", 2, server.requestCount)
            } finally { api.close() }
        }
    }
    @Test fun emptyCreateResponseCannotResolveToPreexistingSameNameFolder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"count":2,"results":[{"id":"old","title":"Folder"},{"id":"new","title":"Folder"}]}"""))
            val api = IwaraApi(context, server.url("/").toString().trimEnd('/'))
            try {
                assertEquals("new", api.createPlaylistBlocking("Folder", setOf("old")).id)
                assertEquals("POST", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
            } finally { api.close() }
        }
    }
}
