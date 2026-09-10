package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FollowingActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var listView: RecyclerView
    private lateinit var status: TextView
    private lateinit var adapter: FollowingAuthorAdapter
    private val items = mutableListOf<IwaraAuthor>()
    private val seenIds = mutableSetOf<String>()
    private val profilePool = Executors.newFixedThreadPool(4)
    private val profileClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private var closed = false

    private val authorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Keep this following page exactly where it was after returning from an author.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_following)

        api = IwaraApi(this)
        status = findViewById(R.id.followingStatus)
        listView = findViewById(R.id.followingList)
        adapter = FollowingAuthorAdapter(items, ::openAuthor)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        findViewById<View>(R.id.followingBack).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        if (!api.isLoggedIn()) {
            Toast.makeText(this, "请先在主页登录 Iwara", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadFollowing()
    }

    private fun loadFollowing() {
        status.text = "正在读取 Iwara 关注列表…"
        api.getCurrentUser { result ->
            result.onSuccess { me -> loadFollowingPage(me.id, 0) }
                .onFailure { e -> runOnUiThread {
                    if (closed) return@runOnUiThread
                    status.text = "关注列表加载失败：${e.message}"
                } }
        }
    }

    private fun loadFollowingPage(userId: String, page: Int) {
        api.getFollowingUsers(userId, page) { result ->
            result.onSuccess { followingPage ->
                val more = followingPage.hasMore && page + 1 < MAX_PAGES
                runOnUiThread {
                    if (closed) return@runOnUiThread
                    // 同一位作者可能同时出现在相邻两页（关注顺序会随分页请求变化）。
                    val fresh = followingPage.users.filter { author -> seenIds.add(author.id) }
                    val start = items.size
                    items += fresh
                    adapter.notifyItemRangeInserted(start, fresh.size)
                    status.text = statusText(followingPage.total, more)
                    fresh.forEachIndexed { offset, author -> enrichProfile(start + offset, author) }
                }
                if (more) loadFollowingPage(userId, page + 1)
            }.onFailure { e -> runOnUiThread {
                if (closed) return@runOnUiThread
                status.text = if (items.isEmpty()) "关注列表加载失败：${e.message}" else "已关注 ${items.size} 位作者 · 后续加载失败"
            } }
        }
    }

    private fun statusText(total: Int, loadingMore: Boolean): String {
        val progress = if (total > items.size) "${items.size} / $total" else "${items.size}"
        return "已关注 $progress 位作者" + if (loadingMore) " · 正在加载更多…" else ""
    }

    private fun enrichProfile(index: Int, author: IwaraAuthor) {
        if (author.username.isBlank()) return
        profilePool.execute {
            val updated = runCatching {
                val encoded = URLEncoder.encode(author.username, "UTF-8").replace("+", "%20")
                val request = Request.Builder()
                    .url("https://apiq.iwara.tv/profile/$encoded")
                    .header("Referer", "https://www.iwara.tv/")
                    .header("Origin", "https://www.iwara.tv")
                    .header("X-Site", "www.iwara.tv")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
                    .build()
                profileClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use author
                    val root = JSONObject(response.body?.string().orEmpty())
                    val user = root.optJSONObject("user") ?: return@use author
                    val avatar = user.optJSONObject("avatar")
                    val avatarId = avatar?.optString("id").orEmpty()
                    val avatarName = avatar?.optString("name").orEmpty()
                    val avatarFile = when {
                        avatarName.isBlank() -> ""
                        avatarName.endsWith(".jpg", true) -> avatarName
                        else -> "$avatarName.jpg"
                    }
                    val avatarUrl = if (avatarId.isNotBlank() && avatarFile.isNotBlank()) {
                        "https://i.iwara.tv/image/avatar/$avatarId/${URLEncoder.encode(avatarFile, "UTF-8").replace("+", "%20")}"
                    } else ""
                    author.copy(
                        name = user.optString("name").ifBlank { author.name },
                        description = root.optString("body").ifBlank { author.description },
                        avatarUrl = avatarUrl,
                        following = true
                    )
                }
            }.getOrDefault(author)

            runOnUiThread {
                if (closed || index !in items.indices || items[index].id != author.id) return@runOnUiThread
                items[index] = updated
                adapter.notifyItemChanged(index)
            }
        }
    }

    private fun openAuthor(author: IwaraAuthor) {
        authorLauncher.launch(Intent(this, AuthorActivity::class.java).apply {
            putExtra(AuthorActivity.EXTRA_ID, author.id)
            putExtra(AuthorActivity.EXTRA_NAME, author.name)
            putExtra(AuthorActivity.EXTRA_USERNAME, author.username)
        })
    }

    override fun onDestroy() {
        closed = true
        api.close()
        profilePool.shutdownNow()
        HttpClientCleanup.close(profileClient)
        super.onDestroy()
    }

    private companion object {
        /** 安全上限：50 条一页，最多读到 2000 位关注，避免异常分页把请求打成死循环。 */
        const val MAX_PAGES = 40
    }
}
