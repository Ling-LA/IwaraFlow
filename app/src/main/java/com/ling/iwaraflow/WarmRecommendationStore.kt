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
internal class WarmRecommendationStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val context = context.applicationContext
    private val prefs = AppPrefs(this.context)
    private val secrets = SecureSessionStore(this.context)
    private val flags = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    @Volatile private var lastPayload: String? = null
    @Volatile private var lastGeneration = -1L
    private val retainedLock = Any()
    private val retainedRows = HashMap<String, String>()
    private var retainedScope = ""
    private var retainedSaveRevision = 0L

    private fun isCurrent(owner: String, expectedScope: String, revision: Long): Boolean =
        revision == generation.get() && !prefs.privateBrowsing && owner == prefs.accountId && expectedScope == scope(prefs)

    fun available(): Boolean = !prefs.privateBrowsing && flags.getBoolean("available", false)

    fun save(upcoming: List<VideoItem>) {
        if (prefs.privateBrowsing) return
        val owner = prefs.accountId
        val scope = scope(prefs)
        val revision = generation.get()
        val rows = synchronized(retainedLock) {
            if (!isCurrent(owner, scope, revision)) return
            retainedSaveRevision++
            val rowScope = "$owner|$scope|$revision"
            if (retainedScope != rowScope) { retainedRows.clear(); retainedScope = rowScope }
            val rows = upcoming.take(2).filter { it.id.isNotBlank() }.map { item ->
                val metadata = FeedSessionCodec.encode(FeedSessionStore.Session(listOf(item), 0, 0, true))
                val previous = retainedRows[item.id]?.let { runCatching { JSONObject(it) }.getOrNull() }
                val source = item.sources?.firstOrNull { it.url == item.streamUrl }
                // Restored partial files deliberately have no playable URL until re-resolution.
                // Do not erase that upcoming cached item while its new URL is still pending.
                if (source == null) return@map previous?.put("metadata", metadata)
                if (source.url.toHttpUrlOrNull()?.isHttps != true) return@map null
                val sourceSavedAt = previous?.takeIf { it.optString("url") == source.url }
                    ?.optLong("sourceSavedAt")?.takeIf { it > 0 } ?: now()
                JSONObject().put("metadata", metadata).put("url", source.url).put("name", source.name).put("score", source.score)
                    .put("sourceSavedAt", sourceSavedAt)
                    .put("sources", JSONArray(item.sources.orEmpty().take(12).filter { it.url.toHttpUrlOrNull()?.isHttps == true }.map {
                        JSONObject().put("name", it.name).put("url", it.url).put("score", it.score)
                    }))
            }.filterNotNull()
            val upcomingIds = upcoming.take(2).mapTo(hashSetOf()) { it.id }
            retainedRows.keys.removeAll { it !in upcomingIds }
            rows.forEach { row ->
                FeedSessionCodec.decode(row.getString("metadata"))?.items?.singleOrNull()?.let { retainedRows[it.id] = row.toString() }
            }
            rows
        }
        val payload = JSONObject().put("version", 1).put("owner", owner).put("scope", scope)
            .put("rows", JSONArray(rows)).toString()
        if (payload.length > 63_000) { clear(context); return }
        if (payload == lastPayload && revision == lastGeneration) return
        lastPayload = payload; lastGeneration = revision
        worker.execute {
            if (revision != generation.get() || prefs.privateBrowsing || owner != prefs.accountId || scope != scope(prefs)) return@execute
            val text = if (rows.isEmpty()) null else JSONObject(payload).put("savedAt", now()).toString()
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
        val saveRevision = synchronized(retainedLock) { retainedSaveRevision }
        worker.execute {
            // A restore may overlap an account change or a newer save on the UI thread.
            // Never expose its rows until their original account/generation is revalidated.
            val restoredRows = HashMap<String, String>()
            val result = runCatching {
                if (prefs.privateBrowsing) return@runCatching emptyList()
                val text = secrets.secret(KEY) ?: return@runCatching emptyList()
                if (text.length > 64_000) return@runCatching emptyList()
                val root = JSONObject(text)
                val now = now()
                val savedAt = root.optLong("savedAt")
                if (root.optInt("version") != 1 || root.optString("owner") != owner || root.optString("scope") != scope ||
                    savedAt <= 0 || savedAt > now) return@runCatching emptyList()
                val rows = root.getJSONArray("rows")
                require(rows.length() <= 2)
                val candidates = (0 until rows.length()).mapNotNull { i ->
                    val row = rows.getJSONObject(i)
                    val item = FeedSessionCodec.decode(row.getString("metadata"))?.items?.singleOrNull() ?: return@mapNotNull null
                    val source = VideoSource(row.getString("name"), row.getString("url"), row.getInt("score"))
                    if (source.url.toHttpUrlOrNull()?.isHttps != true) return@mapNotNull null
                    cache.registerSources(item.id, listOf(source))
                    val progress = cache.storedProgress(source.url)
                    // Media capacity/eviction determines retention. A many-days-old queue is
                    // still useful while its actual prefix remains; metadata alone never is.
                    if (!hasReusablePrefix(progress.cached, progress.total)) return@mapNotNull null
                    val sourceSavedAt = row.optLong("sourceSavedAt", savedAt)
                    row.put("sourceSavedAt", sourceSavedAt)
                    restoredRows[item.id] = row.toString()
                    item.resumePositionMs = 0L
                    item.resumePlayWhenReady = true
                    // A complete cached file can play without contacting an expired CDN URL.
                    val fresh = sourceFresh(source.url, sourceSavedAt, now)
                    if (!fresh) MediaCacheIdentity.requireCachedBytes(source.url)
                    if (progress.fraction >= 1.0 || fresh) {
                        val all = row.optJSONArray("sources")
                        item.sources = if (all == null) listOf(source) else (0 until minOf(all.length(), 12)).mapNotNull { index ->
                            val entry = all.getJSONObject(index)
                            val url = entry.getString("url")
                            if (url != source.url && !sourceFresh(url, sourceSavedAt, now)) null
                            else if (url.toHttpUrlOrNull()?.isHttps != true) null
                            else VideoSource(entry.getString("name"), url, entry.getInt("score"))
                        }.ifEmpty { listOf(source) }
                        item.streamUrl = source.url
                        item.refreshSourcesForSelection = true
                    } else {
                        // Resolve a fresh URL before requesting any missing bytes. Keeping the
                        // cached quality allows stable-key reuse after credentials rotate.
                        item.selectedQuality = source.name
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
            synchronized(retainedLock) {
                if (saveRevision == retainedSaveRevision && isCurrent(owner, scope, revision)) {
                    retainedScope = "$owner|$scope|$revision"
                    retainedRows.clear()
                    result.forEach { item -> restoredRows[item.id]?.let { retainedRows[item.id] = it } }
                }
            }
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
        internal const val MIN_PREFIX_BYTES = 64 * 1024L
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "IwaraFlow-warm-queue").apply { isDaemon = true } }
        private val generation = AtomicLong()
        private fun scope(prefs: AppPrefs) = "${prefs.isolateLibrary}|${prefs.isolateInterests}"
        internal fun hasReusablePrefix(bytes: Long, total: Long = -1) = bytes >= MIN_PREFIX_BYTES || (total > 0 && bytes >= total)
        internal fun sourceFresh(url: String, savedAt: Long, now: Long): Boolean {
            if (savedAt > now) return false
            val parsed = url.toHttpUrlOrNull() ?: return false
            val expiryText = parsed.queryParameter("expires")
            val expiry = expiryText?.let { value ->
                val number = value.toLongOrNull() ?: return false
                if (number < 0) return false
                if (number < 10_000_000_000L) number * 1000 else number
            }
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

