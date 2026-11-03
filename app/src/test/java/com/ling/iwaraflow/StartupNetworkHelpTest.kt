package com.ling.iwaraflow

import android.app.Application
import android.content.Intent
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = StartupNetworkHelpTest.IsolatedApplication::class)
class StartupNetworkHelpTest {
    class IsolatedApplication : Application()

    private val context get() = RuntimeEnvironment.getApplication()
    private fun spans(view: TextView): Array<ClickableSpan> =
        (view.text as? Spanned)?.getSpans(0, view.text.length, ClickableSpan::class.java) ?: emptyArray()

    private fun assertLinks(helper: StartupNetworkHelp, failure: Throwable? = UnknownHostException("example.invalid"),
                            message: String = "连接失败"): TextView = TextView(context).also {
        helper.bind(it, message, failure)
        assertEquals(View.VISIBLE, it.visibility)
        assertTrue(it.text.toString().startsWith(message))
        assertEquals(2, spans(it).size)
        assertTrue(it.linksClickable)
        assertTrue(it.movementMethod is LinkMovementMethod)
        assertTrue(it.text.toString().contains("网络服务推广链接 1（可选）"))
        assertTrue(it.text.toString().contains("网络服务推广链接 2（可选）"))
    }

    private fun assertPlain(helper: StartupNetworkHelp, failure: Throwable?, message: String = "加载失败") {
        val view = TextView(context)
        helper.bind(view, message, failure)
        assertEquals(message, view.text.toString())
        assertEquals(0, spans(view).size)
        assertNull(view.movementMethod)
        assertFalse(view.linksClickable)
    }

    @Test fun linksAreOptionalAndEachOpensOnlyItsOwnDestinationOnClick() {
        val view = assertLinks(StartupNetworkHelp())
        val application = shadowOf(context)
        assertNull("Rendering a connection error must not open a browser", application.nextStartedActivity)
        spans(view).forEachIndexed { index, span ->
            span.onClick(view)
            val intent = application.nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(AppLinks.NETWORK_PROMOTIONS[index], intent.dataString)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertNull(application.nextStartedActivity)
        }
    }

    @Test fun newInstancesAndRepeatedFailuresAlwaysOfferOptionalHelp() {
        val helper = StartupNetworkHelp()
        repeat(3) { assertLinks(helper) }
        assertLinks(StartupNetworkHelp())
        assertLinks(StartupNetworkHelp())
    }

