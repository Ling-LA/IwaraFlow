package com.ling.iwaraflow

import android.os.Looper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class TranslationAccuracyTest {
    private lateinit var server: MockWebServer
    private val japanese = "動画を見てくれてありがとう。また見てね。"
    @Before fun setup() {
        server = MockWebServer(); server.start()
        Translator.configure(TranslationConfig(provider = Translator.PROVIDER_OPENAI,
            aiVendor = Translator.AI_VENDOR_CUSTOM, endpoint = server.url("/v1").toString(), model = "fixture-model", key = "fixture"))
    }
    @After fun cleanup() { Translator.configure(TranslationConfig()); server.shutdown() }
    private fun reply(content: String) = MockResponse().setBody(JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("message", JSONObject().put("content", content)))).toString())
    private fun waitFor(done: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Callback did not finish", done())
    }
    @Test fun rejectsUnchangedOrStillJapaneseEvenWhenLabelSaysJapanese() {
        assertTrue(runCatching { Translator.validateTranslation(japanese, Translator.Translation(japanese, "ja")) }.isFailure)
        assertTrue(runCatching { Translator.validateTranslation(japanese, Translator.Translation("新しい動画を見てください。", "ja")) }.isFailure)
        assertTrue(runCatching { Translator.validateTranslation("Nice animation!", Translator.Translation("Nice animation!", "en")) }.isFailure)
    }
    @Test fun preservesNamesInsideARealChineseTranslation() {
        val result = Translator.validateTranslation(japanese, Translator.Translation("感谢观看动画！请再来看 Miku 的作品。", "ja"))
        assertEquals("ja", result.sourceLang)
        assertTrue(result.text.contains("Miku"))
    }
    @Test fun repairsUntranslatedAiResponseOnceAndCachesOnlyValidText() {
        server.enqueue(reply("ja\n$japanese")); server.enqueue(reply("ja\n感谢观看视频。下次再来看吧。"))
        var result: Result<Translator.Translation>? = null
        Translator.translate(japanese) { result = it }
        waitFor { result != null }
        assertEquals("感谢观看视频。下次再来看吧。", result!!.getOrThrow().text)
        assertEquals(2, server.requestCount)
        assertEquals(result!!.getOrThrow(), Translator.cached(japanese))
        val first = JSONObject(server.takeRequest().body.readUtf8())
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("fixture-model", first.getString("model"))
        assertTrue(second.getJSONArray("messages").getJSONObject(0).getString("content").contains("不能照抄原文"))
    }
    @Test fun queuedDuplicateTranslationUsesOneModelRequest() {
        server.enqueue(reply("ja\n感谢观看视频。"))
        var callbacks = 0
        repeat(2) { Translator.translate(japanese) { assertTrue(it.isSuccess); callbacks++ } }
        waitFor { callbacks == 2 }
        assertEquals(1, server.requestCount)
    }

    @Test fun failedRepairIsNotCachedOrReportedAsSuccess() {
        repeat(2) { server.enqueue(reply("ja\n$japanese")) }
        var result: Result<Translator.Translation>? = null
        Translator.translate(japanese) { result = it }
        waitFor { result != null }
        assertTrue(result!!.isFailure); assertNull(Translator.cached(japanese)); assertEquals(2, server.requestCount)
    }
    @Test fun aiSearchRequiresItsOwnSwitchAndPreservesVerifiedAliases() {
        var basic: List<String>? = null
        QueryTranslator.expand("长风", aiPrecision = false) { basic = it }
        assertNotNull(basic); assertEquals(0, server.requestCount)
        server.enqueue(reply("""{"aliases":[{"text":"Changfeng","language":"en","equivalent":true,"confidence":0.99}]}"""))
        var precise: List<String>? = null
        QueryTranslator.expand("长风", aiPrecision = true, context = "长风 动画") { precise = it }
        waitFor { precise != null }
        assertEquals(1, server.requestCount)
        assertTrue(precise!!.containsAll(SearchQuery.knownAliases("长风")!!))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("fixture-model", body.getString("model"))
        val input = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("长风", input.getString("term")); assertEquals("长风 动画", input.getString("context"))
    }
    @Test fun invalidAiSearchFallsBackToVerifiedAliasesWithWarning() {
        server.enqueue(reply("Sorry, I do not know."))
        var result: List<String>? = null; var warning = ""
        QueryTranslator.expand("长风", aiPrecision = true, onWarning = { warning = it }) { result = it }
        waitFor { result != null }
        assertEquals(SearchQuery.knownAliases("长风"), result)
        assertTrue(warning.contains("未完成")); assertEquals(1, server.requestCount)
    }
    @Test fun danmakuTranslationIsOptInAndReusesTheTranslationCache() {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()
        val prefs = AppPrefs(activity)
        val view = DanmakuView(activity)
        activity.setContentView(view)
        val player = org.mockito.Mockito.mock(androidx.media3.common.Player::class.java)
        view.player = player
        view.layout(0, 0, 400, 240)
        val bitmap = android.graphics.Bitmap.createBitmap(400, 240, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.setComments(listOf(IwaraComment("c1", japanese, IwaraAuthor("a", "作者", "author"), 0)))
        try {
            view.draw(canvas)
            assertEquals(0, server.requestCount)
            server.enqueue(reply("ja\n感谢观看，下次再来看吧。"))
            prefs.translateDanmaku = true
            view.draw(canvas)
            waitFor { Translator.cached(japanese) != null }
            repeat(10) { view.draw(canvas) }
            assertEquals(1, server.requestCount)
            prefs.translateDanmaku = false; view.draw(canvas)
            assertEquals(1, server.requestCount)
        } finally { view.clear(); controller.pause().stop().destroy(); bitmap.recycle() }
    }

    @Test fun rejectsUncertainBroaderAndWrongScriptAiAliases() {
        val raw = """{"aliases":[
            {"text":"Other topic","language":"en","equivalent":false,"confidence":0.99},
            {"text":"Guess","language":"en","equivalent":true,"confidence":0.6},
            {"text":"错误中文","language":"en","equivalent":true,"confidence":0.99},
            {"text":"Clara","language":"en","equivalent":true,"confidence":0.99},
            {"text":"クラーラ","language":"ja","equivalent":true,"confidence":0.95},
            {"text":"클라라","language":"ko","equivalent":true,"confidence":2.0}
        ]}"""
        assertEquals(listOf("Clara", "クラーラ"), QueryTranslator.parsePreciseVariants(raw, "克拉拉"))
        assertFalse(QueryTranslator.roundTripMatches("robot", "machine"))
        assertTrue(QueryTranslator.roundTripMatches("扶她", "futanari"))
    }
}
