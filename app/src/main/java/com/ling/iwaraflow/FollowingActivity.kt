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
import java.util.concurrent.Executors

class FollowingActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var listView: RecyclerView
    private lateinit var status: TextView
    private lateinit var adapter: FollowingAuthorAdapter
    private val items = mutableListOf<IwaraAuthor>()
    private val seenIds = mutableSetOf<String>()
    private val profilePool = Executors.newFixedThreadPool(4)
    private var closed = false
    private var followingTotal = -1
    private val followers: Boolean get() = intent.getBooleanExtra("followers", false)

    private val authorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Keep this following page exactly where it was after returning from an author.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_following)

        api = IwaraApi(this)
        status = findViewById(R.id.followingStatus)
        findViewById<TextView>(R.id.followingTitle).text = if (followers) "我的粉丝" else "我的关注"
        listView = findViewById(R.id.followingList)
        adapter = FollowingAuthorAdapter(items, ::openAuthor, emptyDescription = if (followers) "关注了你" else "已关注")
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
        status.text = if (followers) "正在读取 Iwara 粉丝列表…" else "正在读取 Iwara 关注列表…"
        api.getCurrentUser { result ->
            result.onSuccess { me -> loadFollowingPage(me.id, 0) }
                .onFailure { e -> runOnUiThread {
                    if (closed) return@runOnUiThread
                    status.text = "${if (followers) "粉丝" else "关注"}列表加载失败：${IwaraApi.explainError(e)}"
                } }
        }
    }

    private fun loadFollowingPage(userId: String, page: Int) {
        val request: (String, Int, (Result<FollowingPage>) -> Unit) -> Unit = if (followers) api::getFollowers else api::getFollowingUsers
        request(userId, page) { result ->
            result.onSuccess { followingPage ->
                val more = followingPage.hasMore && page + 1 < MAX_PAGES
                runOnUiThread {
                    if (closed) return@runOnUiThread
                    // 同一位作者可能同时出现在相邻两页（关注顺序会随分页请求变化）。
                    val fresh = followingPage.users.filter { author -> seenIds.add(author.id.ifBlank { author.username }) }
                    val start = items.size
                    items += fresh
                    adapter.notifyItemRangeInserted(start, fresh.size)
                    followingTotal = followingPage.total
                    status.text = statusText(followingPage.total, more)
                    fresh.forEachIndexed { offset, author -> enrichProfile(start + offset, author) }
                }
                if (more) loadFollowingPage(userId, page + 1)
            }.onFailure { e -> runOnUiThread {
                if (closed) return@runOnUiThread
                status.text = if (items.isEmpty()) "${if (followers) "粉丝" else "关注"}列表加载失败：${IwaraApi.explainError(e)}"
                else "${statusText(followingTotal, false)} · 后续加载失败：${IwaraApi.explainError(e)}"
            } }
        }
    }

    private fun statusText(total: Int, loadingMore: Boolean): String {
        val progress = if (total > items.size) "${items.size} / $total" else "${items.size}"
        return (if (followers) "粉丝 $progress 位" else "已关注 $progress 位作者") + if (loadingMore) " · 正在加载更多…" else ""
    }

    private fun enrichProfile(index: Int, author: IwaraAuthor) {
        if (author.username.isBlank()) return
        profilePool.execute {
            val updated = runCatching {
                val profile = api.getAuthorProfileBlocking(author.username)
                author.copy(
                    name = profile.name.ifBlank { author.name },
                    description = profile.description.ifBlank { author.description },
                    avatarUrl = profile.avatarUrl.ifBlank { author.avatarUrl },
                    following = author.following || profile.following,
                    followers = profile.followers.takeIf { it >= 0 } ?: author.followers
                )
            }.getOrDefault(author)

            runOnUiThread {
                if (closed || index !in items.indices || items[index].id != author.id) return@runOnUiThread
                items[index] = updated
                adapter.notifyItemChanged(index)
            }
        }
    }

    private fun openAuthor(author: IwaraAuthor) {
        if (author.username.isBlank() || author.id.isBlank()) return
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
        super.onDestroy()
    }

    private companion object {
        /** 安全上限：50 条一页，最多读到 2000 位关注，避免异常分页把请求打成死循环。 */
        const val MAX_PAGES = 40
    }
}
