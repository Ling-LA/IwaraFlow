package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 国内用户交来的诊断都是 `Connection reset`——Iwara 在 TLS 握手阶段就被掐断。
 * 应用能做的是把错误说成人话，并允许手动指定代理端口。
 */
class NetworkProxyTest {
    @Test fun noProxyWhenTurnedOffOrIncomplete() {
        assertNull(NetworkProxy.proxyFor(NetworkProxy.TYPE_NONE, "127.0.0.1", 7890))
        assertNull(NetworkProxy.proxyFor(NetworkProxy.TYPE_HTTP, "", 7890))
        assertNull(NetworkProxy.proxyFor(NetworkProxy.TYPE_HTTP, "127.0.0.1", 0))
        assertNull(NetworkProxy.proxyFor(NetworkProxy.TYPE_HTTP, "127.0.0.1", 70000))
        assertNull(NetworkProxy.proxyFor("weird", "127.0.0.1", 7890))
    }

    @Test fun httpAndSocksProxiesKeepTheHostUnresolved() {
        val http = NetworkProxy.proxyFor(NetworkProxy.TYPE_HTTP, " 127.0.0.1 ", 7890)!!
        assertEquals(Proxy.Type.HTTP, http.type())
        val address = http.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
        assertTrue("设置页不解析主机名，连的时候才解析", address.isUnresolved)

        val socks = NetworkProxy.proxyFor(NetworkProxy.TYPE_SOCKS, "proxy.local", 1080)!!
        assertEquals(Proxy.Type.SOCKS, socks.type())
    }

    @Test fun connectionResetIsExplainedAsABlockedSite() {
        val text = NetworkProxy.explain("date 第 0 页加载失败：Connection reset")!!
        assertTrue(text.contains("连不上 Iwara"))
        assertTrue("要告诉用户去哪里填代理", text.contains("设置"))
        assertNotNull(NetworkProxy.explain("Unable to resolve host \"apiq.iwara.tv\""))
        assertNotNull(NetworkProxy.explain("SSLHandshakeException: Handshake failed"))
        assertNotNull(NetworkProxy.explain("timeout"))
    }

    @Test fun otherErrorsAreLeftAlone() {
        assertNull(NetworkProxy.explain("HTTP 500"))
        assertNull(NetworkProxy.explain(null))
        assertNull(NetworkProxy.explain("视频已删除或不存在"))
    }
}
