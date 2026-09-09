package com.ling.iwaraflow

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class IwaraApi {
    private val apiRoot = "https://apiq.iwara.tv"
    private val siteRoot = "https://www.iwara.tv"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun baseRequest(url: String) = Request.Builder()
        .url(url)
        .header("Referer", "$siteRoot/")
        .header("Origin", siteRoot)
        .header("X-Site", "www.iwara.tv")
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")

    fun getVideos(sort: String, page: Int = 0, limit: Int = 20, callback: (Result<List<VideoItem>>) -> Unit) {
        val url = "$apiRoot/videos".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("rating", "all")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        client.newCall(baseRequest(url.toString()).get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return callback(Result.failure(IOException("HTTP ${it.code}")))
                    try {
                        val root = JSONObject(it.body!!.string())
                        val results = root.optJSONArray("results") ?: JSONArray()
                        val list = ArrayList<VideoItem>()
                        for (i in 0 until results.length()) {
                            val o = results.getJSONObject(i)
                            val user = o.optJSONObject("user")
                            val tagsJson = o.optJSONArray("tags")
                            val tags = mutableListOf<String>()
                            if (tagsJson != null) for (j in 0 until tagsJson.length()) {
                                val t = tagsJson.optJSONObject(j)
                                tags += (t?.optString("id") ?: "")
                            }
                            list += VideoItem(
                                id = o.optString("id"),
                                title = o.optString("title", "Untitled"),
                                author = user?.optString("name")?.takeIf { n -> n.isNotBlank() }
                                    ?: user?.optString("username") ?: "Iwara",
                                tags = tags.filter { t -> t.isNotBlank() },
                                likes = o.optInt("numLikes", 0)
                            )
                        }
                        callback(Result.success(list))
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }

    fun resolveStream(videoId: String, callback: (Result<String>) -> Unit) {
        val detailReq = baseRequest("$apiRoot/video/$videoId").get().build()
        client.newCall(detailReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return callback(Result.failure(IOException("video HTTP ${it.code}")))
                    try {
                        val detail = JSONObject(it.body!!.string())
                        val fileUrl = detail.optString("fileUrl")
                        if (fileUrl.isBlank()) return callback(Result.failure(IOException("No fileUrl")))
                        resolveFileUrl(fileUrl, callback)
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }

    private fun resolveFileUrl(fileUrl: String, callback: (Result<String>) -> Unit) {
        val parsed = fileUrl.toHttpUrlOrNull() ?: return callback(Result.failure(IOException("Bad fileUrl")))
        val expires = parsed.queryParameter("expires") ?: return callback(Result.failure(IOException("Missing expires")))
        val fileId = parsed.pathSegments().lastOrNull()?.takeIf { it.isNotBlank() }
            ?: return callback(Result.failure(IOException("Missing file id")))
        val key = "${fileId}_${expires}_mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
        val sha = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val req = baseRequest(fileUrl).header("X-Version", sha).get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return callback(Result.failure(IOException("source HTTP ${it.code}")))
                    try {
                        val arr = JSONArray(it.body!!.string())
                        var bestUrl: String? = null
                        var bestScore = -1
                        for (i in 0 until arr.length()) {
                            val s = arr.getJSONObject(i)
                            val srcObj = s.optJSONObject("src") ?: continue
                            val raw = srcObj.optString("download").takeIf { u -> u.isNotBlank() }
                                ?: srcObj.optString("view").takeIf { u -> u.isNotBlank() }
                                ?: continue
                            val name = s.optString("name")
                            val score = Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                                ?: if (name.equals("source", true)) 9999 else 0
                            if (score > bestScore) { bestScore = score; bestUrl = raw }
                        }
                        val u = bestUrl ?: return callback(Result.failure(IOException("No playable source")))
                        callback(Result.success(if (u.startsWith("//")) "https:$u" else u))
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }
}
