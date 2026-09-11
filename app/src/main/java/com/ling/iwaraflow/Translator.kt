package com.ling.iwaraflow

import android.os.Handler
import android.os.Looper
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 翻译服务的配置：用哪家、密钥、地址；自定义接口的模板。 */
data class TranslationConfig(
    val provider: String = Translator.PROVIDER_GOOGLE,
    val key: String = "",
    val region: String = "",
    val appId: String = "",
    val endpoint: String = "",
    val customMethod: String = "GET",
    val customBody: String = "",
    val customHeaders: String = "",
    val customResultPath: String = "",
    val customLangPath: String = ""
)

/**
 * 评论和简介的中文翻译。
 *
 * 默认走谷歌翻译的免费网页接口（`translate_a/single`，`client=gtx`，不用密钥）；
 * 也内置了 DeepL、微软、百度、LibreTranslate 的适配，填上各自的密钥就能切换；
 * 还可以自己配一个接口：地址模板、请求方法、请求体、请求头和译文所在的字段路径。
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

    const val PROVIDER_GOOGLE = "google"
    const val PROVIDER_DEEPL = "deepl"
    const val PROVIDER_MICROSOFT = "microsoft"
    const val PROVIDER_BAIDU = "baidu"
    const val PROVIDER_LIBRE = "libre"
    const val PROVIDER_CUSTOM = "custom"

    /** 设置页里的顺序和名字。 */
    val PROVIDERS = listOf(
        PROVIDER_GOOGLE to "谷歌翻译（免费网页接口，不用密钥）",
        PROVIDER_DEEPL to "DeepL（API Key）",
        PROVIDER_MICROSOFT to "微软翻译（Azure Key + 区域）",
        PROVIDER_BAIDU to "百度翻译开放平台（APP ID + 密钥）",
        PROVIDER_LIBRE to "LibreTranslate（自建 / 公共服务器）",
        PROVIDER_CUSTOM to "自定义接口"
    )

    const val TARGET = "zh-CN"

    @Volatile var config = TranslationConfig()
        private set

    /** 换了服务或密钥就把缓存清掉：不同家翻出来的不一样。 */
    fun configure(next: TranslationConfig) {
        if (next == config) return
        config = next
        synchronized(cache) { cache.clear() }
    }

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
    private val jsonType = "application/json; charset=utf-8".toMediaType()

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
        val snapshot = config
        io.execute {
            val result = runCatching { fetch(text, snapshot) }
            result.onSuccess { if (snapshot == config) synchronized(cache) { cache[text] = it } }
            main.post { callback(result) }
        }
    }

    // ---------------------------------------------------------------- 各家接口

    private fun fetch(text: String, c: TranslationConfig): Translation = when (c.provider) {
        PROVIDER_DEEPL -> fetchDeepL(text, c)
        PROVIDER_MICROSOFT -> fetchMicrosoft(text, c)
        PROVIDER_BAIDU -> fetchBaidu(text, c)
        PROVIDER_LIBRE -> fetchLibre(text, c)
        PROVIDER_CUSTOM -> fetchCustom(text, c)
        else -> fetchGoogle(text)
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(when (response.code) {
                    401, 403 -> "密钥无效或没有权限（${response.code}）"
                    429 -> "请求太频繁（429），稍后再试"
                    456 -> "DeepL 额度已用完（456）"
                    else -> "HTTP ${response.code}"
                })
            }
            return raw
        }
    }

    private fun requireKey(value: String, what: String) {
        if (value.isBlank()) throw IOException("没填 $what，请到 设置 → 翻译 里配置")
    }

    // ---- 谷歌免费网页接口

    internal fun endpoint(text: String): HttpUrl =
        "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", TARGET)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()

    private fun fetchGoogle(text: String): Translation {
        val request = Request.Builder().url(endpoint(text))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        return parseResponse(execute(request))
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

    // ---- DeepL

    private fun fetchDeepL(text: String, c: TranslationConfig): Translation {
        requireKey(c.key, "DeepL API Key")
        // 免费版的 key 以 :fx 结尾，走 api-free 域名。
        val host = if (c.key.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = FormBody.Builder().add("text", text).add("target_lang", "ZH").build()
        val request = Request.Builder().url("https://$host/v2/translate")
            .header("Authorization", "DeepL-Auth-Key ${c.key.trim()}").post(body).build()
        return parseDeepL(execute(request))
    }

    internal fun parseDeepL(raw: String): Translation {
        val first = JSONObject(raw).optJSONArray("translations")?.optJSONObject(0)
            ?: throw IOException("返回格式不对")
        val text = first.optString("text")
        if (text.isBlank()) throw IOException("没有翻出内容")
        return Translation(text, first.optString("detected_source_language").lowercase().ifBlank { "auto" })
    }

    // ---- 微软 / Azure

    private fun fetchMicrosoft(text: String, c: TranslationConfig): Translation {
        requireKey(c.key, "Azure 翻译 Key")
        val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-Hans"
        val body = JSONArray().put(JSONObject().put("Text", text)).toString().toRequestBody(jsonType)
        val builder = Request.Builder().url(url)
            .header("Ocp-Apim-Subscription-Key", c.key.trim())
            .post(body)
        if (c.region.isNotBlank()) builder.header("Ocp-Apim-Subscription-Region", c.region.trim())
        return parseMicrosoft(execute(builder.build()))
    }

    internal fun parseMicrosoft(raw: String): Translation {
        val first = JSONArray(raw).optJSONObject(0) ?: throw IOException("返回格式不对")
        val text = first.optJSONArray("translations")?.optJSONObject(0)?.optString("text").orEmpty()
        if (text.isBlank()) throw IOException("没有翻出内容")
        val lang = first.optJSONObject("detectedLanguage")?.optString("language").orEmpty().ifBlank { "auto" }
        return Translation(text, lang)
    }

    // ---- 百度翻译开放平台

    internal fun baiduSign(appId: String, text: String, salt: String, key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest((appId + text + salt + key).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun fetchBaidu(text: String, c: TranslationConfig): Translation {
        requireKey(c.appId, "百度 APP ID")
        requireKey(c.key, "百度密钥")
        val salt = System.currentTimeMillis().toString()
        val url = "https://fanyi-api.baidu.com/api/trans/vip/translate".toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("from", "auto")
            .addQueryParameter("to", "zh")
            .addQueryParameter("appid", c.appId.trim())
            .addQueryParameter("salt", salt)
            .addQueryParameter("sign", baiduSign(c.appId.trim(), text, salt, c.key.trim()))
            .build()
        return parseBaidu(execute(Request.Builder().url(url).build()))
    }

    internal fun parseBaidu(raw: String): Translation {
        val root = JSONObject(raw)
        val error = root.optString("error_code")
        if (error.isNotBlank() && error != "0" && error != "52000") {
            throw IOException("百度返回错误 $error：${root.optString("error_msg")}")
        }
        val results = root.optJSONArray("trans_result") ?: throw IOException("返回格式不对")
        val text = (0 until results.length()).joinToString("\n") { results.optJSONObject(it)?.optString("dst").orEmpty() }
        if (text.isBlank()) throw IOException("没有翻出内容")
        // 百度的语言代码和别家不一样：jp = 日语，kor = 韩语。
        val lang = when (val from = root.optString("from")) { "jp" -> "ja"; "kor" -> "ko"; "" -> "auto"; else -> from }
        return Translation(text, lang)
    }

    // ---- LibreTranslate

    private fun fetchLibre(text: String, c: TranslationConfig): Translation {
        requireKey(c.endpoint, "LibreTranslate 服务器地址")
        val base = c.endpoint.trim().trimEnd('/')
        val json = JSONObject().put("q", text).put("source", "auto").put("target", "zh").put("format", "text")
        if (c.key.isNotBlank()) json.put("api_key", c.key.trim())
        val request = Request.Builder().url("$base/translate").post(json.toString().toRequestBody(jsonType)).build()
        return parseLibre(execute(request))
    }

    internal fun parseLibre(raw: String): Translation {
        val root = JSONObject(raw)
        val text = root.optString("translatedText")
        if (text.isBlank()) throw IOException(root.optString("error").ifBlank { "没有翻出内容" })
        val lang = root.optJSONObject("detectedLanguage")?.optString("language").orEmpty().ifBlank { "auto" }
        return Translation(text, lang)
    }

    // ---- 自定义接口

    /** 地址模板：`{text}` 会按 URL 编码替换，`{target}` 是目标语言。 */
    internal fun customUrl(template: String, text: String): String =
        template.trim()
            .replace("{text}", URLEncoder.encode(text, "UTF-8"))
            .replace("{target}", TARGET)

    /** 请求体模板：`{text}` 按 JSON 字符串转义替换（不含引号），`{target}` 是目标语言。 */
    internal fun customBody(template: String, text: String): String {
        val quoted = JSONObject.quote(text)
        return template.replace("{text}", quoted.substring(1, quoted.length - 1)).replace("{target}", TARGET)
    }

    /** 请求头：一行一个，`名字: 值`。 */
    internal fun customHeaders(lines: String): List<Pair<String, String>> =
        lines.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotBlank() }

    /**
     * 按 `a.b.0.c` 这样的路径在 JSON 里取值；数字段当数组下标。
     * 路径留空时按常见字段猜：translatedText / data.translatedText / translations.0.text / text。
     */
    internal fun extractPath(raw: String, path: String): String? {
        val trimmed = raw.trim()
        var node: Any = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }.getOrElse { return null }
        if (path.isBlank()) {
            return listOf("translatedText", "data.translatedText", "translations.0.text", "data.0.text", "text", "result")
                .firstNotNullOfOrNull { extractPath(raw, it) }
        }
        for (segment in path.split('.')) {
            node = when (node) {
                is JSONObject -> node.opt(segment) ?: return null
                is JSONArray -> segment.toIntOrNull()?.let { node.opt(it) } ?: return null
                else -> return null
            }
        }
        return when (node) {
            is String -> node
            is JSONObject, is JSONArray -> null
            else -> node.toString()
        }
    }

    private fun fetchCustom(text: String, c: TranslationConfig): Translation {
        requireKey(c.endpoint, "自定义接口地址")
        val builder = Request.Builder().url(customUrl(c.endpoint, text))
        customHeaders(c.customHeaders).forEach { (name, value) -> builder.header(name, value) }
        if (c.customMethod.equals("POST", ignoreCase = true)) {
            val body = customBody(c.customBody, text)
            val type = customHeaders(c.customHeaders).firstOrNull { it.first.equals("Content-Type", true) }?.second
                ?.toMediaType() ?: jsonType
            builder.post(body.toRequestBody(type))
        }
        val raw = execute(builder.build())
        val translated = extractPath(raw, c.customResultPath)?.takeIf { it.isNotBlank() }
            ?: throw IOException("返回里没找到译文（字段路径：${c.customResultPath.ifBlank { "自动" }}）")
        val lang = c.customLangPath.takeIf { it.isNotBlank() }?.let { extractPath(raw, it) }.orEmpty().ifBlank { "auto" }
        return Translation(translated, lang)
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
