package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

class IwaraSharedLinkTest {
    @Test fun extractsVideoFromSharedTextAndIgnoresSlugAndTracking() {
        assertEquals(IwaraSharedLink(IwaraSharedLink.Kind.VIDEO, "abc123"),
            IwaraSharedLink.parse("标题：示例\n作者：某某\n链接：https://www.iwara.tv/video/abc123/title?utm_source=share。"))
    }

    @Test fun acceptsAuthorAndLegacyLocaleLinks() {
        assertEquals(IwaraSharedLink(IwaraSharedLink.Kind.AUTHOR, "测试+name"),
            IwaraSharedLink.parse("作者：测试\nhttps://iwara.tv/profile/%E6%B5%8B%E8%AF%95+name"))
        assertEquals("abc", IwaraSharedLink.parse("(HTTPS://WWW.IWARA.TV/en/videos/abc)")?.key)
        assertEquals(IwaraSharedLink.Kind.AUTHOR, IwaraSharedLink.parse("http://iwara.tv/ja/users/alice")?.kind)
    }

    @Test fun rejectsOtherHostsUnsupportedPagesAndInvalidKeys() {
        listOf("https://iwara.tv.evil.test/video/abc", "https://evil.test/iwara.tv/video/abc",
            "https://iwara.tv@evil.test/video/abc", "https://evil@iwara.tv/video/abc",
            "https://iwara.tv:8888/video/abc", "https://iwara.tv/search?q=abc", "https://iwara.tv/video/",
            "https://iwara.tv/video/%2fetc", "https://iwara.tv/profile/%0Aalice", "https://iwara.tv/video/%ZZ",
            "file:///video/abc").forEach { assertNull(it, IwaraSharedLink.parse(it)) }
    }

    @Test fun oneShareOpensOnlyTheFirstValidIwaraLink() {
        assertEquals("first", IwaraSharedLink.parse("https://example.com https://iwara.tv/video/first https://iwara.tv/video/second")?.key)
    }
}
