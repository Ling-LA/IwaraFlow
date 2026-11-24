package com.ling.iwaraflow

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Local, bounded keyword evidence. No model request and no additional video-detail fetch. */
object ContentInterest {
    data class Features(val title: Set<String>, val body: Set<String>)
    private val links = Regex("https?://\\S+|www\\.\\S+|[\\w.+-]+@[\\w.-]+", RegexOption.IGNORE_CASE)
    private val markup = Regex("<[^>]*>|!\\[[^]]*]\\([^)]*\\)")
    private val promotion = Regex("patreon|fantia|fanbox|gumroad|subscribe|subscription|sponsor|commission|discord|telegram|support me|download|follow me|all rights|转载|订阅|赞助|推广|下载|禁止|版权|支持我|お借り|配布|転載|支援|구독|후원|다운로드|무단", RegexOption.IGNORE_CASE)
    // Android 9's ICU does not accept the Java-style IsLatin / IsHan script aliases.
    private val clauses = Regex("[\\r\\n，,。；;！!？?|•·—–]+|\\s+-\\s+")
    private val words = Regex("[\\p{script=Latin}]{3,24}|[\\p{script=Cyrillic}]{3,24}|[가-힣]{2,12}|[ぁ-ゖ]{2,16}|[ァ-ヺー]{2,16}|[\\p{script=Han}]{2,24}")
    private val stop = setOf("the", "and", "with", "for", "this", "that", "from", "have", "you", "your", "are", "was", "not", "but", "will", "can", "video", "videos", "new", "full", "version", "preview", "model", "motion", "camera", "stage", "effect", "mmd", "blender", "test", "please", "thanks", "thank", "作品", "视频", "作品紹介", "新作", "测试", "演示", "更新", "大家", "这个", "今回", "動画", "영상", "모델", "untitled", "iwara", "です", "ます", "から", "まで", "した", "して", "する")
    private val cache = object : LinkedHashMap<String, Features>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Features>?) = size > 512
    }

    fun features(item: VideoItem): Features {
        val title = item.title.take(180)
        val body = item.description.take(1600)
        val key = title + '\u001f' + body
        return synchronized(cache) { cache[key] } ?: Features(extract(title, 24), extract(body, 32)).also {
            synchronized(cache) { cache[key] = it }
        }
    }

    private fun extract(raw: String, limit: Int): Set<String> {
        val plain = links.replace(markup.replace(SearchQuery.normalize(raw), " "), " ")
        val text = clauses.split(plain).filterNot(promotion::containsMatchIn).joinToString(" ")
        val result = linkedSetOf<String>()
        // Canonical names bridge the supported languages without guessing entity translations.
        val remaining = SearchQuery.extractConcepts(text, result)
        result.removeAll(stop)
        words.findAll(remaining).forEach { match ->
            val word = match.value
            if (word in stop) return@forEach
            if (word.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                if (word.length <= 4) result += word
                if (word.length > 4) for (size in 4 downTo 2) {
                    word.windowed(size).filterNot { it in stop }.forEach { result += it }
                }
            } else result += word
        }
        return result.take(limit).toSet()
    }

    fun score(item: VideoItem, profile: PreferenceProfile): Double {
        if (profile.textWeights.isEmpty()) return 0.0
        val features = features(item)
        val tags = item.tags.mapNotNull { raw ->
            val key = SearchQuery.canonicalTag(raw)
            val learned = profile.tagWeights[key] ?: profile.tagWeights[raw.lowercase()] ?: 0.0
            key.takeIf { learned != 0.0 || (profile.manualTagPreferences[key] ?: 0) != 0 }
        }.toSet()
        fun component(terms: Set<String>, scale: Double): List<Double> = terms.filterNot { it in tags }
            .mapNotNull { profile.textWeights[it]?.times(scale) }
        val matches = component(features.title, 1.0) + component(features.body - features.title, .5)
        return (matches.filter { it > 0 }.sortedDescending().take(3).sum().coerceAtMost(1.2) +
            matches.filter { it < 0 }.sorted().take(3).sum().coerceAtLeast(-1.8))
    }

    class Learning {
        private data class Evidence(val documents: MutableMap<String, Double> = linkedMapOf(),
            val authors: MutableSet<String> = hashSetOf(), val explicit: MutableSet<String> = hashSetOf())
        private val terms = hashMapOf<String, Evidence>()
        private val documents = hashSetOf<String>()
        private val actions = hashSetOf<String>()

        fun observe(video: String, author: String, action: String, weight: Double, ageMs: Long,
            title: Collection<String>, body: Collection<String>) {
            // Rows arrive newest first, including ties. Revoking a reaction supersedes
            // that reaction only; independent viewing and other reactions still count.
            val kind = when (action) {
                "unlike" -> "like"
                "unfavorite" -> "favorite"
                "watch", "complete", "like", "favorite", "follow", "skip" -> action
                else -> return
            }
            if (video.isBlank() || video.startsWith("author:") || !weight.isFinite() ||
                !actions.add("$video\u001f$kind") || action != kind) return
            documents += video
            val signal = weight.coerceIn(-3.0, 3.0) * .5.pow(ageMs.coerceAtLeast(0).toDouble() / (30 * 86_400_000.0))
            fun add(tokens: Collection<String>, strength: Double) = tokens.toSet().forEach { token ->
                val evidence = terms.getOrPut(token) { Evidence() }
                evidence.documents[video] = ((evidence.documents[video] ?: 0.0) + signal * strength).coerceIn(-3.0, 3.0)
                if (author.isNotBlank() && !author.equals("unknown", true) && !author.equals("iwara", true))
                    evidence.authors += author.lowercase()
                if (signal > 0 && action in setOf("like", "favorite", "follow")) evidence.explicit += video
            }
            add(title, 1.0); add(body.toSet() - title.toSet(), .5)
        }

        fun weights(now: Long): Map<String, Double> {
            val inactive = SeasonalInterest.inactiveTags(now)
            return terms.mapNotNull { (term, evidence) ->
                val count = evidence.documents.size
                val signal = evidence.documents.values.sum()
                if (count < 2 || evidence.authors.size < 2 ||
                    (evidence.explicit.isEmpty() && count < 3) ||
                    // A ubiquitous passive-exposure word may just be a template. Repeated
                    // explicit choices or negative feedback must not disappear as usage grows.
                    (documents.size >= 6 && count > documents.size * .75 && evidence.explicit.size < 2 && signal > 0) ||
                    term in inactive) return@mapNotNull null
                val specificity = (ln((documents.size + 2.0) / (count + 1.0)) + .3).coerceIn(.3, 1.0)
                term to (signal * .14 * specificity).coerceIn(-.8, .6)
            }.filter { abs(it.second) >= .02 }.sortedByDescending { abs(it.second) }.take(256).toMap()
        }
    }
}
