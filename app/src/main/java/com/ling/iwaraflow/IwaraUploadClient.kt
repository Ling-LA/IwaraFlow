package com.ling.iwaraflow

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class UploadTicket(val id: String, val key: String)
data class UploadProcessing(val state: String, val progress: Int, val file: JSONObject?)
data class VideoUploadDraft(
    val title: String, val body: String, val tags: List<String>, val rating: String,
    val privateVideo: Boolean = false, val unlisted: Boolean = false, val rulesAgreement: Boolean = false
) {
    fun validate() {
        require(title.isNotBlank()) { "请填写标题" }
        require(body.length <= 50_000) { "简介不能超过 50000 字" }
        require(tags.isNotEmpty()) { "请至少填写一个标签" }
        require(rating in listOf("general", "ecchi")) { "请选择内容分级" }
        require(rulesAgreement) { "请阅读并同意 Iwara 规则" }
    }
    fun payload(file: JSONObject): JSONObject {
        validate()
        require(file.optString("id").isNotBlank()) { "视频文件尚未处理完成" }
        return JSONObject().put("title", title.trim()).put("body", body).put("file", file)
            .put("tags", JSONArray(tags.distinct().map { JSONObject().put("id", it) }))
            .put("rating", rating).put("private", privateVideo).put("unlisted", unlisted)
            .put("thumbnail", 0).put("rulesAgreement", rulesAgreement)
    }
    companion object {
        fun parseTags(text: String): List<String> = text.split(Regex("[,，;；\\n]+"))
            .map { it.trim().removePrefix("#").replace(Regex("\\s+"), "_").lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotBlank() }.distinct()
    }
}
class UploadApiException(message: String, val status: Int) : IOException(message)
class PublicationUncertainException(cause: IOException) :
    IOException("发布请求未收到确认。请先查看我的作品，确认未发布后再重试，避免重复投稿。", cause)

/**
 * Protocol verified against Iwara's public web client 3.3.7.
 * See docs/video-upload-protocol.md. Mutating requests are never automatically retried.
 */
class IwaraUploadClient(
    private val accessToken: () -> String,
    private val apiRoot: String = "https://api.iwara.tv",
    private val filesRoot: String = "https://files.iwara.tv",
    private val authExpired: () -> Unit = {}
) : Closeable {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    @Volatile private var closed = false
    private fun request(url: String, authenticated: Boolean) = Request.Builder().url(url)
        .header("Accept", "application/json").header("Origin", "https://www.iwara.tv")
        .header("Referer", "https://www.iwara.tv/").header("X-Site", "www.iwara.tv")
        .apply {
            check(!closed) { "上传已取消" }
            if (authenticated) header("Authorization", "Bearer ${accessToken()}")
        }
    private fun json(request: Request, expectedCode: Int? = null): JSONObject {
        if (closed || Thread.currentThread().isInterrupted) throw IOException("上传已取消")
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful || (expectedCode != null && response.code != expectedCode)) {
                if (response.code == 401) authExpired()
                val details = data?.opt("errors")?.toString()?.takeIf { it != "null" }.orEmpty()
                val reason = data?.optString("reason").orEmpty()
                val description = when (reason) {
                    "fileTooLarge" -> "视频文件超过当前账号的大小限制"
                    "invalidMime" -> "官网不支持该视频格式，请使用 MP4"
                    "fileTooShort" -> "视频时长不足官网要求"
                    "fileTooSmall" -> "视频分辨率低于官网要求"
                    else -> data?.optString("message")?.takeIf { it.isNotBlank() }.orEmpty()
                }
                throw UploadApiException("官网返回 HTTP ${response.code}" +
                    if (description.isNotBlank() || details.isNotBlank()) "：$description $details" else "", response.code)
            }
            data ?: throw IOException("官网响应格式异常")
        }
    }

    fun upload(filename: String, size: Long, input: () -> InputStream, progress: (Int) -> Unit): UploadTicket {
        require(size != 0L) { "视频文件为空" }
        val body = object : RequestBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                input().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var previous = -1
                    while (true) {
                        if (closed || Thread.currentThread().isInterrupted) throw IOException("上传已取消")
                        val count = stream.read(buffer)
                        if (count == -1) break
                        sink.write(buffer, 0, count)
                        sent += count
                        val percent = if (size > 0) (sent * 100 / size).toInt().coerceIn(0, 100) else -1
                        if (percent != previous) { previous = percent; progress(percent) }
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, body).build()
        val result = json(request("$filesRoot/upload/video", true).post(multipart).build(), 201)
        val id = result.optString("id").takeUnless { it.isBlank() || it == "null" } ?: throw IOException("官网未返回上传任务")
        val key = result.optString("key").takeUnless { it.isBlank() || it == "null" } ?: throw IOException("官网未返回处理凭据")
        return UploadTicket(id, key)
    }

    fun processing(ticket: UploadTicket): UploadProcessing {
        val url = filesRoot.toHttpUrl().newBuilder().addPathSegment("upload").addPathSegment("video")
            .addPathSegment(ticket.id).addPathSegment(ticket.key).build()
        val result = json(request(url.toString(), false).get().build())
        val state = result.optString("state")
        if (state == "failed") throw IOException("官网处理视频失败，请检查编码、时长和分辨率后重新选择文件")
        val file = result.optJSONObject("data")
        if (state == "completed" && file?.optString("id").isNullOrBlank()) throw IOException("官网未返回已处理的视频文件")
        return UploadProcessing(state, result.optInt("progress", 0).coerceIn(0, 100), file)
    }

    fun publish(draft: VideoUploadDraft, file: JSONObject): String {
        val request = request("$apiRoot/videos", true)
            .post(draft.payload(file).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val result = try { json(request) }
        catch (e: UploadApiException) { if (e.status >= 500 || e.status == 408) throw PublicationUncertainException(e) else throw e }
        catch (e: IOException) { throw PublicationUncertainException(e) }
        return result.optString("id").takeUnless { it.isBlank() || it == "null" }
            ?: throw PublicationUncertainException(IOException("官网未返回视频编号"))
    }
    override fun close() { closed = true; HttpClientCleanup.close(client) }
}
