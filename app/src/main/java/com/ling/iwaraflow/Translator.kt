package com.ling.iwaraflow

import android.os.Handler
import android.os.Looper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 评论和简介的中文翻译，走谷歌翻译的免费网页接口（`translate_a/single`，`client=gtx`）。
 *
 * 只在面板打开、内容真的显示出来时才请求，结果按原文缓存，同一条不会翻第二次；
 * 原文已经是中文（或者根本没有文字，只有表情、数字）的不请求。
 * 请求排成一队串行发，别一口气打几十个并发出去。
 */
object Translator {
    data class Translation(val text: String, val sourceLang: String)

    /** 一条内容的翻译状态；界面按它决定显示什么。 */
    sealed class State {
        object Loading : State()
        data class Done(val translation: Translation, val showOriginal: Boolean = false) : State()
        data class Failed(val message: String) : State()
    }

    const val TARGET = "zh-CN"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val io = Executors.newSingleThreadExecutor()
    // 懒加载：纯函数（判断语言、解析返回）在没有 Android 主线程的单元测试里也要能用。
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val cache = object : LinkedHashMap<String, Translation>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translation>?): Boolean = size > 400
    }

    fun cached(text: String): Translation? = synchronized(cache) { cache[text] }

    private val word = Regex("\\p{L}{2,}")

    /** 有没有必要翻：得有连着的至少两个字母（“1080p”这种不算），而且不是中文。 */
    fun needsTranslation(text: String): Boolean = word.containsMatchIn(text) && !isChinese(text)

    /**
     * 原文是不是中文。带假名的一定是日语；否则汉字占字母的四成以上就当中文
     * （中文里夹几个英文单词很常见）。没有任何字母的（纯表情、数字）也当作不用翻。
     */
    fun isChinese(text: String): Boolean {
        if (text.any { it in '぀'..'ヿ' }) return false
        val letters = text.count { it.isLetter() }
        if (letters == 0) return true
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return han * 100 / letters >= 40
    }

    fun translate(text: String, callback: (Result<Translation>) -> Unit) {
        cached(text)?.let { callback(Result.success(it)); return }
        io.execute {
            val result = runCatching { fetch(text) }
            result.onSuccess { synchronized(cache) { cache[text] = it } }
            main.post { callback(result) }
        }
    }

    internal fun endpoint(text: String): HttpUrl =
        "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", TARGET)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()

    private fun fetch(text: String): Translation {
        val request = Request.Builder().url(endpoint(text))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(when (response.code) {
                    429 -> "请求太频繁（429），稍后再试"
                    403 -> "接口拒绝了请求（403）"
                    else -> "HTTP ${response.code}"
                })
            }
            return parseResponse(raw)
        }
    }

    /**
     * 返回体是嵌套数组：`[[["译文","原文",...],["译文2","原文2",...]],null,"ja",...]`。
     * 第一层第 0 项是分段列表，每段第 0 个是译文；第 2 项是识别出的源语言。
     */
    internal fun parseResponse(raw: String): Translation {
        val root = JSONArray(raw)
        val segments = root.optJSONArray(0) ?: throw IOException("返回格式不对")
        val text = buildString {
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                append(segment.optString(0))
            }
        }
        if (text.isBlank()) throw IOException("没有翻出内容")
        val lang = root.optString(2).takeIf { it.isNotBlank() && it != "null" } ?: "auto"
        return Translation(text, lang)
    }

    /** 把连接层的错误说成人话；其它照原样。 */
    fun explain(error: Throwable): String {
        val message = error.message.orEmpty()
        return NetworkProxy.explain(message)?.let { "连不上翻译服务" } ?: message.ifBlank { "翻译失败" }
    }

    fun languageName(code: String): String = when (code.lowercase().substringBefore('-')) {
        "ja" -> "日语"; "en" -> "英语"; "ko" -> "韩语"; "ru" -> "俄语"; "es" -> "西班牙语"
        "fr" -> "法语"; "de" -> "德语"; "pt" -> "葡萄牙语"; "it" -> "意大利语"; "th" -> "泰语"
        "vi" -> "越南语"; "id" -> "印尼语"; "zh" -> "中文"; "auto" -> "外语"
        else -> code
    }
}