    @Test fun existingInstallMarkersDoNotExcludeSubsequentLaunches() {
        context.getSharedPreferences(AppPrefs.FILE, 0).edit()
            .putBoolean(PlaybackGuide.key(false), true)
            .putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).apply()
        assertLinks(StartupNetworkHelp())
    }

    @Test fun typedConnectionFailuresAndNestedCausesWorkWithoutEnglishMessages() {
        val helper = StartupNetworkHelp()
        val failures = listOf(UnknownHostException(), ConnectException(), SocketException(),
            SocketTimeoutException(), TimeoutException(), SSLHandshakeException("连接失败"))
        failures.forEach { failure ->
            assertLinks(helper, failure)
            assertLinks(helper, IOException("读取失败", RuntimeException("重试失败", failure)))
        }
    }

    @Test fun knownTransportMessagesWorkForAggregatedErrorsWithNoThrowable() {
        val helper = StartupNetworkHelp()
        listOf("Connection reset", "ECONNRESET", "Network is unreachable", "timed out",
            "Unable to resolve host example.invalid").forEach { message ->
            assertLinks(helper, null, message)
            assertLinks(helper, IOException(message))
        }
    }

    @Test fun emptyResultsAndOrdinaryServerOrPermissionErrorsDoNotShowPromotion() {
        val helper = StartupNetworkHelp()
        assertPlain(helper, null, "没有找到视频")
        assertPlain(helper, IOException("errors.unauthorized"))
        assertPlain(helper, IOException("errors.forbidden"))
        assertPlain(helper, IOException("errors.serverError"))
        listOf(401, 403, 500, 503).forEach { status ->
            assertPlain(helper, IOException("HTTP $status: timeout"))
            assertPlain(helper, IOException("HTTP/1.1 $status", SocketTimeoutException()))
            assertPlain(helper, PlaylistHttpException(status, "Connection reset"))
        }
    }

    @Test fun actualApiHttpReplyWithTransportWordsInItsBodyIsNotAConnectionFailure() {
        // The API keeps the response body as its message, which need not contain "HTTP 500".
        val type = IwaraApi::class.java.declaredClasses.single { it.simpleName == "ApiHttpException" }
        val constructor = type.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        val helper = StartupNetworkHelp()
        listOf(401, 403, 500).forEach { status ->
            val failure = constructor.newInstance(status, "timeout") as Throwable
            assertTrue(IwaraApi.isHttpFailure(failure))
            val wrapped = recommendationFailure(failure)
            assertSame("The aggregate error must keep the original HTTP failure", failure, wrapped.cause)
            assertPlain(helper, wrapped, wrapped.message.orEmpty())
        }
    }

    @Test fun recommendationAggregateKeepsConnectionCauseForClassifyingTranslatedMessages() {
        val failure = SocketTimeoutException()
        val wrapped = recommendationFailure(failure)
        assertSame(failure, wrapped.cause)
        assertLinks(StartupNetworkHelp(), wrapped, wrapped.message.orEmpty())
    }

    @Test fun recommendationRequestBudgetTimeoutStillOffersBothLinks() {
        val api = mock(IwaraApi::class.java) { invocation ->
            if (invocation.method.name.endsWith("Blocking")) {
                Thread.sleep(10_000)
                throw AssertionError("The caller must stop waiting before this stalled request completes")
            }
            Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val engine = RecommendationEngine(api, mock(HistoryStore::class.java), listBudgetMs = 1_000, nowMs = { 0L })
        try {
            val wrapped = invokeRecommendationFailure(engine)
            assertTrue("The aggregate must carry the request budget timeout", wrapped.cause is TimeoutException)
            assertLinks(StartupNetworkHelp(), wrapped, wrapped.message.orEmpty())
        } finally { engine.close() }
    }

    @Test fun emptyRecommendationRoundDoesNotReuseThePreviousRoundsConnectionFailure() {
        val failRequests = java.util.concurrent.atomic.AtomicBoolean(true)
        val original = UnknownHostException("example.invalid")
        val api = mock(IwaraApi::class.java) { invocation ->
            if (invocation.method.name.endsWith("Blocking")) {
                if (failRequests.get()) throw original
                if (invocation.method.returnType == VideoListPage::class.java) VideoListPage(emptyList(), 0)
                else emptyList<VideoItem>()
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val engine = RecommendationEngine(api, mock(HistoryStore::class.java), listBudgetMs = 2_000, nowMs = { 0L })
        try {
            val first = invokeRecommendationFailure(engine)
            assertSame(original, first.cause)
            assertLinks(StartupNetworkHelp(), first, first.message.orEmpty())
            failRequests.set(false)
            val empty = invokeRecommendationFailure(engine)
            assertNull("Successful empty responses must not inherit the previous connection failure", empty.cause)
            assertPlain(StartupNetworkHelp(), empty, empty.message.orEmpty())
        } finally { engine.close() }
    }

    private fun recommendationFailure(failure: Throwable): Throwable {
        val api = mock(IwaraApi::class.java) { invocation ->
            if (invocation.method.name.endsWith("Blocking")) throw failure
            Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val engine = RecommendationEngine(api, mock(HistoryStore::class.java), listBudgetMs = 2_000, nowMs = { 0L })
        try {
            return invokeRecommendationFailure(engine)
        } finally { engine.close() }
    }

    private fun invokeRecommendationFailure(engine: RecommendationEngine): Throwable {
        val method = RecommendationEngine::class.java.getDeclaredMethod("loadBlocking", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return try {
            method.invoke(engine, false)
            throw AssertionError("No recommendation requests produced content, so an error must be returned")
        } catch (error: java.lang.reflect.InvocationTargetException) {
            error.targetException
        }
    }

    @Test fun cancellationWinsOverConnectionWordsOrNestedNetworkErrors() {
        val helper = StartupNetworkHelp()
        val failures = listOf(CancellationException("Connection reset"), InterruptedException("timed out"),
            IOException("Canceled", SocketTimeoutException()), IOException("请求已取消", UnknownHostException()))
        failures.forEach { assertPlain(helper, it) }
        assertPlain(helper, SocketTimeoutException(), "HTTP 500 timeout")
        assertPlain(helper, SocketTimeoutException(), "request cancelled")
    }

    @Test fun rebindingOrdinaryOrEmptyStatusClearsAllStaleLinkTouchHandling() {
        val helper = StartupNetworkHelp()
        val view = assertLinks(helper)
        helper.bind(view, "没有找到视频", null)
        assertEquals("没有找到视频", view.text.toString())
        assertEquals(0, spans(view).size)
        assertNull(view.movementMethod)
        assertFalse(view.linksClickable)
        assertFalse(view.isClickable)
        assertFalse(view.isLongClickable)
        helper.bind(view, "", UnknownHostException())
        assertEquals(View.GONE, view.visibility)
        assertEquals("", view.text.toString())
        assertEquals(0, spans(view).size)
        helper.bind(view, "连接超时", SocketTimeoutException())
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals("A cleared status must not disable help for later connection failures", 2, spans(view).size)
    }

    @Test fun malformedCyclicCauseChainCannotHangStatusRendering() {
        val a = IOException("读取失败")
        val b = IOException("响应失败", a)
        a.initCause(b)
        assertPlain(StartupNetworkHelp(), a)
    }
}
