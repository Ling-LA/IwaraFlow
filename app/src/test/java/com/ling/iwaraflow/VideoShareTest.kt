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
        assertEquals("标题：标题\n作者：作者\n发布时间：未知\n链接：https://www.iwara.tv/video/abc123", VideoShare.shareText(item("abc123", "标题")))
    }

    @Test fun missingMetadataHasExplicitFallbacks() {
        assertEquals("标题：未命名视频\n作者：未知作者\n发布时间：未知\n链接：https://www.iwara.tv/video/abc123",
            VideoShare.shareText(item("abc123", "   ").copy(author = "")))
    }

    @Test fun publicationTimeAndAuthorUsernameAreIncluded() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val video = item("abc123", "标题").copy(author = "", authorUsername = "alice",
                createdAt = java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli())
            val text = VideoShare.shareText(video)
            assertTrue(text.contains("作者：alice"))
            assertEquals("发布时间：2026-09-28", text.lines()[2])
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
}
