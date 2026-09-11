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
            customMethod = "POST", customBody = "{}", customHeaders = "A: b", customResultPath = "r.0"
        )
        prefs.translation = custom
        assertEquals(custom, AppPrefs(RuntimeEnvironment.getApplication()).translation)
        prefs.translation = TranslationConfig()
    }
}
