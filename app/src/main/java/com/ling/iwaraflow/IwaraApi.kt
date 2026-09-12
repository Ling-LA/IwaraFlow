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

class IwaraApi(context: Context, private val apiRoot: String = DEFAULT_API_ROOT) {
    private val siteRoot = "https://www.iwara.tv"
    private val imageRoot = "https://i.iwara.tv"
    private val session = SecureSessionStore(context.applicationContext)
    private val io = Executors.newCachedThreadPool()
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        // Keystore 解锁和加密 SharedPreferences 的第一次打开都比较慢，
        // 放到后台线程和界面初始化并行，冷启动主线程不再等它。
        runCatching { io.execute { runCatching { session.warmUp() } } }
    }

    fun isLoggedIn(): Boolean = !session.refreshToken.isNullOrBlank()
    fun logout() = session.clear()

    private fun baseRequest(url: String, authenticated: Boolean = false): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", "$siteRoot/")
            .header("Origin", siteRoot)
            .header("X-Site", "www.iwara.tv")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
        if (authenticated) {
            ensureAccessTokenBlocking()?.let { builder.header("Authorization", "Bearer $it") }
        } else {
            session.accessToken?.takeIf { token -> tokenIsUsable(token, 60) }
                ?.let { builder.header("Authorization", "Bearer $it") }
        }
        return builder
    }

    fun login(email: String, password: String, callback: (LoginResult) -> Unit) {
        enqueue(callback) { loginBlocking(email.trim(), password) }
    }

    private fun <T> enqueue(callback: (T) -> Unit, request: () -> T) {
        synchronized(lifecycleLock) {
            // Page callbacks can schedule follow-up requests while the Activity is closing.
            // Serialize submission with shutdown so that this cannot reject a late request.
            if (closed) return
            io.execute {
                if (closed) return@execute
                val result = request()
                if (!closed) callback(result)
            }
        }
    }

    private fun loginBlocking(email: String, password: String): LoginResult {
        if (email.isBlank() || password.isBlank()) return LoginResult(false, "邮箱和密码不能为空")
        return try {
            val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody(jsonType)
            client.newCall(baseRequest("$apiRoot/user/login").post(body).build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return LoginResult(false, extractMessage(raw, "登录失败（HTTP ${response.code}）"))
                val refresh = JSONObject(raw).optString("token")
                if (refresh.isBlank()) return LoginResult(false, "登录成功但服务器未返回 refresh token")
                session.refreshToken = refresh
                session.accessToken = null
                val access = refreshAccessTokenBlocking(refresh)
                if (access.isNullOrBlank()) {
                    session.clear(); LoginResult(false, "登录后获取 access token 失败")
                } else LoginResult(true, "登录成功")
            }
        } catch (e: Exception) {
            LoginResult(false, e.message ?: "登录请求失败")
        }
    }

    private fun ensureAccessTokenBlocking(): String? {
        val current = session.accessToken
        if (!current.isNullOrBlank() && tokenIsUsable(current, 120)) return current
        val refresh = session.refreshToken ?: return null
        if (!tokenIsUsable(refresh, 0)) { session.clear(); return null }
        return refreshAccessTokenBlocking(refresh)
    }

    private fun refreshAccessTokenBlocking(refreshToken: String): String? {
        return try {
            val req = baseRequest("$apiRoot/user/token")
                .header("Authorization", "Bearer $refreshToken")
                .post(ByteArray(0).toRequestBody(null)).build()
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
        } catch (_: Exception) { null }
    }

    private fun tokenIsUsable(token: String, leewaySeconds: Long): Boolean {
        return try {
            val parts = token.split('.')
            if (parts.size != 3) return false
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val exp = JSONObject(String(decoded, Charsets.UTF_8)).optLong("exp", 0L)
            exp > (System.currentTimeMillis() / 1000L) + leewaySeconds
        } catch (_: Exception) { false }
    }

    fun getVideos(sort: String, page: Int = 0, limit: Int = 24, callback: (Result<List<VideoItem>>) -> Unit) {
        enqueue(callback) { runCatching { getVideosBlocking(sort, page, limit) } }
    }

    fun getVideosBlocking(sort: String, page: Int = 0, limit: Int = 24): List<VideoItem> =
        getVideoListPageBlocking(sort, page, limit).videos

    /** 和 [getVideosBlocking] 同一个请求，另外带上服务端回报的列表总条数。 */
    fun getVideoListPageBlocking(sort: String, page: Int = 0, limit: Int = 24): VideoListPage {
        val url = "$apiRoot/videos".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort).addQueryParameter("rating", "all")
            .addQueryParameter("page", page.toString()).addQueryParameter("limit", limit.toString()).build()
        return parseVideoListPage(getJsonObject(url.toString(), optionalAuth = true))
    }

    /**
     * 关注作者的最新作品。Iwara 的订阅流就是视频列表接口加 `subscribed=true`；
     * 万一服务端忽略这个参数，拿回来的也是按时间排序的新视频，不会更差。
     */
    fun getSubscribedVideosBlocking(page: Int = 0, limit: Int = 36): List<VideoItem> =
        getSubscribedVideoPageBlocking(page, limit).videos

    /** 和 [getSubscribedVideosBlocking] 同一个请求，另外带上订阅流的总条数。 */
    fun getSubscribedVideoPageBlocking(page: Int = 0, limit: Int = 36): VideoListPage {
        if (!isLoggedIn()) return VideoListPage(emptyList(), -1)
        val url = "$apiRoot/videos".toHttpUrl().newBuilder()
            .addQueryParameter("subscribed", "true").addQueryParameter("sort", "date")
            .addQueryParameter("rating", "all")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.coerceAtMost(MAX_PAGE_LIMIT).toString()).build()
        return parseVideoListPage(getJsonObject(url.toString(), requireAuth = true))
    }

    fun getAuthorVideos(userId: String, page: Int = 0, limit: Int = 36, callback: (Result<List<VideoItem>>) -> Unit) {
        enqueue(callback) { runCatching { getAuthorVideosBlocking(userId, page, limit) } }
    }

    fun getAuthorVideosBlocking(userId: String, page: Int = 0, limit: Int = 36): List<VideoItem> {
        val url = "$apiRoot/videos".toHttpUrl().newBuilder()
            .addQueryParameter("user", userId).addQueryParameter("rating", "all")
            .addQueryParameter("page", page.toString()).addQueryParameter("limit", limit.toString()).build()
        return parseVideoPage(getJsonObject(url.toString(), optionalAuth = true))
    }

    fun getVideo(videoId: String, callback: (Result<VideoItem>) -> Unit) {
        enqueue(callback) { runCatching {
            parseVideo(getJsonObject("$apiRoot/video/$videoId", optionalAuth = true)) ?: throw IOException("视频不存在或已删除")
        } }
    }

    fun searchVideos(query: String, page: Int = 0, limit: Int = 32, callback: (Result<List<VideoItem>>) -> Unit) {
        enqueue(callback) { runCatching { searchVideosBlocking(query, page, limit) } }
    }

    fun searchVideosBlocking(query: String, page: Int = 0, limit: Int = 32): List<VideoItem> {
        val url = "$apiRoot/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("type", "videos")
            .addQueryParameter("page", page.toString()).addQueryParameter("limit", limit.toString()).build()
        return parseVideoPage(getJsonObject(url.toString(), optionalAuth = true))
    }

    fun searchUsers(query: String, page: Int = 0, limit: Int = 24, callback: (Result<List<IwaraAuthor>>) -> Unit) {
        enqueue(callback) { runCatching { searchUsersBlocking(query, page, limit) } }
    }

    fun searchUsersBlocking(query: String, page: Int = 0, limit: Int = 24): List<IwaraAuthor> {
        val url = "$apiRoot/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("type", "users")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.coerceAtMost(MAX_PAGE_LIMIT).toString()).build()
        val results = getJsonObject(url.toString(), optionalAuth = true).optJSONArray("results") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val wrapper = results.optJSONObject(i) ?: continue
                val user = wrapper.optJSONObject("user") ?: wrapper
                val author = parseAuthor(user)
                if (author.id.isNotBlank() || author.username.isNotBlank()) add(author)
            }
        }
    }

    /** 标签检索：Iwara 的标签是视频列表接口的 tags 过滤，多个标签用逗号连接表示同时命中。 */
    fun getVideosByTag(tag: String, page: Int = 0, limit: Int = 24, callback: (Result<List<VideoItem>>) -> Unit) {
        enqueue(callback) { runCatching { getVideosByTagBlocking(tag, page, limit) } }
    }

    fun getVideosByTagBlocking(tag: String, page: Int = 0, limit: Int = 24): List<VideoItem> {
        val url = "$apiRoot/videos".toHttpUrl().newBuilder()
            .addQueryParameter("tags", tag).addQueryParameter("rating", "all")
            .addQueryParameter("sort", "date")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.coerceAtMost(MAX_PAGE_LIMIT).toString()).build()
        return parseVideoPage(getJsonObject(url.toString(), optionalAuth = true))
    }

    fun getFavoriteVideos(callback: (Result<List<VideoItem>>) -> Unit) {
        enqueue(callback) { runCatching { getFavoriteVideosBlocking() } }
    }

    fun getFavoriteVideosBlocking(page: Int = 0, limit: Int = MAX_PAGE_LIMIT): List<VideoItem> =
        getFavoritesPageBlocking(page, limit).videos

    /** 官方点赞列表的一页。服务端同样把 limit 截断到 50，翻页要看它回报的总数。 */
    fun getFavoritesPageBlocking(page: Int = 0, limit: Int = MAX_PAGE_LIMIT): FavoritesPage {
        if (!isLoggedIn()) throw IOException("请先登录 Iwara")
        val url = "$apiRoot/favorites/videos".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.coerceAtMost(MAX_PAGE_LIMIT).toString()).build()
        val root = getJsonObject(url.toString(), requireAuth = true)
        val arr = root.optJSONArray("results") ?: JSONArray()
        val out = ArrayList<VideoItem>()
        for (i in 0 until arr.length()) {
            val wrapper = arr.optJSONObject(i) ?: continue
            val video = wrapper.optJSONObject("video") ?: wrapper
            parseVideo(video)?.let { out += it.copy(liked = true) }
        }
        return FavoritesPage(out, root.optInt("count", -1), hasMorePages(root, page, out.size))
    }

    // ---------------------------------------------------------------- 评论

    fun getComments(videoId: String, page: Int = 0, parentId: String? = null, callback: (Result<CommentPage>) -> Unit) {
        enqueue(callback) { runCatching { getCommentPageBlocking(videoId, page, parentId) } }
    }

    /**
     * 官方评论：`/video/{id}/comments`。带 `parent=<评论 id>` 时返回那条评论下的回复。
     * 登录了就带上 token——服务端据此回传本人身份，也能看到仅登录可见的内容。
     */
    fun getCommentPageBlocking(videoId: String, page: Int = 0, parentId: String? = null, limit: Int = 32): CommentPage {
        val builder = "$apiRoot/video/$videoId/comments".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.coerceAtMost(MAX_PAGE_LIMIT).toString())
        if (!parentId.isNullOrBlank()) builder.addQueryParameter("parent", parentId)
        val root = getJsonObject(builder.build().toString(), optionalAuth = true)
        val results = root.optJSONArray("results") ?: JSONArray()
        val list = ArrayList<IwaraComment>()
        for (i in 0 until results.length()) parseComment(results.optJSONObject(i))?.let { list += it }
        return CommentPage(list, root.optInt("count", -1), hasMorePages(root, page, list.size))
    }

    fun postComment(videoId: String, body: String, parentId: String? = null, callback: (Result<IwaraComment>) -> Unit) {
        enqueue(callback) { runCatching { postCommentBlocking(videoId, body, parentId) } }
    }

    /**
     * 发评论 / 回复：`POST /video/{id}/comments`，正文字段是 `body`，回复带 `parentId`。
     * 回复直接落到 Iwara 官网上，和在网页里回复完全一样。
     */
    fun postCommentBlocking(videoId: String, body: String, parentId: String? = null): IwaraComment {
        val text = body.trim()
        if (text.isBlank()) throw IOException("评论内容不能为空")
        if (!isLoggedIn()) throw IOException("请先登录 Iwara")
        ensureAccessTokenBlocking() ?: throw IOException("登录已失效，请重新登录")
        val json = JSONObject().put("body", text)
        if (!parentId.isNullOrBlank()) json.put("parentId", parentId)
        val request = baseRequest("$apiRoot/video/$videoId/comments", authenticated = true)
            .post(json.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractMessage(raw, when (response.code) {
                    401 -> "登录已失效，请重新登录"
                    403 -> "没有评论权限"
                    429 -> "评论太频繁，稍后再试"
                    else -> "评论发送失败（HTTP ${response.code}）"
                }))
            }
            val posted = runCatching { parseComment(JSONObject(raw)) }.getOrNull()
            // 服务端不一定原样回传评论体；回传不了就自己拼一条，界面上先显示出来。
            return posted ?: IwaraComment(
                id = "", body = text, author = IwaraAuthor("", "我", ""),
                createdAt = System.currentTimeMillis(), parentId = parentId.orEmpty()
            )
        }
    }

    private fun parseComment(o: JSONObject?): IwaraComment? {
        o ?: return null
        val id = o.optString("id")
        val body = o.optString("body")
        if (id.isBlank() && body.isBlank()) return null
        val user = o.optJSONObject("user") ?: JSONObject()
        val created = runCatching {
            o.optString("createdAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() }
        }.getOrNull() ?: 0L
        return IwaraComment(
            id = id,
            body = body,
            author = parseAuthor(user),
            createdAt = created,
            replyCount = o.optInt("numReplies", 0),
            parentId = o.optJSONObject("parent")?.optString("id").orEmpty().ifBlank { o.optString("parentId") }
        )
    }

    fun likeVideo(videoId: String, liked: Boolean, callback: (Result<Unit>) -> Unit) = relationWrite("$apiRoot/video/$videoId/like", liked, callback)
    fun followUser(userId: String, following: Boolean, callback: (Result<Unit>) -> Unit) = relationWrite("$apiRoot/user/$userId/followers", following, callback)
    fun setFriend(userId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) = relationWrite("$apiRoot/user/$userId/friends", enabled, callback)

    private fun relationWrite(url: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        enqueue(callback) { runCatching {
            if (!isLoggedIn()) throw IOException("请先登录 Iwara")
            ensureAccessTokenBlocking() ?: throw IOException("登录已失效，请重新登录")
            val builder = baseRequest(url, authenticated = true)
            val request = if (enabled) builder.post(ByteArray(0).toRequestBody(null)).build() else builder.delete().build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401) session.accessToken = null
                    throw IOException(extractMessage(raw, "操作失败（HTTP ${response.code}）"))
                }
            }
        } }
    }

    fun getFriendStatus(userId: String, callback: (Result<String>) -> Unit) {
        enqueue(callback) { runCatching {
            if (!isLoggedIn()) return@runCatching "none"
            getJsonObject("$apiRoot/user/$userId/friends/status", requireAuth = true).optString("status", "none")
        } }
    }

    fun getAuthorProfile(username: String, callback: (Result<IwaraAuthor>) -> Unit) {
        enqueue(callback) { runCatching {
            val root = getJsonObject("$apiRoot/profile/${UriEncoder.encodePath(username)}", optionalAuth = true)
            val user = root.optJSONObject("user") ?: throw IOException("作者资料不存在")
            parseAuthor(user, root.optString("body"))
        } }
    }

    fun getCurrentUser(callback: (Result<IwaraAuthor>) -> Unit) {
        enqueue(callback) { runCatching { getCurrentUserBlocking() } }
    }

    fun getCurrentUserBlocking(): IwaraAuthor {
        if (!isLoggedIn()) throw IOException("请先登录 Iwara")
        val root = getJsonObject("$apiRoot/user", requireAuth = true)
        val user = root.optJSONObject("user") ?: throw IOException("无法读取当前账号")
        return parseAuthor(user, root.optJSONObject("profile")?.optString("body").orEmpty())
    }

    fun getFollowingUsers(userId: String, page: Int = 0, callback: (Result<FollowingPage>) -> Unit) {
        enqueue(callback) { runCatching { getFollowingPageBlocking(userId, page) } }
    }

    fun getFollowingPageBlocking(userId: String, page: Int = 0): FollowingPage {
        // Iwara 会把列表 limit 截断到自己的上限，请求 100 也只会返回 50 条。
        // 之前用“返回条数 < 请求条数”判断结尾，于是关注超过 50 位时永远停在第一页。
        val url = "$apiRoot/user/$userId/following".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", MAX_PAGE_LIMIT.toString()).build()
        val root = getJsonObject(url.toString(), requireAuth = true)
        val arr = root.optJSONArray("results") ?: JSONArray()
        val users = buildList {
            for (i in 0 until arr.length()) {
                val wrapper = arr.optJSONObject(i) ?: continue
                val user = wrapper.optJSONObject("user") ?: wrapper
                add(parseAuthor(user).copy(following = true))
            }
        }
        return FollowingPage(users, root.optInt("count", -1), hasMorePages(root, page, users.size))
    }

    fun resolveSources(videoId: String, callback: (Result<List<VideoSource>>) -> Unit) {
        enqueue(callback) { runCatching { resolveSourcesBlocking(videoId) } }
    }

    fun resolveSourcesBlocking(videoId: String): List<VideoSource> {
        val detail = getJsonObject("$apiRoot/video/$videoId", optionalAuth = true)
        val fileUrl = detail.optString("fileUrl")
        if (fileUrl.isBlank()) {
            val reason = when {
                detail.optBoolean("private", false) -> "仅限好友/授权用户观看"
                detail.optString("status").contains("processing", true) -> "视频仍在处理中"
                else -> extractMessage(detail.toString(), "没有可播放的视频源")
            }
            throw IOException(reason)
        }
        return resolveFileUrlBlocking(fileUrl)
    }

    fun resolveStream(videoId: String, quality: String = "highest", callback: (Result<String>) -> Unit) {
        enqueue(callback) { runCatching {
            val sources = resolveSourcesBlocking(videoId)
            chooseSource(sources, quality)?.url ?: throw IOException("没有可播放清晰度")
        } }
    }

    fun chooseSource(sources: List<VideoSource>, quality: String): VideoSource? {
        if (sources.isEmpty()) return null
        if (quality == "highest") return sources.maxByOrNull { it.score }
        val exact = sources.firstOrNull { it.name.equals(quality, ignoreCase = true) }
        if (exact != null) return exact
        val target = Regex("(\\d+)").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (target != null) return sources.minByOrNull { kotlin.math.abs(it.score.coerceAtMost(9999) - target) }
        return sources.maxByOrNull { it.score }
    }

    private fun resolveFileUrlBlocking(fileUrl: String): List<VideoSource> {
        val parsed = fileUrl.toHttpUrlOrNull() ?: throw IOException("错误的 fileUrl")
        val expires = parsed.queryParameter("expires") ?: throw IOException("fileUrl 缺少 expires")
        val fileId = parsed.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: throw IOException("fileUrl 缺少文件 ID")
        val key = "${fileId}_${expires}_mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
        val sha = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val request = baseRequest(fileUrl).header("X-Version", sha).get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("视频源 HTTP ${response.code}")
            val arr = JSONArray(raw)
            val out = ArrayList<VideoSource>()
            for (i in 0 until arr.length()) {
                val source = arr.optJSONObject(i) ?: continue
                val src = source.optJSONObject("src") ?: continue
                val rawUrl = src.optString("download").takeIf { it.isNotBlank() } ?: src.optString("view").takeIf { it.isNotBlank() } ?: continue
                val name = source.optString("name", "Unknown")
                val resolution = Regex("(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val score = when { name.equals("source", true) -> 10000; resolution != null -> resolution; else -> 0 }
                val normalized = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                out += VideoSource(name, normalized, score)
            }
            if (out.isEmpty()) throw IOException("服务器未返回可播放视频源")
            return out.distinctBy { it.url }.sortedByDescending { it.score }
        }
    }

    private fun getJsonObject(url: String, optionalAuth: Boolean = false, requireAuth: Boolean = false): JSONObject {
        if (requireAuth && ensureAccessTokenBlocking().isNullOrBlank()) throw IOException("登录已失效，请重新登录")
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

    private fun parseVideoPage(root: JSONObject): List<VideoItem> = parseVideoListPage(root).videos

    private fun parseVideoListPage(root: JSONObject): VideoListPage {
        val results = root.optJSONArray("results") ?: JSONArray()
        val list = ArrayList<VideoItem>()
        for (i in 0 until results.length()) parseVideo(results.optJSONObject(i))?.let { list += it }
        return VideoListPage(list, root.optInt("count", -1))
    }

    private fun parseVideo(o: JSONObject?): VideoItem? {
        o ?: return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        val user = o.optJSONObject("user")
        val tagsJson = o.optJSONArray("tags")
        val tags = mutableListOf<String>()
        if (tagsJson != null) for (j in 0 until tagsJson.length()) {
            val tagObj = tagsJson.optJSONObject(j)
            val tag = tagObj?.optString("id")?.takeIf { it.isNotBlank() }
                ?: tagObj?.optString("name")?.takeIf { it.isNotBlank() }
            if (tag != null) tags += tag
        }
        val created = try {
            o.optString("createdAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() } ?: 0L
        } catch (_: Exception) { 0L }
        val thumbnailUrl = buildThumbnailUrl(o)
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
            liked = o.optBoolean("liked", false),
            authorId = user?.optString("id").orEmpty(),
            authorUsername = user?.optString("username").orEmpty(),
            isPrivate = o.optBoolean("private", false),
            status = o.optString("status"),
            thumbnailUrl = thumbnailUrl,
            description = o.optString("body").takeIf { it != "null" }.orEmpty()
        )
    }

    private fun buildThumbnailUrl(video: JSONObject): String {
        val custom = video.optJSONObject("customThumbnail")
        if (custom != null) {
            val customId = custom.optString("id")
            val customName = custom.optString("name")
            if (customId.isNotBlank() && customName.isNotBlank()) {
                return "$imageRoot/image/thumbnail/$customId/${UriEncoder.encodePathSegment(customName)}"
            }
        }
        val file = video.optJSONObject("file")
        val fileId = file?.optString("id").orEmpty()
        if (fileId.isBlank()) return ""
        val frame = video.optInt("thumbnail", 0).coerceAtLeast(0).toString().padStart(2, '0')
        return "$imageRoot/image/thumbnail/$fileId/thumbnail-$frame.jpg"
    }

    private fun parseAuthor(user: JSONObject, body: String = ""): IwaraAuthor = IwaraAuthor(
        id = user.optString("id"),
        name = user.optString("name").ifBlank { user.optString("username") },
        username = user.optString("username"),
        description = body,
        avatarUrl = buildAvatarUrl(user),
        following = user.optBoolean("following", false),
        friend = user.optBoolean("friend", false),
        friendStatus = if (user.optBoolean("friend", false)) "friends" else "none"
    )

    /**
     * 还有没有下一整页。用服务端真实生效的 limit 和总数判断，而不是我们请求的 limit：
     * 请求 100 也只会返回 50，按“返回条数 < 请求条数”判断会永远停在第一页。
     */
    private fun hasMorePages(root: JSONObject, page: Int, received: Int): Boolean {
        if (received <= 0) return false
        val served = root.optInt("limit", MAX_PAGE_LIMIT).coerceAtLeast(1)
        val total = root.optInt("count", -1)
        return if (total >= 0) (page + 1) * served < total else received >= served
    }

    private fun buildAvatarUrl(user: JSONObject): String {
        val avatar = user.optJSONObject("avatar") ?: return ""
        val avatarId = avatar.optString("id")
        val name = avatar.optString("name")
        if (avatarId.isBlank() || name.isBlank()) return ""
        val file = if (name.contains('.')) name else "$name.jpg"
        return "$imageRoot/image/avatar/$avatarId/${UriEncoder.encodePathSegment(file)}"
    }

    private fun extractMessage(raw: String, fallback: String): String {
        return try { JSONObject(raw).optString("message").takeIf { it.isNotBlank() } ?: fallback } catch (_: Exception) { fallback }
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            io.shutdownNow()
        }
        HttpClientCleanup.close(client)
    }

    companion object {
        const val DEFAULT_API_ROOT = "https://apiq.iwara.tv"

        /** Iwara 列表接口的服务端上限，请求更大的 limit 也只会返回这么多。 */
        const val MAX_PAGE_LIMIT = 50

        /**
         * 把服务端的错误码（形如 `errors.privateVideo`）翻成能看懂的话；认不出的原样返回。
         */
        fun explainError(error: Throwable): String {
            val raw = error.message?.trim().orEmpty()
            return when (raw) {
                "errors.privateVideo" -> "视频已被作者设为私密"
                "errors.notFound", "errors.videoNotFound" -> "视频已被删除或不存在"
                "errors.unauthorized", "errors.tokenExpired" -> "需要登录后才能查看"
                "errors.forbidden" -> "没有权限查看"
                "errors.tooManyRequests" -> "请求太频繁，稍后再试"
                "" -> "未知错误"
                else -> raw
            }
        }
    }
}

internal object UriEncoder {
    fun encodePath(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun encodePathSegment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%2F", "/")
}
