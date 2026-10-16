package com.ling.iwaraflow

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A bounded, encrypted handoff of the two upcoming recommendations across process death. */
internal class WarmRecommendationStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = AppPrefs(this.context)
    private val secrets = SecureSessionStore(this.context)
    private val flags = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    @Volatile private var lastPayload: String? = null
    @Volatile private var lastGeneration = -1L

    fun available(): Boolean = !prefs.privateBrowsing && flags.getBoolean("available", false)

    fun save(upcoming: List<VideoItem>) {
        if (prefs.privateBrowsing) return
        val owner = prefs.accountId
        val scope = scope(prefs)
        val revision = generation.get()
        val rows = upcoming.take(2).filter { it.id.isNotBlank() }.map { item ->
            val source = item.sources?.firstOrNull { it.url == item.streamUrl } ?: return@map null
            if (source.url.toHttpUrlOrNull()?.isHttps != true) return@map null
            val metadata = FeedSessionCodec.encode(FeedSessionStore.Session(listOf(item), 0, 0, true))
            JSONObject().put("metadata", metadata).put("url", source.url).put("name", source.name).put("score", source.score)
                .put("sources", JSONArray(item.sources.orEmpty().take(12).filter { it.url.toHttpUrlOrNull()?.isHttps == true }.map {
                    JSONObject().put("name", it.name).put("url", it.url).put("score", it.score)
                }))
        }.filterNotNull()
        val payload = JSONObject().put("version", 1).put("owner", owner).put("scope", scope)
            .put("rows", JSONArray(rows)).toString()
        if (payload.length > 63_000) { clear(context); return }
        if (payload == lastPayload && revision == lastGeneration) return
        lastPayload = payload; lastGeneration = revision
        worker.execute {
            if (revision != generation.get() || prefs.privateBrowsing || owner != prefs.accountId || scope != scope(prefs)) return@execute
            val text = if (rows.isEmpty()) null else JSONObject(payload).put("savedAt", System.currentTimeMillis()).toString()
            val saved = runCatching { secrets.putSecretDurable(KEY, text) }.getOrDefault(false)
            runCatching { flags.edit().putBoolean("available", saved && text != null).commit() }
            if (!saved && lastPayload == payload && lastGeneration == revision) lastPayload = null
        }
    }

    /** All Keystore, cache-index and history work happens off the UI thread. */
    fun restore(cache: MediaPreloadCache, callback: (List<VideoItem>) -> Unit) {
        val owner = prefs.accountId
        val scope = scope(prefs)
        val revision = generation.get()
        worker.execute {
            val result = runCatching {
                if (prefs.privateBrowsing) return@runCatching emptyList()
                val text = secrets.secret(KEY) ?: return@runCatching emptyList()
                if (text.length > 64_000) return@runCatching emptyList()
                val root = JSONObject(text)
                val now = System.currentTimeMillis()
                val savedAt = root.optLong("savedAt")
                if (root.optInt("version") != 1 || root.optString("owner") != owner || root.optString("scope") != scope ||
                    now - savedAt !in 0..MAX_AGE_MS) return@runCatching emptyList()
                val rows = root.getJSONArray("rows")
                require(rows.length() <= 2)
                val candidates = (0 until rows.length()).mapNotNull { i ->
                    val row = rows.getJSONObject(i)
                    val item = FeedSessionCodec.decode(row.getString("metadata"))?.items?.singleOrNull() ?: return@mapNotNull null
                    val source = VideoSource(row.getString("name"), row.getString("url"), row.getInt("score"))
                    if (source.url.toHttpUrlOrNull()?.isHttps != true) return@mapNotNull null
                    cache.registerSources(item.id, listOf(source))
                    val progress = cache.storedProgress(source.url)
                    if (progress.cached < MIN_PREFIX_BYTES) return@mapNotNull null
                    item.resumePositionMs = 0L
                    item.resumePlayWhenReady = true
                    // A complete cached file can play without contacting an expired CDN URL.
                    if (progress.fraction >= 1.0 || sourceFresh(source.url, savedAt, now)) {
                        val all = row.optJSONArray("sources")
                        item.sources = if (all == null) listOf(source) else (0 until minOf(all.length(), 12)).mapNotNull { index ->
                            val entry = all.getJSONObject(index)
                            val url = entry.getString("url")
                            if (url != source.url && !sourceFresh(url, savedAt, now)) null
                            else if (url.toHttpUrlOrNull()?.isHttps != true) null
                            else VideoSource(entry.getString("name"), url, entry.getInt("score"))
                        }.ifEmpty { listOf(source) }
                        item.streamUrl = source.url
                        item.refreshSourcesForSelection = true
                    }
                    item
                }.distinctBy { it.id }
                val history = HistoryStore(context)
                try {
                    val statuses = history.loadStatuses(candidates.map { it.id })
                    val mutes = history.mutedEntities()
                    val taste = PreferenceProfile(emptyMap(), emptyMap(), mutedAuthors = mutes.authors, mutedAuthorIds = mutes.authorIds, mutedTags = mutes.tags)
                    candidates.filterNot { it.id in statuses.seen || taste.isMuted(it) }.onEach {
                        it.localFavorite = it.id in statuses.favorites
                    }
                } finally { history.close() }
            }.getOrDefault(emptyList())
            Handler(Looper.getMainLooper()).post {
                if (revision == generation.get() && !prefs.privateBrowsing && owner == prefs.accountId && scope == scope(prefs))
                    callback(result)
                else callback(emptyList())
            }
        }
    }

    companion object {
        private const val FILE = "warm_recommendation"
        private const val KEY = "warm_recommendation_queue"
        internal const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
        internal const val MIN_PREFIX_BYTES = 64 * 1024L
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "IwaraFlow-warm-queue").apply { isDaemon = true } }
        private val generation = AtomicLong()
        private fun scope(prefs: AppPrefs) = "${prefs.isolateLibrary}|${prefs.isolateInterests}"
        internal fun sourceFresh(url: String, savedAt: Long, now: Long): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            val expiry = parsed.queryParameter("expires")?.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
            return if (expiry != null) expiry > now + 60_000 else now - savedAt in 0..(15 * 60 * 1000L)
        }
        fun clear(context: Context) {
            val app = context.applicationContext
            generation.incrementAndGet()
            app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("available", false).apply()
            worker.execute {
                runCatching { SecureSessionStore(app).putSecretDurable(KEY, null) }
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("available", false).commit()
            }
        }
    }
}

