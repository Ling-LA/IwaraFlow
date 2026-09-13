package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 内置的几家翻译服务各自的返回格式，以及自定义接口的模板替换和字段路径取值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationProvidersTest {
    @Test fun deepLResponse() {
        val t = Translator.parseDeepL("""{"translations":[{"detected_source_language":"JA","text":"你好"}]}""")
        assertEquals("你好", t.text)
        assertEquals("ja", t.sourceLang)
    }

    @Test fun microsoftResponse() {
        val t = Translator.parseMicrosoft("""[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"你好","to":"zh-Hans"}]}]""")
        assertEquals("你好", t.text)
        assertEquals("en", t.sourceLang)
    }

    @Test fun baiduResponseJoinsLinesAndMapsLanguageCodes() {
        val t = Translator.parseBaidu("""{"from":"jp","to":"zh","trans_result":[{"src":"a","dst":"第一行"},{"src":"b","dst":"第二行"}]}""")
        assertEquals("第一行\n第二行", t.text)
        assertEquals("ja", t.sourceLang)
    }

    @Test fun baiduErrorsAreSurfaced() {
        val error = runCatching { Translator.parseBaidu("""{"error_code":"52003","error_msg":"UNAUTHORIZED USER"}""") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("52003"))
    }

    @Test fun baiduSignIsMd5OfAppIdTextSaltKey() {
        // md5("2015063000000001apple143566020257ed6716b3f4d2d9") 是百度文档里的示例。
        assertEquals("f89f9594663708c1605f3d736d01d2d4", Translator.baiduSign("2015063000000001", "apple", "1435660288", "12345678"))
    }

    @Test fun libreResponse() {
        val t = Translator.parseLibre("""{"translatedText":"你好","detectedLanguage":{"confidence":90,"language":"en"}}""")
        assertEquals("你好", t.text)
        assertEquals("en", t.sourceLang)
    }

    @Test fun openAiUrlIsCompletedFromWhateverTheUserTyped() {
        assertEquals("https://api.openai.com/v1/chat/completions", Translator.openAiUrl(""))
        assertEquals("https://api.openai.com/v1/chat/completions", Translator.openAiUrl("https://api.openai.com/v1/"))
        assertEquals("https://relay.example/v1/chat/completions", Translator.openAiUrl("https://relay.example"))
        assertEquals("https://relay.example/v1/chat/completions", Translator.openAiUrl("https://relay.example/"))
        assertEquals("https://relay.example/api/v3/chat/completions", Translator.openAiUrl("https://relay.example/api/v3"))
        assertEquals("https://relay.example/x/chat/completions", Translator.openAiUrl("https://relay.example/x/chat/completions"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            Translator.openAiUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        )
    }

    @Test fun openAiRequestCarriesModelAndPromptButNoSamplingParameters() {
        val body = org.json.JSONObject(Translator.openAiBody("hello", ""))
        assertEquals(Translator.DEFAULT_AI_MODEL, body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("hello", messages.getJSONObject(1).getString("content"))
        assertFalse("推理类模型不接受 temperature，请求里不能带", body.has("temperature"))
        assertEquals("deepseek-chat", org.json.JSONObject(Translator.openAiBody("x", " deepseek-chat ")).getString("model"))
    }

    @Test fun builtInVendorsNeedOnlyAKeyAndCustomUsesTheTypedAddress() {
        val deepseek = Translator.aiTarget(TranslationConfig(provider = Translator.PROVIDER_OPENAI, aiVendor = "deepseek", key = "k"))
        assertEquals("https://api.deepseek.com/v1/chat/completions" to "deepseek-chat", deepseek)
        val gemini = Translator.aiTarget(TranslationConfig(provider = Translator.PROVIDER_OPENAI, aiVendor = "gemini"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", gemini.first)
        val custom = Translator.aiTarget(TranslationConfig(
            provider = Translator.PROVIDER_OPENAI, aiVendor = Translator.AI_VENDOR_CUSTOM,
            endpoint = "https://relay.example/v1", model = "my-model"
        ))
        assertEquals("https://relay.example/v1/chat/completions" to "my-model", custom)
        assertEquals("认不出的服务商退回 OpenAI", Translator.DEFAULT_AI_MODEL, Translator.aiTarget(TranslationConfig(aiVendor = "nope")).second)
        assertTrue("每家都得有地址和模型", Translator.AI_VENDORS.dropLast(1).all { it.endpoint.isNotBlank() && it.model.isNotBlank() })
        assertEquals(Translator.AI_VENDOR_CUSTOM, Translator.AI_VENDORS.last().id)
    }

    @Test fun openAiResponse() {
        val t = Translator.parseOpenAi("""{"choices":[{"message":{"role":"assistant","content":"\"你好\""}}]}""")
        assertEquals("引号是模型多加的，去掉", "你好", t.text)
        assertEquals("没报语言就留空，之后按文字猜", "", t.sourceLang)
        val tagged = Translator.parseOpenAi("""{"choices":[{"message":{"content":"ja\n你好\n第二行"}}]}""")
        assertEquals("ja", tagged.sourceLang)
        assertEquals("你好\n第二行", tagged.text)
        val region = Translator.splitLanguageLine("pt_BR\nolá")
        assertEquals("pt-br", region.sourceLang)
        assertEquals("olá", region.text)
        assertEquals("第一行不是代码就整段当译文", "hello there\nworld", Translator.splitLanguageLine("hello there\nworld").text)
        val parts = Translator.parseOpenAi("""{"choices":[{"message":{"content":[{"type":"text","text":"你"},{"type":"text","text":"好"}]}}]}""")
        assertEquals("你好", parts.text)
        val error = runCatching { Translator.parseOpenAi("""{"error":{"message":"model not found","type":"invalid_request_error"}}""") }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("model not found"))
    }

    @Test fun httpErrorsCarryTheServersReason() {
        assertEquals("Incorrect API key", Translator.errorDetail("""{"error":{"message":"Incorrect API key","type":"x"}}"""))
        assertEquals("quota", Translator.errorDetail("""{"error":"quota"}"""))
        assertEquals("nope", Translator.errorDetail("""{"message":"nope"}"""))
        assertNull(Translator.errorDetail("<html>502</html>"))
    }

    @Test fun customUrlAndBodyTemplatesAreFilledIn() {
        assertEquals(
            "https://x.example/api?q=hello+%26+bye&to=zh-CN",
            Translator.customUrl("https://x.example/api?q={text}&to={target}", "hello & bye")
        )
        assertEquals(
            """{"q":"say \"hi\"\nnow","target":"zh-CN"}""",
            Translator.customBody("""{"q":"{text}","target":"{target}"}""", "say \"hi\"\nnow")
        )
        assertEquals(
            listOf("Authorization" to "Bearer abc", "X-Id" to "1"),
            Translator.customHeaders("Authorization: Bearer abc\n\nX-Id:1\nnonsense")
        )
    }

    @Test fun dottedPathsWalkObjectsAndArrays() {
        val raw = """{"data":{"translations":[{"translatedText":"你好","detectedSourceLanguage":"en"}]},"n":3}"""
        assertEquals("你好", Translator.extractPath(raw, "data.translations.0.translatedText"))
        assertEquals("en", Translator.extractPath(raw, "data.translations.0.detectedSourceLanguage"))
        assertEquals("3", Translator.extractPath(raw, "n"))
        assertNull(Translator.extractPath(raw, "data.missing"))
        assertNull("对象不算译文", Translator.extractPath(raw, "data"))
    }

    @Test fun anEmptyPathGuessesCommonFields() {
        assertEquals("你好", Translator.extractPath("""{"translatedText":"你好"}""", ""))
        assertEquals("你好", Translator.extractPath("""{"data":{"translatedText":"你好"}}""", ""))
        assertEquals("你好", Translator.extractPath("""{"translations":[{"text":"你好"}]}""", ""))
        assertNull(Translator.extractPath("""{"foo":"bar"}""", ""))
    }

    @Test fun theDefaultProviderIsGoogleAndSettingsRoundTrip() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        assertEquals(Translator.PROVIDER_GOOGLE, prefs.translation.provider)
        val custom = TranslationConfig(
            provider = Translator.PROVIDER_CUSTOM, endpoint = "https://x.example/{text}",
            customMethod = "POST", customBody = "{}", customHeaders = "A: b", customResultPath = "r.0", model = "gpt-4o-mini",
            aiVendor = "deepseek"
        )
        prefs.translation = custom
        assertEquals(custom, AppPrefs(RuntimeEnvironment.getApplication()).translation)
        prefs.translation = TranslationConfig()
    }
}
