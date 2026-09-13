package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** 失败原因给用户看的是话，不是 errors.serverError 这种码。 */
class ErrorWordingTest {
    private fun explain(message: String?) = IwaraApi.explainError(IOException(message))

    @Test fun serverErrorCodesBecomeAReason() {
        assertEquals("Iwara 服务器处理这次请求时出错，稍后再试或换个关键词", explain("errors.serverError"))
        assertEquals("内容已被删除或不存在", explain("errors.notFound"))
        assertEquals("请求太频繁，稍后再试", explain("errors.tooManyRequests"))
    }

    /** 认不出的错误码也要带一句解释，不能光把码甩给用户。 */
    @Test fun unknownCodesStillReadAsASentence() {
        val text = explain("errors.somethingNew")
        assertTrue(text, text.startsWith("Iwara 拒绝了这次请求"))
        assertFalse(text, text.startsWith("errors."))
    }

    @Test fun bareHttpStatusCodesBecomeAReason() {
        assertEquals("Iwara 服务器出错（HTTP 503），稍后再试", explain("HTTP 503"))
        assertEquals("内容不存在或已被删除（HTTP 404）", explain("HTTP 404"))
        assertEquals("需要登录后才能查看（HTTP 401）", explain("HTTP 401"))
    }

    @Test fun networkFailuresSayWhatToCheck() {
        assertTrue(IwaraApi.explainError(UnknownHostException("apiq.iwara.tv")).contains("域名解析失败"))
        assertTrue(IwaraApi.explainError(SocketTimeoutException("timeout")).contains("超时"))
        assertTrue(IwaraApi.explainError(SSLHandshakeException("handshake failed")).contains("加密连接"))
        assertTrue(IwaraApi.explainError(IOException("Connection reset")).contains("代理"))
    }

    /** 本来就是中文的提示原样用，别被 HTTP 数字规则吃掉。 */
    @Test fun messagesThatAreAlreadyReadableStayAsTheyAre() {
        assertEquals("请先登录 Iwara", explain("请先登录 Iwara"))
        assertEquals("视频源 HTTP 500", explain("视频源 HTTP 500"))
    }

    @Test fun anEmptyMessageNamesTheFailureType() {
        assertTrue(explain(null).startsWith("未知错误"))
    }
}
