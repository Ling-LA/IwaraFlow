package com.ling.iwaraflow

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 搜索词的中英日韩互译。
 *
 * 搜「克拉拉」时同时按 Clara / クラーラ / 클라라 去搜，四种语言的结果并在一起；
 * 输入别的语言也一样，先认出它是四种里的哪一种（都不是就当成英文那一路），
 * 再把另外三种补齐。
 *
 * 优先问用户配好的 AI：人名、角色名、作品名这类专有名词，各语言有通行译名，
 * 一次要出三种写法 AI 比通用翻译准。没配 AI 就走谷歌免费网页接口，
 * 一个目标语言一个请求。哪一路失败就少一种写法，搜索本身照常进行。
 *
 * 结果按原词缓存，同一个词不会翻第二次。
 */
object QueryTranslator {
    const val ZH = "zh-CN"
    const val EN = "en"
    const val JA = "ja"
    const val KO = "ko"

    /** 互相补齐的四种语言，顺序就是展示顺序。 */
    val LANGUAGES = listOf(ZH, EN, JA, KO)

    /** 一次搜索最多用几种写法。 */
    const val MAX_VARIANTS = 4

    /** 太长的输入（整句话）不做互译：那不是搜索词，翻出来也搜不到东西。 */
    const val MAX_QUERY_LENGTH = 40

    const val AI_PROMPT = "你是搜索词转换器。用户会给一个搜索词（多半是人名、角色名、作品名或标签）。" +
        "给出它在简体中文、英文、日文、韩文里最常用的写法：专有名词用各语言的通行译名，" +
        "不要音译成四不像，也不要解释。严格按这四行输出，每行一个语言代码加冒号：\n" +
        "zh: …\nen: …\nja: …\nko: …"

    private val io = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val cache = object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 120
    }

    fun cached(query: String): List<String>? = synchronized(cache) { cache[query.trim()] }

    /**
     * 把搜索词展开成最多四种写法，第一个永远是用户输入的原词。
     * 回调在主线程；不需要翻（空词、太长、没有字母）时同步回调只带原词。
     */
    fun expand(query: String, callback: (List<String>) -> Unit) {
        val original = query.trim()
        if (!worthTranslating(original)) { callback(listOf(original).filter { it.isNotBlank() }); return }
        cached(original)?.let { callback(it); return }
        io.execute {
            val variants = runCatching { lookup(original) }.getOrDefault(emptyList())
            val merged = merge(original, variants)
            // 一个都没翻出来时不写缓存：多半是网络不通，下次搜还该再试。
            if (merged.size > 1) synchronized(cache) { cache[original] = merged }
            main.post { callback(merged) }
        }
    }

    /** 值不值得翻：有字母、不是一长串话。 */
    internal fun worthTranslating(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_QUERY_LENGTH) return false
        return trimmed.any { it.isLetter() }
    }

    /** 输入属于四种语言里的哪一种；认不出的当英文那一路（它的另外三种照样补齐）。 */
    internal fun sourceLanguage(query: String): String = when (Translator.guessLanguage(query)) {
        "zh" -> ZH
        "ja" -> JA
        "ko" -> KO
        else -> EN
    }

    private fun lookup(query: String): List<String> {
        val targets = LANGUAGES.filter { it != sourceLanguage(query) }
        if (Translator.aiReady()) {
            val byAi = runCatching { parseVariants(Translator.askAiBlocking(query, AI_PROMPT)) }.getOrDefault(emptyMap())
            val picked = targets.mapNotNull { byAi[it] }
            if (picked.isNotEmpty()) return picked
        }
        // 谷歌一次只翻一种语言：哪一种失败就少那一种。
        return targets.mapNotNull { target ->
            runCatching { Translator.translateToBlocking(query, target) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /** 「zh: 克拉拉」这样的一行，容得下模型爱加的项目符号、星号、引号和全角冒号。 */
    private val variantLine = Regex(
        "^[\\s\"'`*\\-–—•]*([A-Za-z]{2}(?:[-_][A-Za-z]{2,4})?)[\\s\"'`*]*[:：]\\s*(.+?)\\s*$"
    )

    private fun normalizeCode(raw: String): String? =
        when (raw.lowercase().substringBefore('-').substringBefore('_')) {
            "zh", "cn" -> ZH
            "en" -> EN
            "ja", "jp" -> JA
            "ko", "kr" -> KO
            else -> null
        }

    /** 解析 AI 的四行回答；认不出的行跳过，少一行就少一种写法。 */
    internal fun parseVariants(content: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in content.lines()) {
            val match = variantLine.find(line) ?: continue
            val code = normalizeCode(match.groupValues[1]) ?: continue
            val value = match.groupValues[2].trim().trim('"', '“', '”', '\'', '`', '*', ' ')
            if (value.isNotBlank() && value.length <= MAX_QUERY_LENGTH) out.putIfAbsent(code, value)
        }
        return out
    }

    /** 原词在最前，去掉重复的和明显不是搜索词的，最多 [MAX_VARIANTS] 个。 */
    internal fun merge(original: String, variants: Collection<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (candidate in listOf(original) + variants) {
            val value = candidate.trim()
            if (value.isBlank() || value.length > MAX_QUERY_LENGTH) continue
            if (!seen.add(value.lowercase())) continue
            out += value
            if (out.size >= MAX_VARIANTS) break
        }
        return out
    }
}
