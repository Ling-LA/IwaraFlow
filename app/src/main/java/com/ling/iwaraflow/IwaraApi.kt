package com.ling.iwaraflow

import android.content.Context
import android.util.Base64
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IwaraApi(context: Context) {
    private val apiRoot = "https://apiq.iwara.tv"
    private val siteRoot = "https://www.iwara.tv"
    private val session = SecureSessionStore(context.applicationContext)
    private val io = Executors.newCachedThreadPool()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isLoggedIn(): Boolean = !session.refreshToken.isNullOrBlank()

    fun logout() = session.clear()

    private fun baseRequest(url: String, authenticated: Boolean = false): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", "$siteRoot/")
            .header("Origin", siteRoot)
            .header("X-Site", "www.iwara.tv")
            .header("Accept", "application/json")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36"
            )
        if (authenticated) {
            ensureAccessTokenBlocking()?.let { builder.header("Authorization", "Bearer $it") }
        } else {
            session.accessToken?.takeIf { token -> tokenIsUsable(token, 60) }
                ?.let { builder.header("Authorization", "Bearer $it") }
        }
        return builder
    }

    fun login(email: String, password: String, callback: (LoginResult) -> Unit) {
        io.execute { callback(loginBlocking(email.trim(), password)) }
    }

    private fun loginBlocking(email: String, password: String): LoginResult {
        if (email.isBlank() || password.isBlank()) return LoginResult(false, "邮箱和密码不能为空")
        return try {
            val body = JSONObject().put("email", email).put("password", password).toString()
                .toRequestBody(jsonType)
            client.newCall(baseRequest("$apiRoot/user/login").post(body).build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return LoginResult(false, extractMessage(raw, "登录失败（HTTP ${response.code}）"))
                }
                val refresh = JSONObject(raw).optString("token")
                if (refresh.isBlank()) return LoginResult(false, "登录成功但服务器未返回 refresh token")
                session.refreshToken = refresh
                session.accessToken = null
                val access = refreshAccessTokenBlocking(refresh)
                if (access.isNullOrBlank()) {
                    session.clear()
                    LoginResult(false, "登录后获取 access token 失败")
                } else {
                    LoginResult(true, "登录成功")
                }
            }
        } catch (e: Exception) {
            LoginResult(false, e.message ?: "登录请求失败")
        }
    }

    private fun ensureAccessTokenBlocking(): String? {
        val current = session.accessToken
        if (!current.isNullOrBlank() && tokenIsUsable(current, 120)) return current
        val refresh = session.refreshToken ?: return null
        if (!tokenIsUsable(refresh, 0)) {
            session.clear()
            return null
        }
        return refreshAccessTokenBlocking(refresh)
    }

    private fun refreshAccessTokenBlocking(refreshToken: String): String? {
        return try {
            val req = baseRequest("$apiRoot/user/token")
                .header("Authorization", "Bearer $refreshToken")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) session.clear()
                    return null
                }
                val token = JSONObject(raw).optString("accessToken")
                if (token.isBlank()) return null
                session.accessToken = token
                token
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenIsUsable(token: String, leewaySeconds: Long): Boolean {
        return try {
            val parts = token.split('.')
            if (parts.size != 3) return false
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val exp = JSONObject(String(decoded, Charsets.UTF_8)).optLong("exp", 0L)
            exp > (System.currentTimeMillis() / 1000L) + leewaySeconds
        } catch (_: Exception) {
            false
        }
    }

    fun getVideos(
        sort: String,
        page: Int = 0,
        limit: Int = 24,
        callback: (Result<List<VideoItem>>) -> Unit
    ) {
        io.execute { callback(runCatching { getVideosBlocking(sort, page, limit) }) }
    }

    fun getVideosBlocking(sort: String, page: Int = 0, limit: Int = 24): List<VideoItem> {
        val url = "$apiRoot/videos".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("rating", "all")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val root = getJsonObject(url.toString(), optionalAuth = true)
        return parseVideoPage(root)
    }

    fun getVideo(videoId: String, callback: (Result<VideoItem>) -> Unit) {
        io.execute {
            callback(runCatching {
                parseVideo(getJsonObject("$apiRoot/video/$videoId", optionalAuth = true))
                    ?: throw IOException("视频不存在或已删除")
            })
        }
    }

    fun searchVideos(query: String, page: Int = 0, limit: Int = 32, callback: (Result<List<VideoItem>>) -> Unit) {
        io.execute { callback(runCatching { searchVideosBlocking(query, page, limit) }) }
    }

    fun searchVideosBlocking(query: String, page: Int = 0, limit: Int = 32): List<VideoItem> {
        val url = "$apiRoot/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("type", "videos")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return parseVideoPage(getJsonObject(url.toString(), optionalAuth = true))
    }

    fun getFavoriteVideos(callback: (Result<List<VideoItem>>) -> Unit) {
        io.execute { callback(runCatching { getFavoriteVideosBlocking() }) }
    }

    fun getFavoriteVideosBlocking(page: Int = 0, limit: Int = 100): List<VideoItem> {
        if (!isLoggedIn()) throw IOException("请先登录 Iwara")
        val url = "$apiRoot/favorites/videos".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val root = getJsonObject(url.toString(), requireAuth = true)
        val arr = root.optJSONArray("results") ?: JSONArray()
        val out = ArrayList<VideoItem>()
        for (i in 0 until arr.length()) {
            val wrapper = arr.optJSONObject(i) ?: continue
            val video = wrapper.optJSONObject("video") ?: wrapper
            parseVideo(video)?.let { out += it.copy(liked = true) }
        }
        return out
    }

    fun likeVideo(videoId: String, liked: Boolean, callback: (Result<Unit>) -> Unit) {
        io.execute {
            callback(runCatching {
                if (!isLoggedIn()) throw IOException("请先登录 Iwara")
                ensureAccessTokenBlocking() ?: throw IOException("登录已失效，请重新登录")
                val builder = baseRequest("$apiRoot/video/$videoId/like", authenticated = true)
                val request = if (liked) {
                    builder.post(ByteArray(0).toRequestBody(null)).build()
                } else {
                    builder.delete().build()
                }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 401) session.accessToken = null
                        throw IOException("同步失败（HTTP ${response.code}）")
                    }
                }
            })
        }
    }

    fun resolveSources(videoId: String, callback: (Result<List<VideoSource>>) -> Unit) {
        io.execute { callback(runCatching { resolveSourcesBlocking(videoId) }) }
    }

    fun resolveSourcesBlocking(videoId: String): List<VideoSource> {
        val detail = getJsonObject("$apiRoot/video/$videoId", optionalAuth = true)
        val fileUrl = detail.optString("fileUrl")
        if (fileUrl.isBlank()) throw IOException("该视频没有可直接播放的 fileUrl")
        return resolveFileUrlBlocking(fileUrl)
    }

    fun resolveStream(videoId: String, quality: String = "highest", callback: (Result<String>) -> Unit) {
        io.execute {
            callback(runCatching {
                val sources = resolveSourcesBlocking(videoId)
                chooseSource(sources, quality)?.url ?: throw IOException("没有可播放清晰度")
            })
        }
    }

    fun chooseSource(sources: List<VideoSource>, quality: String): VideoSource? {
        if (sources.isEmpty()) return null
        if (quality == "highest") return sources.maxByOrNull { it.score }
        val exact = sources.firstOrNull { it.name.equals(quality, ignoreCase = true) }
        if (exact != null) return exact
        val target = Regex("(\\d+)").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (target != null) {
            return sources.minByOrNull { kotlin.math.abs(it.score.coerceAtMost(9999) - target) }
        }
        return sources.maxByOrNull { it.score }
    }

    private fun resolveFileUrlBlocking(fileUrl: String): List<VideoSource> {
        val parsed = fileUrl.toHttpUrlOrNull() ?: throw IOException("错误的 fileUrl")
        val expires = parsed.queryParameter("expires") ?: throw IOException("fileUrl 缺少 expires")
        val fileId = parsed.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IOException("fileUrl 缺少文件 ID")
        val key = "${fileId}_${expires}_mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
        val sha = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val request = baseRequest(fileUrl)
            .header("X-Version", sha)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("视频源 HTTP ${response.code}")
            val arr = JSONArray(raw)
            val out = ArrayList<VideoSource>()
            for (i in 0 until arr.length()) {
                val source = arr.optJSONObject(i) ?: continue
                val src = source.optJSONObject("src") ?: continue
                val rawUrl = src.optString("download").takeIf { it.isNotBlank() }
                    ?: src.optString("view").takeIf { it.isNotBlank() }
                    ?: continue
                val name = source.optString("name", "Unknown")
                val resolution = Regex("(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val score = when {
                    name.equals("source", true) -> 10000
                    resolution != null -> resolution
                    else -> 0
                }
                val normalized = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                out += VideoSource(name = name, url = normalized, score = score)
            }
            if (out.isEmpty()) throw IOException("服务器未返回可播放视频源")
            return out.distinctBy { it.url }.sortedByDescending { it.score }
        }
    }

    private fun getJsonObject(url: String, optionalAuth: Boolean = false, requireAuth: Boolean = false): JSONObject {
        if (requireAuth && ensureAccessTokenBlocking().isNullOrBlank()) {
            throw IOException("登录已失效，请重新登录")
        }
        val shouldAuthenticate = requireAuth || (optionalAuth && isLoggedIn())
        val request = baseRequest(url, authenticated = shouldAuthenticate).get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 && (optionalAuth || requireAuth)) session.accessToken = null
                throw IOException(extractMessage(raw, "HTTP ${response.code}"))
            }
            return JSONObject(raw)
        }
    }

    private fun parseVideoPage(root: JSONObject): List<VideoItem> {
        val results = root.optJSONArray("results") ?: JSONArray()
        val list = ArrayList<VideoItem>()
        for (i in 0 until results.length()) {
            parseVideo(results.optJSONObject(i))?.let { list += it }
        }
        return list
    }

    private fun parseVideo(o: JSONObject?): VideoItem? {
        o ?: return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        val user = o.optJSONObject("user")
        val tagsJson = o.optJSONArray("tags")
        val tags = mutableListOf<String>()
        if (tagsJson != null) {
            for (j in 0 until tagsJson.length()) {
                val tagObj = tagsJson.optJSONObject(j)
                val tag = tagObj?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: tagObj?.optString("name")?.takeIf { it.isNotBlank() }
                if (tag != null) tags += tag
            }
        }
        val created = try {
            val s = o.optString("createdAt")
            if (s.isBlank()) 0L else Instant.parse(s).toEpochMilli()
        } catch (_: Exception) { 0L }
        return VideoItem(
            id = id,
            title = o.optString("title", "Untitled"),
            author = user?.optString("name")?.takeIf { it.isNotBlank() }
                ?: user?.optString("username")?.takeIf { it.isNotBlank() }
                ?: "Iwara",
            tags = tags,
            likes = o.optInt("numLikes", 0),
            views = o.optInt("numViews", 0),
            createdAt = created,
            liked = o.optBoolean("liked", false)
        )
    }

    private fun extractMessage(raw: String, fallback: String): String {
        return try {
            val o = JSONObject(raw)
            o.optString("message").takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) { fallback }
    }

    fun close() {
        io.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
