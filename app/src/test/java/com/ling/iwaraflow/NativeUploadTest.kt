package com.ling.iwaraflow

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeUploadTest {
    private fun draft() = VideoUploadDraft("  测试作品  ", "作品简介", listOf("animation", "music"), "general", rulesAgreement = true)
    private fun client(server: MockWebServer, expired: () -> Unit = {}) = IwaraUploadClient({ "existing-app-access" },
        server.url("/").toString().trimEnd('/'), server.url("/").toString().trimEnd('/'), expired)

    @Test fun uploadProcessingAndPublicationMatchOfficialProtocol() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"job-1","key":"process-key"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"active","progress":25}"""))
            server.enqueue(MockResponse().setBody("""{"state":"completed","progress":100,"data":{"id":"file-1","name":"video.mp4","type":"video"}}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"published-video"}"""))
            client(server).use { api ->
                val bytes = "fixture video bytes".toByteArray()
                val progress = mutableListOf<Int>()
                val ticket = api.upload("fixture.mp4", bytes.size.toLong(), { ByteArrayInputStream(bytes) }) { progress += it }
                assertEquals(UploadTicket("job-1", "process-key"), ticket)
                val transfer = server.takeRequest()
                assertEquals("/upload/video", transfer.path); assertEquals("POST", transfer.method)
                assertEquals("Bearer existing-app-access", transfer.getHeader("Authorization"))
                assertTrue(transfer.body.readUtf8().contains("name=\"file\"; filename=\"fixture.mp4\""))
                assertEquals(100, progress.last())
                assertEquals("active", api.processing(ticket).state)
                val polling = server.takeRequest()
                assertEquals("/upload/video/job-1/process-key", polling.path)
                assertNull(polling.getHeader("Authorization"))
                val completed = api.processing(ticket)
                server.takeRequest()
                val result = api.publish(draft(), completed.file!!)
                assertEquals("published-video", result)
                val publish = server.takeRequest()
                assertEquals("/videos", publish.path); assertEquals("POST", publish.method)
                assertEquals("Bearer existing-app-access", publish.getHeader("Authorization"))
                val body = JSONObject(publish.body.readUtf8())
                assertEquals("测试作品", body.getString("title"))
                assertEquals("作品简介", body.getString("body"))
                assertEquals("file-1", body.getJSONObject("file").getString("id"))
                assertEquals("animation", body.getJSONArray("tags").getJSONObject(0).getString("id"))
                assertEquals("general", body.getString("rating"))
                assertTrue(body.getBoolean("rulesAgreement"))
                assertFalse(body.getBoolean("private"))
            }
        }
    }
    @Test fun unauthorizedUploadInvalidatesSessionAndDoesNotRetry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"errors.unauthorized"}"""))
            var expired = 0
            client(server) { expired++ }.use { api ->
                assertThrows(UploadApiException::class.java) {
                    api.upload("fixture.mp4", 1, { ByteArrayInputStream(byteArrayOf(1)) }) {}
                }
                assertEquals(1, expired); assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun redirectNeverForwardsFileOrAccountTokenToAnotherHost() {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
                client(server).use { api ->
                    assertThrows(UploadApiException::class.java) {
                        api.upload("fixture.mp4", 1, { ByteArrayInputStream(byteArrayOf(1)) }) {}
                    }
                    assertEquals(0, other.requestCount)
                }
            }
        }
    }
    @Test fun failedProcessingAndInvalidCompletionCannotPublish() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"state":"failed","progress":99}"""))
            server.enqueue(MockResponse().setBody("""{"state":"completed","data":{}}"""))
            client(server).use { api ->
                repeat(2) { assertThrows(java.io.IOException::class.java) { api.processing(UploadTicket("job", "key")) } }
                assertEquals(2, server.requestCount)
            }
        }
    }
    @Test fun ambiguousPublicationResultIsNotAutomaticallyRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            client(server).use { api ->
                assertThrows(PublicationUncertainException::class.java) { api.publish(draft(), JSONObject().put("id", "file")) }
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun fieldErrorsRemainActionableAndProcessedFileCanBeReused() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"errors.validation","errors":{"tags":"invalid"}}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"video"}"""))
            client(server).use { api ->
                val file = JSONObject().put("id", "existing-file")
                val error = assertThrows(UploadApiException::class.java) { api.publish(draft(), file) }
                assertTrue(error.message!!.contains("tags"))
                assertEquals("video", api.publish(draft().copy(tags = listOf("music")), file))
                repeat(2) { assertEquals("existing-file", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("file").getString("id")) }
            }
        }
    }
    @Test fun invalidDraftIsRejectedBeforeSendingAndTagsAreNormalized() {
        MockWebServer().use { server ->
            client(server).use { api ->
                assertThrows(IllegalArgumentException::class.java) { api.publish(draft().copy(rulesAgreement = false), JSONObject().put("id", "file")) }
                assertThrows(IllegalArgumentException::class.java) { api.publish(draft().copy(title = " "), JSONObject().put("id", "file")) }
                assertThrows(IllegalArgumentException::class.java) { api.publish(draft(), JSONObject()) }
                assertEquals(0, server.requestCount)
            }
        }
        assertEquals(listOf("animation", "hatsune_miku"), VideoUploadDraft.parseTags("#Animation, hatsune miku，animation"))
    }
    @Test fun closingUploaderClosesInputAndStopsAnyFutureRequest() {
        MockWebServer().use { server ->
            val api = client(server)
            api.close()
            assertThrows(IllegalStateException::class.java) {
                api.upload("fixture.mp4", 1, { ByteArrayInputStream(byteArrayOf(1)) }) {}
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun uploaderReusesAndRefreshesTheAppSession() {
        MockWebServer().use { server ->
            fun jwt(expires: Long) = "header." + android.util.Base64.encodeToString(
                JSONObject().put("exp", expires).toString().toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) + ".signature"
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val refresh = jwt(System.currentTimeMillis() / 1000 + 7200)
            val access = jwt(System.currentTimeMillis() / 1000 + 3600)
            SecureSessionStore(app).apply { refreshToken = refresh; accessToken = null }
            server.enqueue(MockResponse().setBody(JSONObject().put("accessToken", access).toString()))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"job","key":"key"}"""))
            val root = server.url("/").toString().trimEnd('/')
            val sessionApi = IwaraApi(app, root)
            try {
                IwaraUploadClient({ sessionApi.uploadAccessToken() }, root, root).use { upload ->
                    upload.upload("file.mp4", 1, { ByteArrayInputStream(byteArrayOf(1)) }) {}
                    val refreshRequest = server.takeRequest()
                    assertEquals("/user/token", refreshRequest.path)
                    assertEquals("Bearer $refresh", refreshRequest.getHeader("Authorization"))
                    assertEquals("Bearer $access", server.takeRequest().getHeader("Authorization"))
                }
            } finally { sessionApi.close(); SecureSessionStore(app).clear() }
        }
    }

    @Test fun officialRulesPreferChineseAndFallBackToEnglish() {
        val rules = IwaraRule.parse(JSONObject("""{"results":[
          {"id":"1","title":{"zh":"规则标题","en":"English title"},"body":{"zh":"**中文规则**","en":"English body"}},
          {"id":"2","title":{"zh":"","en":"Fallback"},"body":{"en":"Read [rules](https://www.iwara.tv/rules)"}},
          {"id":"3","title":null,"body":null}
        ]}"""))
        assertEquals(2, rules.size)
        assertEquals("规则标题", rules[0].title)
        assertEquals("中文规则", IwaraRule.readableBody(rules[0].body))
        assertEquals("Fallback", rules[1].title)
        assertTrue(IwaraRule.readableBody(rules[1].body).contains("https://www.iwara.tv/rules"))
    }

}
