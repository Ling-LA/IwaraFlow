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
    val customLangPath: String = "",
    /** AI 翻译用的模型名（只在自定义服务商时生效），留空用 [Translator.DEFAULT_AI_MODEL]。 */
    val model: String = "",
    /** AI 翻译选的服务商，见 [Translator.AI_VENDORS]；内置服务商只要填 Key。 */
    val aiVendor: String = Translator.AI_VENDOR_OPENAI
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
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_CUSTOM = "custom"

    /** 设置页里的顺序和名字。 */
    val PROVIDERS = listOf(
        PROVIDER_GOOGLE to "谷歌翻译（免费网页接口，不用密钥）",
        PROVIDER_DEEPL to "DeepL（API Key）",
        PROVIDER_MICROSOFT to "微软翻译（Azure Key + 区域）",
        PROVIDER_BAIDU to "百度翻译开放平台（APP ID + 密钥）",
        PROVIDER_LIBRE to "LibreTranslate（自建 / 公共服务器）",
        PROVIDER_OPENAI to "AI 翻译（OpenAI 兼容接口：中转站 / DeepSeek / 通义 / Kimi 等）",
        PROVIDER_CUSTOM to "自定义接口"
    )

    const val TARGET = "zh-CN"

    /** AI 翻译的默认接口和模型：不填地址就直连 OpenAI。 */
    const val DEFAULT_AI_ENDPOINT = "https://api.openai.com/v1"
    const val DEFAULT_AI_MODEL = "gpt-4o-mini"

    /** 一家 AI 服务商：接口地址和默认模型都定好，用户只填 Key。 */
    data class AiVendor(val id: String, val name: String, val endpoint: String, val model: String)

    const val AI_VENDOR_OPENAI = "openai"
    const val AI_VENDOR_CUSTOM = "custom"

    /** 内置的主流服务商（都是 OpenAI 兼容的聊天接口），最后一项是自定义。 */
    val AI_VENDORS = listOf(
        AiVendor(AI_VENDOR_OPENAI, "OpenAI", DEFAULT_AI_ENDPOINT, DEFAULT_AI_MODEL),
        AiVendor("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        AiVendor("qwen", "通义千问（阿里云百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        AiVendor("moonshot", "Kimi（Moonshot）", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        AiVendor("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        AiVendor("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        AiVendor("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        AiVendor("xai", "xAI Grok", "https://api.x.ai/v1", "grok-3-mini"),
        AiVendor("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        AiVendor(AI_VENDOR_CUSTOM, "自定义（OpenAI 兼容接口）", "", "")
    )

    fun vendor(id: String): AiVendor = AI_VENDORS.firstOrNull { it.id == id } ?: AI_VENDORS.first()

    /** 这份配置实际会请求的接口地址和模型：内置服务商用预设，自定义用用户填的。 */
    internal fun aiTarget(c: TranslationConfig): Pair<String, String> {
        val v = vendor(c.aiVendor)
        return if (v.id == AI_VENDOR_CUSTOM) openAiUrl(c.endpoint) to c.model.trim().ifBlank { DEFAULT_AI_MODEL }
        else openAiUrl(v.endpoint) to v.model
    }

    /** 翻译失败时把原因交给页面记进诊断（不含原文）。 */
    @Volatile var onFailure: ((String) -> Unit)? = null

    /** 发给模型的系统提示：第一行报源语言代码，之后只要译文。 */
    const val AI_PROMPT = "你是翻译引擎。把用户发来的内容翻译成简体中文：保留原有的换行、表情、链接和 @用户名；" +
        "不要解释，不要加引号。输出格式：第一行只写原文语言的 ISO 639-1 代码（如 en、ja、ko、ru、es），" +
        "从第二行开始输出译文，不要输出别的内容。"

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
    /** 链接、邮箱、@用户名：里面全是拉丁字母，但不是“外语”，判断语言前先剥掉。 */
    private val noise = Regex("(https?://\\S+|www\\.\\S+|\\S+@\\S+\\.\\S+|@\\w+)")

    /** 有没有必要翻：剥掉链接后得有连着的至少两个字母（“1080p”这种不算），而且不是中文。 */
    fun needsTranslation(text: String): Boolean {
        val plain = stripNoise(text)
        return word.containsMatchIn(plain) && !isChinese(plain)
    }

    internal fun stripNoise(text: String): String = noise.replace(text, " ")

    /**
     * 原文是不是中文。带假名的一定是日语；否则汉字占字母的四成以上就当中文
     * （中文里夹几个英文单词很常见）。没有任何字母的（纯表情、数字）也当作不用翻。
     */
    fun isChinese(text: String): Boolean {
        val plain = stripNoise(text)
        if (plain.any { it in '぀'..'ヿ' }) return false
        val letters = plain.count { it.isLetter() }
        if (letters == 0) return true
        val han = plain.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return han * 100 / letters >= 40
    }

    /** 服务端识别出来源语言就是中文：那就不用翻，也不用显示那一行。 */
    fun isChineseSource(translation: Translation): Boolean =
        translation.sourceLang.lowercase().let { it == "zh" || it.startsWith("zh-") || it == "zh_cn" || it == "zh_tw" }

    fun translate(text: String, callback: (Result<Translation>) -> Unit) {
        cached(text)?.let { callback(Result.success(it)); return }
        val snapshot = config
        io.execute {
            val result = runCatching { fetch(text, snapshot) }.map { t ->
                // 服务没报源语言（或只说“auto”）就按文字本身猜，别把“外语”写在界面上。
                if (t.sourceLang.isBlank() || t.sourceLang.equals("auto", true)) t.copy(sourceLang = guessLanguage(text)) else t
            }
            result.onSuccess { if (snapshot == config) synchronized(cache) { cache[text] = it } }
            result.onFailure { error ->
                val where = if (snapshot.provider == PROVIDER_OPENAI) "${snapshot.provider}/${snapshot.aiVendor} ${aiTarget(snapshot).first.substringAfter("://").substringBefore('/')}" else snapshot.provider
                onFailure?.invoke("翻译失败（$where）：${error.javaClass.simpleName} ${error.message?.take(160)}")
            }
            main.post { callback(result) }
        }
    }

    // ---------------------------------------------------------------- 各家接口

    private fun fetch(text: String, c: TranslationConfig): Translation = when (c.provider) {
        PROVIDER_DEEPL -> fetchDeepL(text, c)
        PROVIDER_MICROSOFT -> fetchMicrosoft(text, c)
        PROVIDER_BAIDU -> fetchBaidu(text, c)
        PROVIDER_LIBRE -> fetchLibre(text, c)
        PROVIDER_OPENAI -> fetchOpenAi(text, c)
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
                    else -> "HTTP ${response.code}" + (errorDetail(raw)?.let { "：$it" } ?: "")
                })
            }
            return raw
        }
    }

    /** 出错时返回体里常带着原因（`error.message` 或 `message`），带上它比光一个状态码有用。 */
    internal fun errorDetail(raw: String): String? {
        val root = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null
        val message = root.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
            root.optString("error").takeIf { it.isNotBlank() && it != "null" && !it.startsWith("{") }.orEmpty()
        }.ifBlank { root.optString("message") }
        return message.takeIf { it.isNotBlank() && it != "null" }?.take(160)
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

    // ---- AI 翻译（OpenAI 兼容的聊天接口）

    /**
     * 把用户填的地址补成 `…/chat/completions`：只填域名就按 OpenAI 的 `/v1` 路径；
     * 带了路径（`/v1`、`/api/v3`、`/v1beta/openai` 这类）就在后面直接接上；填了完整路径原样用。
     */
    internal fun openAiUrl(base: String): String {
        val trimmed = base.trim().ifBlank { DEFAULT_AI_ENDPOINT }.trimEnd('/')
        val path = trimmed.substringAfter("://", trimmed).substringAfter('/', "")
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            path.isNotBlank() -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    /**
     * 请求体只带模型和消息：不带 temperature 之类的采样参数——推理类模型（o 系列、gpt-5 等）
     * 不接受非默认的 temperature，带上就是 400。
     */
    internal fun openAiBody(text: String, model: String): String =
        JSONObject()
            .put("model", model.trim().ifBlank { DEFAULT_AI_MODEL })
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", AI_PROMPT))
                .put(JSONObject().put("role", "user").put("content", text)))
            .toString()

    private fun fetchOpenAi(text: String, c: TranslationConfig): Translation {
        requireKey(c.key, "AI 接口的 API Key")
        val (url, model) = aiTarget(c)
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${c.key.trim()}")
            .post(openAiBody(text, model).toRequestBody(jsonType))
            .build()
        return parseOpenAi(execute(request))
    }

    /**
     * 标准返回是 `choices[0].message.content`；有些服务把 content 拆成分段数组，把文字段拼起来。
     * 模型偶尔会把整段译文包在引号里，去掉。
     */
    internal fun parseOpenAi(raw: String): Translation {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.let { error ->
            throw IOException("AI 接口返回错误：${error.optString("message").ifBlank { error.toString() }.take(160)}")
        }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IOException("返回格式不对")
        val content = when (val node = message.opt("content")) {
            is String -> node
            is JSONArray -> (0 until node.length()).joinToString("") { i ->
                node.optJSONObject(i)?.optString("text").orEmpty()
            }
            else -> ""
        }.trim()
        if (content.isBlank()) throw IOException("没有翻出内容")
        return splitLanguageLine(content)
    }

    private val langLine = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?$")

    /**
     * 模型按提示在第一行报语言代码：拆出来当源语言，其余是译文。模型没照做（第一行不是代码）
     * 就整段当译文、源语言留空，由 [translate] 再按文字猜。多余的引号一并去掉。
     */
    internal fun splitLanguageLine(content: String): Translation {
        val lines = content.lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        val (lang, body) = if (lines.size > 1 && langLine.matches(first)) {
            first.lowercase().replace('_', '-') to lines.drop(1).joinToString("\n").trim()
        } else "" to content
        val text = body.let { t ->
            if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("“") && t.endsWith("”"))))
                t.substring(1, t.length - 1).trim() else t
        }
        if (text.isBlank()) throw IOException("没有翻出内容")
        return Translation(text, lang)
    }

    /**
     * 按文字本身猜语言：假名 → 日语，谚文 → 韩语，西里尔 → 俄语，泰文 / 阿拉伯文各自对应，
     * 只有汉字 → 中文；拉丁字母按几个常见功能词区分西 / 葡 / 法 / 德 / 意 / 印尼，带越南语声调字母算越南语，
     * 都不像就当英语。没有字母的返回空串。
     */
    fun guessLanguage(text: String): String {
        val plain = stripNoise(text)
        if (plain.any { it in '぀'..'ヿ' }) return "ja"
        if (plain.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }) return "ko"
        if (plain.any { it in 'Ѐ'..'ӿ' }) return "ru"
        if (plain.any { it in '฀'..'๿' }) return "th"
        if (plain.any { it in '؀'..'ۿ' }) return "ar"
        val letters = plain.filter { it.isLetter() }
        // “1080p”这种单个字母不算文字，和 needsTranslation 的口径一致。
        if (letters.isEmpty() || !word.containsMatchIn(plain)) return ""
        if (letters.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return "zh"
        if (plain.any { it in "ăâđêôơưĂÂĐÊÔƠƯạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ" }) return "vi"
        val words = plain.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotBlank() }.toSet()
        fun hits(vararg stop: String) = stop.count { it in words }
        val scores = listOf(
            "es" to hits("que", "el", "los", "las", "muy", "gracias", "por", "pero", "como", "esta", "está", "una"),
            "pt" to hits("não", "muito", "você", "obrigado", "isso", "uma", "mais", "com", "também", "ele"),
            "fr" to hits("les", "est", "pour", "très", "merci", "vous", "une", "des", "pas", "c'est"),
            "de" to hits("und", "ist", "nicht", "das", "sehr", "danke", "ich", "die", "der", "auch"),
            "it" to hits("che", "non", "molto", "grazie", "per", "una", "anche", "sono", "questo"),
            "id" to hits("yang", "dan", "tidak", "saya", "ini", "itu", "banget", "bagus")
        )
        val best = scores.maxByOrNull { it.second }
        return if (best != null && best.second >= 2) best.first else "en"
    }

    /** 界面上那行小字：知道源语言就写“翻译自×语”，不知道就只说“已翻译”。 */
    fun sourceLabel(translation: Translation): String {
        val name = languageName(translation.sourceLang)
        return if (name.isBlank()) "已翻译" else "翻译自$name"
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

    fun languageName(code: String): String = when (code.lowercase().substringBefore('-').substringBefore('_')) {
        "ja" -> "日语"; "en" -> "英语"; "ko" -> "韩语"; "ru" -> "俄语"; "es" -> "西班牙语"
        "fr" -> "法语"; "de" -> "德语"; "pt" -> "葡萄牙语"; "it" -> "意大利语"; "th" -> "泰语"
        "vi" -> "越南语"; "id" -> "印尼语"; "zh" -> "中文"; "ar" -> "阿拉伯语"; "tr" -> "土耳其语"
        "pl" -> "波兰语"; "uk" -> "乌克兰语"; "nl" -> "荷兰语"; "tl" -> "菲律宾语"; "ms" -> "马来语"
        "", "auto", "und" -> ""
        else -> code
    }
}
