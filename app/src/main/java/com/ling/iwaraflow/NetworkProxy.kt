package com.ling.iwaraflow

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 用户手动指定的代理。
 *
 * 两台国内的 Android 16 机器交来的诊断都是同一个样子：所有榜单请求全部失败、
 * `Connection reset`，没有任何 Java 异常——连接在 TLS 握手阶段就被掐了，
 * 这是 Iwara 在国内被阻断的典型特征，App 自己绕不过去。用户能做的是让流量走代理，
 * 但不少代理工具只开了本地端口（比如 127.0.0.1:7890）而没开 VPN 模式，
 * 系统层面不会替应用转发。所以这里给一个入口，让 App 自己走那个端口。
 *
 * 设成进程级默认代理（[ProxySelector.setDefault]）：OkHttp、Media3 的 HttpURLConnection、
 * Coil 全部跟着走，不用每个客户端各配一遍。
 */
object NetworkProxy {
    const val TYPE_NONE = "none"
    const val TYPE_HTTP = "http"
    const val TYPE_SOCKS = "socks"

    /** 应用当前保存的代理设置。启动时和保存设置后各调一次。 */
    fun apply(prefs: AppPrefs) {
        val proxy = proxyFor(prefs.proxyType, prefs.proxyHost, prefs.proxyPort)
        ProxySelector.setDefault(if (proxy == null) systemDefault else Selector(proxy))
    }

    fun proxyFor(type: String, host: String, port: Int): Proxy? {
        if (type == TYPE_NONE || host.isBlank() || port !in 1..65535) return null
        val kind = when (type) {
            TYPE_HTTP -> Proxy.Type.HTTP
            TYPE_SOCKS -> Proxy.Type.SOCKS
            else -> return null
        }
        // 不解析主机名：解析失败也不该在设置页就崩，连的时候再报。
        return Proxy(kind, InetSocketAddress.createUnresolved(host.trim(), port))
    }

    /** 记住进程启动时系统给的默认，关掉代理时要能还回去。 */
    private val systemDefault: ProxySelector? = ProxySelector.getDefault()

    private class Selector(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host.orEmpty()
            // 本机地址不走代理，否则代理工具自己的端口也会被套一层。
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return listOf(Proxy.NO_PROXY)
            return listOf(proxy)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 交给调用方处理失败；这里不切回直连，否则用户看到的就是时好时坏。
        }
    }

    /**
     * 把连接层的错误翻译成用户能采取行动的话。"Connection reset" 对用户来说什么都不是，
     * "连不上 Iwara，请检查代理" 才是。
     */
    fun explain(message: String?): String? {
        val m = message.orEmpty()
        val blocked = listOf("Connection reset", "connection reset", "ECONNRESET", "Connection refused",
            "timed out", "timeout", "Unable to resolve host", "No address associated",
            "Network is unreachable", "SSLHandshake", "Handshake failed", "Software caused connection abort")
        if (blocked.none { m.contains(it, ignoreCase = true) }) return null
        return "连不上 Iwara（$m）。\n\nIwara 在部分地区无法直接访问：请确认代理 / VPN 已开启并对本应用生效。" +
            "如果代理工具只开了本地端口而没开 VPN 模式，可以在 设置 → 网络 里填上那个端口。"
    }
}
