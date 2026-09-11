package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 评论 / 简介翻译：谷歌免费接口的地址和返回格式，以及“什么该翻、什么不该翻”的判断。
 * 返回体用 org.json 解析，纯 JVM 里那是空壳，所以跑在 Robolectric 上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslatorTest {
    @Test fun theEndpointIsTheFreeWebTranslateApiTargetingChinese() {
        val url = Translator.endpoint("hello")
        assertEquals("translate.googleapis.com", url.host)
        assertEquals("/translate_a/single", url.encodedPath)
        assertEquals("gtx", url.queryParameter("client"))
        assertEquals("auto", url.queryParameter("sl"))
        assertEquals("zh-CN", url.queryParameter("tl"))
        assertEquals("hello", url.queryParameter("q"))
    }

    @Test fun theNestedArrayResponseIsJoinedIntoOneText() {
        val raw = """[[["你好，","Hello, ",null,null,10],["世界！","world!",null,null,10]],null,"en",null,null,null,null,[]]"""
        val t = Translator.parseResponse(raw)
        assertEquals("你好，世界！", t.text)
        assertEquals("en", t.sourceLang)
    }

    @Test fun aResponseWithoutSegmentsIsAnError() {
        assertThrows(java.io.IOException::class.java) { Translator.parseResponse("[null,null,\"en\"]") }
    }

    @Test fun chineseTextIsNotTranslated() {
        assertTrue(Translator.isChinese("这个渲染真好看"))
        assertTrue("夹几个英文单词还是中文", Translator.isChinese("这个 MMD 的渲染真好看 nice"))
        assertFalse(Translator.needsTranslation("好美"))
    }

    @Test fun japaneseWithKanaIsTranslatedEvenThoughItHasKanji() {
        assertFalse(Translator.isChinese("使用モデル「暁、響、雷、電」"))
        assertTrue(Translator.needsTranslation("使用モデル「暁、響、雷、電」"))
    }

    @Test fun englishAndKoreanAreTranslated() {
        assertTrue(Translator.needsTranslation("can't wait full version"))
        assertTrue(Translator.needsTranslation("너무 예뻐요"))
    }

    @Test fun emojiOrNumbersAloneAreLeftAlone() {
        assertFalse(Translator.needsTranslation("😍😍😍"))
        assertFalse(Translator.needsTranslation("1080p 👍"))
        assertFalse(Translator.needsTranslation("   "))
    }

    @Test fun languageCodesReadAsChineseNames() {
        assertEquals("日语", Translator.languageName("ja"))
        assertEquals("英语", Translator.languageName("en"))
        assertEquals("中文", Translator.languageName("zh-TW"))
        assertEquals("xx", Translator.languageName("xx"))
    }
}
