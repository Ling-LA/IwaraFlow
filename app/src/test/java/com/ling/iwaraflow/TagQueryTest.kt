package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/** 标签栏搜的是 Iwara 的视频标签，多个词是“同时带这些标签”，不是一句关键词。 */
class TagQueryTest {
    @Test fun oneWordIsOneTag() {
        assertEquals(listOf("mmd"), TagQuery.candidates("MMD"))
    }

    @Test fun severalWordsMeanSeveralTagsAtOnce() {
        assertEquals("mmd,genshin", TagQuery.candidates("MMD Genshin").first())
    }

    @Test fun separatorsCoverChinesePunctuationToo() {
        assertEquals(listOf("mmd", "原神"), TagQuery.tags("MMD，原神"))
    }

    @Test fun aMultiWordTagIsStillReachableThroughTheFallbacks() {
        assertEquals(
            listOf("hatsune,miku", "hatsune_miku", "hatsunemiku"),
            TagQuery.candidates("Hatsune Miku")
        )
    }

    @Test fun blankInputHasNoTagToSearch() {
        assertTrue(TagQuery.candidates("   ").isEmpty())
    }

    @Test fun statusShowsTheTagsActuallyUsed() {
        assertEquals("#mmd  #原神", TagQuery.display("mmd,原神"))
    }
}
