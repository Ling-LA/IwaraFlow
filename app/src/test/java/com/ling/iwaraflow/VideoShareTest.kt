package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

/** 分享出去的必须是能在浏览器里打开的 Iwara 视频链接。 */
class VideoShareTest {
    private fun item(id: String, title: String) = VideoItem(id, title, "作者", emptyList(), 0)

    @Test fun shareLinkPointsAtTheIwaraVideoPage() {
        assertEquals("https://www.iwara.tv/video/abc123", VideoShare.linkFor(item("abc123", "标题")))
    }

    @Test fun shareTextKeepsTheTitleAboveTheLink() {
        assertEquals("标题：标题\n作者：作者\n链接：https://www.iwara.tv/video/abc123", VideoShare.shareText(item("abc123", "标题")))
    }

    @Test fun missingMetadataHasExplicitFallbacks() {
        assertEquals("标题：未命名视频\n作者：未知作者\n链接：https://www.iwara.tv/video/abc123",
            VideoShare.shareText(item("abc123", "   ").copy(author = "")))
    }

    @Test fun publicationTimeIsOmittedAndAuthorUsernameIsIncluded() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val video = item("abc123", "标题").copy(author = "", authorUsername = "alice",
                createdAt = java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli())
            val text = VideoShare.shareText(video)
            assertTrue(text.contains("作者：alice"))
            assertFalse(text.contains("发布时间"))
            assertEquals("链接：https://www.iwara.tv/video/abc123", text.lines()[2])
        } finally { java.util.TimeZone.setDefault(original) }
    }

    private fun author(description: String) =
        IwaraAuthor("user-1", "Fixture 作者", "fixture", description)

    @Test fun authorCardCarriesNameProfileAndLink() {
        assertEquals(
            "作者：Fixture 作者\n链接：https://www.iwara.tv/profile/fixture",
            VideoShare.authorShareText(author("每周更新 MMD"))
        )
    }

    @Test fun anAuthorWithoutAProfileTextStillSharesTheLink() {
        assertEquals(
            "作者：Fixture 作者\n链接：https://www.iwara.tv/profile/fixture",
            VideoShare.authorShareText(author("   "))
        )
    }

    @Test fun authorLinkPointsAtTheProfilePage() {
        assertEquals("https://www.iwara.tv/profile/fixture", VideoShare.authorLinkFor(author("")))
    }

    @Test fun shareContainsOnlyTheIwaraLinkForBothShareKinds() {
        val video = item("abc123", "中性示例")
        val creator = author("")
        val cases = listOf(
            VideoShare.shareText(video) to IwaraSharedLink(IwaraSharedLink.Kind.VIDEO, "abc123"),
            VideoShare.authorShareText(creator) to IwaraSharedLink(IwaraSharedLink.Kind.AUTHOR, "fixture")
        )
        for ((text, expected) in cases) {
            assertEquals(listOf(expected.url), Regex("https://\\S+").findAll(text).map { it.value }.toList())
            assertEquals("Pasting a share into search must still select its video or author", expected, IwaraSharedLink.parse(text))
            assertFalse(text.contains(AppLinks.DOWNLOAD))
        }
    }
}
