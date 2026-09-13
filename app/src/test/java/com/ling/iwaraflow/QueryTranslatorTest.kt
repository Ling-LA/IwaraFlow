package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/** 搜索词的中英日韩互译：认语言、解析 AI 的四行回答、把写法并成一份搜索词。 */
class QueryTranslatorTest {
    @Test fun theInputLanguageIsRecognisedAmongTheFour() {
        assertEquals(QueryTranslator.ZH, QueryTranslator.sourceLanguage("克拉拉"))
        assertEquals(QueryTranslator.EN, QueryTranslator.sourceLanguage("Clara"))
        assertEquals(QueryTranslator.JA, QueryTranslator.sourceLanguage("クラーラ"))
        assertEquals(QueryTranslator.KO, QueryTranslator.sourceLanguage("클라라"))
    }

    /** 认不出的语言当英文那一路：另外三种照样补齐，不会因此不翻。 */
    @Test fun otherLanguagesFallIntoTheEnglishLane() {
        assertEquals(QueryTranslator.EN, QueryTranslator.sourceLanguage("Клара"))
    }

    @Test fun theAiAnswerIsReadLineByLine() {
        val variants = QueryTranslator.parseVariants(
            """
            zh: 克拉拉
            en: Clara
            ja: クラーラ
            ko: 클라라
            """.trimIndent()
        )
        assertEquals("克拉拉", variants[QueryTranslator.ZH])
        assertEquals("Clara", variants[QueryTranslator.EN])
        assertEquals("クラーラ", variants[QueryTranslator.JA])
        assertEquals("클라라", variants[QueryTranslator.KO])
    }

    /** 模型爱加的项目符号、引号、全角冒号和 zh-CN 这种写法都得认。 */
    @Test fun theAnswerIsReadEvenWhenTheModelDressesItUp() {
        val variants = QueryTranslator.parseVariants("- **zh-CN**：“克拉拉”\n* EN: \"Clara\"\n随便说的一句话\nja：クラーラ")
        assertEquals("克拉拉", variants[QueryTranslator.ZH])
        assertEquals("Clara", variants[QueryTranslator.EN])
        assertEquals("クラーラ", variants[QueryTranslator.JA])
        assertNull(variants[QueryTranslator.KO])
    }

    @Test fun theOriginalWordComesFirstAndDuplicatesGoAway() {
        val merged = QueryTranslator.merge("Clara", listOf("克拉拉", "clara", "クラーラ", "클라라"))
        assertEquals(listOf("Clara", "克拉拉", "クラーラ", "클라라"), merged)
    }

    @Test fun atMostFourWaysOfWritingAreSearched() {
        val merged = QueryTranslator.merge("a", listOf("b", "c", "d", "e", "f"))
        assertEquals(QueryTranslator.MAX_VARIANTS, merged.size)
        assertEquals("a", merged.first())
    }

    @Test fun wholeSentencesAndEmptyInputAreNotTranslated() {
        assertFalse(QueryTranslator.worthTranslating(""))
        assertFalse(QueryTranslator.worthTranslating("1080"))
        val sentence = "请把这段很长很长的句子当成搜索词来处理然后再翻译成另外三种语言看看会怎么样好不好呢再多写几个字凑够长度"
        assertTrue(sentence.length > QueryTranslator.MAX_QUERY_LENGTH)
        assertFalse(QueryTranslator.worthTranslating(sentence))
        assertTrue(QueryTranslator.worthTranslating("克拉拉"))
        assertTrue(QueryTranslator.worthTranslating("Clara"))
    }
}
