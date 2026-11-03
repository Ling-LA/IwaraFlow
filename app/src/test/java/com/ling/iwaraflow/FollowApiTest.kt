package com.ling.iwaraflow

import android.util.Base64
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class FollowApiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        AppPrefs(context).accountId = "account-a"
        val token = "header." + Base64.encodeToString(
            JSONObject().put("exp", System.currentTimeMillis() / 1000 + 3600).toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        SecureSessionStore(context).apply { refreshToken = "fixture-refresh"; accessToken = token }
    }
    private fun profile(following: Boolean, id: String = "creator") = MockResponse().setBody(
        """{"user":{"id":"$id","username":"creator-name","name":"Creator","following":$following}}""")
    private fun api(server: MockWebServer) = IwaraApi(context, server.url("/").toString().trimEnd('/'))
    private fun ensure(api: IwaraApi, username: String = "creator-name"): CompletableFuture<Result<Unit>> =
        CompletableFuture<Result<Unit>>().also { answer -> api.ensureUserFollowed("creator", username) { answer.complete(it) } }
    private fun CompletableFuture<Result<Unit>>.await() = get(5, TimeUnit.SECONDS)

    @Test fun existingServerFollowNeedsNoWrite() {
        MockWebServer().use { server ->
            server.enqueue(profile(true))
            val api = api(server)
            try {
                assertTrue(ensure(api).await().isSuccess)
                assertEquals(1, server.requestCount)
                val read = server.takeRequest()
                assertEquals("/profile/creator-name", read.path)
                assertNotNull(read.getHeader("Authorization"))
            } finally { api.close() }
        }
    }

    @Test fun repeatedPagesShareAcknowledgement() {
        MockWebServer().use { server ->
            server.enqueue(profile(false)); server.enqueue(MockResponse().setResponseCode(204))
            val first = api(server); val second = api(server)
            try {
                assertTrue(ensure(first).await().isSuccess)
                assertTrue(ensure(second).await().isSuccess)
                assertEquals(2, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
                val write = server.takeRequest()
                assertEquals("POST", write.method); assertEquals("/user/creator/followers", write.path)
            } finally { first.close(); second.close() }
        }
    }

    @Test fun simultaneousPagesOnlyWriteOnce() {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") {
                        entered.countDown(); release.await(5, TimeUnit.SECONDS)
                        return profile(false)
                    }
                    return MockResponse().setResponseCode(204)
                }
            }
            val first = api(server); val second = api(server)
            try {
                val one = ensure(first)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val two = ensure(second)
                release.countDown()
                assertTrue(one.await().isSuccess); assertTrue(two.await().isSuccess)
                assertEquals(2, server.requestCount)
            } finally { release.countDown(); first.close(); second.close() }
        }
    }

    @Test fun explicitUnfollowAllowsNewFollow() {
        MockWebServer().use { server ->
            server.enqueue(profile(true))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)
            try {
                assertTrue(ensure(api).await().isSuccess)
                val removed = CompletableFuture<Result<Unit>>()
                api.followUser("creator", false) { removed.complete(it) }
                assertTrue(removed.await().isSuccess)
                assertTrue(ensure(api).await().isSuccess)
                // A recently acknowledged DELETE must not be undone by a stale profile GET.
                assertEquals(listOf("GET", "DELETE", "POST"), List(3) { server.takeRequest().method })
            } finally { api.close() }
        }
    }

    @Test fun verifiedDuplicateResponseIsSuccess() {
        MockWebServer().use { server ->
            server.enqueue(profile(false))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Already following"}"""))
            server.enqueue(profile(true))
            val api = api(server)
            try {
                assertTrue(ensure(api).await().isSuccess)
                assertTrue(ensure(api).await().isSuccess)
                assertEquals(3, server.requestCount)
            } finally { api.close() }
        }
    }

    @Test fun unconfirmedErrorRemainsRetryable() {
        MockWebServer().use { server ->
            server.enqueue(profile(false))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Denied"}"""))
            server.enqueue(profile(false))
            server.enqueue(profile(false)); server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)
            try {
                assertTrue(ensure(api).await().isFailure)
                assertTrue(ensure(api).await().isSuccess)
                assertEquals(5, server.requestCount)
            } finally { api.close() }
        }
    }

    @Test fun anotherAccountCannotReuseFollow() {
        MockWebServer().use { server ->
            server.enqueue(profile(true))
            server.enqueue(profile(false)); server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)
            try {
                assertTrue(ensure(api).await().isSuccess)
                AppPrefs(context).accountId = "account-b"
                assertTrue(ensure(api).await().isSuccess)
                assertEquals(3, server.requestCount)
            } finally { api.close() }
        }
    }

    @Test fun accountChangeDuringReadPreventsWrite() {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown(); release.await(5, TimeUnit.SECONDS)
                    return profile(false)
                }
            }
            val api = api(server)
            try {
                val result = ensure(api)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                AppPrefs(context).accountId = "account-b"
                release.countDown()
                assertTrue(result.await().isFailure)
                assertEquals(1, server.requestCount)
            } finally { release.countDown(); api.close() }
        }
    }

    @Test fun wrongProfileCannotConfirmFollow() {
        MockWebServer().use { server ->
            server.enqueue(profile(true, "different-author"))
            server.enqueue(MockResponse().setResponseCode(409))
            server.enqueue(profile(true, "different-author"))
            val api = api(server)
            try { assertTrue(ensure(api).await().isFailure) } finally { api.close() }
        }
    }

    @Test fun missingUsernameStillDeduplicatesWrites() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)
            try {
                assertTrue(ensure(api, "").await().isSuccess)
                assertTrue(ensure(api, "").await().isSuccess)
                assertEquals(1, server.requestCount)
            } finally { api.close() }
        }
    }
}
