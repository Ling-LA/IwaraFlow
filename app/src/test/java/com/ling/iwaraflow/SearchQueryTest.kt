package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

class SearchQueryTest {
    @Test fun termsAreAndEvenWhenTheServerReturnsOrResults() {
        val query = SearchQuery.local("dance，robot")
        assertTrue(query.matchesTitle("ROBOT dance 2026"))
        assertFalse(query.matchesTitle("dance only"))
        assertFalse(query.matchesTitle("robot only"))
    }

    @Test fun knownSlangNeverUsesUnrelatedTranslations() {
        val query = SearchQuery.local("扶她")
        assertTrue(query.matchesTitle("FUTA animation"))
        assertTrue(query.matchesTitle("ふたなり animation"))
        assertFalse(query.matchesTitle("Support Her"))
        assertFalse(query.matchesTitle("双性恋"))
        assertFalse(query.matchesTitle("Seal of Lust"))
        assertFalse(query.matchesTitle("futastic"))
    }

    @Test fun mixedLanguagesMatchEachTermIndependently() {
        val query = SearchQuery.local("克拉拉 原神")
        assertTrue(query.matchesTitle("Clara 原神"))
        assertTrue(query.matchesTitle("クラーラ Genshin Impact"))
        assertFalse(query.matchesTitle("Clara"))
    }

    @Test fun tagSearchUsesWholeTagsAndRequiresEveryTerm() {
        val query = SearchQuery.local("dance robot")
        assertTrue(query.matchesTags(listOf("robot", "DANCE")))
        assertFalse(query.matchesTags(listOf("dance", "robotics")))
        assertFalse(query.matchesTags(listOf("dance_robot")))
        assertTrue(SearchQuery.local("扶她").matchesTags(listOf("futanari")))
    }

    @Test fun quotedPhrasesFullWidthAndUnderscoresAreSupported() {
        val query = SearchQuery.local("\"hatsune miku\" ＭＭＤ")
        assertEquals(2, query.groups.size)
        assertTrue(query.matchesTitle("初音ミク mmd"))
        assertTrue(query.matchesTags(listOf("hatsune_miku", "mmd")))
        assertEquals(listOf("dance", "robot"), SearchQuery.terms(" #dance；#robot "))
        assertFalse(SearchQuery.local("，；").matchesTitle("anything"))
    }

    @Test fun authorsUseLiteralNamesWithoutTranslation() {
        val query = SearchQuery.literal("alice studio")
        assertTrue(query.matchesAuthor(IwaraAuthor("1", "Alice", "studio")))
        assertFalse(query.matchesAuthor(IwaraAuthor("1", "Alice", "other")))
        assertFalse(SearchQuery.literal("Clara").matchesAuthor(IwaraAuthor("1", "克拉拉", "test")))
    }
}
