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
        assertEquals("标题\nhttps://www.iwara.tv/video/abc123", VideoShare.shareText(item("abc123", "标题")))
    }

    @Test fun untitledVideosShareTheBareLink() {
        assertEquals("https://www.iwara.tv/video/abc123", VideoShare.shareText(item("abc123", "   ")))
    }

    private fun author(description: String) =
        IwaraAuthor("user-1", "Fixture 作者", "fixture", description)

    @Test fun authorCardCarriesNameProfileAndLink() {
        assertEquals(
            "Fixture 作者（@fixture）\n每周更新 MMD\nhttps://www.iwara.tv/profile/fixture",
            VideoShare.authorShareText(author("每周更新 MMD"))
        )
    }

    @Test fun anAuthorWithoutAProfileTextStillSharesTheLink() {
        assertEquals(
            "Fixture 作者（@fixture）\nhttps://www.iwara.tv/profile/fixture",
            VideoShare.authorShareText(author("   "))
        )
    }

    @Test fun authorLinkPointsAtTheProfilePage() {
        assertEquals("https://www.iwara.tv/profile/fixture", VideoShare.authorLinkFor(author("")))
    }
}
