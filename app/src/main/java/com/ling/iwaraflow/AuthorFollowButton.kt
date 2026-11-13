package com.ling.iwaraflow

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

/** Small capsule follow button used inside the vertical video feed. */
class AuthorFollowButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatTextView(context, attrs) {

    private var busy = false
    private var lastVideoId: String? = null
    private var stateGeneration = 0

    init {
        gravity = android.view.Gravity.CENTER
        text = UiText.text(R.string.ui_eb137dee4c0a, "关注")
        textSize = 12f
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
        setPadding(dp(12), 0, dp(12), 0)
        refreshStyle(false)
        setOnClickListener { toggleFollow() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { resolveCurrentState() }
    }

    private fun currentItemAndAdapter(): Pair<VideoItem, VideoAdapter>? {
        var child: View = this
        var parent = child.parent
        while (parent is ViewGroup && parent !is RecyclerView) {
            child = parent as View
            parent = child.parent
        }
        val recycler = parent as? RecyclerView ?: return null
        val position = recycler.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return null
        val adapter = recycler.adapter as? VideoAdapter ?: return null
        val item = adapter.items.getOrNull(position) ?: return null
        return item to adapter
    }

    private fun resolveCurrentState() {
        val pair = currentItemAndAdapter() ?: return
        val item = pair.first
        lastVideoId = item.id
        if (item.authorFollowing) {
            showFollowing(true)
            return
        }
        if (!api().isLoggedIn() || item.authorUsername.isBlank()) {
            showFollowing(false)
            return
        }
        val expectedVideo = item.id
        val expectedGeneration = stateGeneration
        api().getAuthorProfile(item.authorUsername) { result ->
            result.onSuccess { author ->
                post {
                    val current = currentItemAndAdapter() ?: return@post
                    if (current.first.id != expectedVideo || expectedGeneration != stateGeneration) return@post
                    updateAuthorState(current.second, current.first, author.following)
                    showFollowing(author.following)
                }
            }
        }
    }

    private fun toggleFollow() {
        if (busy) return
        val (item, adapter) = currentItemAndAdapter() ?: return
        val api = api()
        if (!api.isLoggedIn()) {
            Toast.makeText(context, UiText.text(R.string.ui_79f8f482032b, "请先登录 Iwara 后再关注作者"), Toast.LENGTH_SHORT).show()
            return
        }
        if (item.authorId.isBlank() && item.authorUsername.isBlank()) {
            Toast.makeText(context, UiText.text(R.string.ui_5a8d4a2ddb54, "该视频没有可用的作者资料"), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        stateGeneration++
        isEnabled = false
        text = "…"

        fun commit(userId: String, currentlyFollowing: Boolean) {
            val desired = !currentlyFollowing
            api.followUser(userId, desired) { result ->
                post {
                    busy = false
                    isEnabled = true
                    result.onSuccess {
                        updateAuthorState(adapter, item, desired)
                        showFollowing(desired)
                        Toast.makeText(context, if (desired) UiText.text(R.string.ui_415ceaf14d1d, "已关注 %1\$s", item.author) else UiText.text(R.string.ui_d0aaadd68b8d, "已取消关注 %1\$s", item.author), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        showFollowing(item.authorFollowing)
                        Toast.makeText(context, it.message ?: UiText.text(R.string.ui_81c13adcedb3, "关注操作失败"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        if (item.authorUsername.isNotBlank()) {
            api.getAuthorProfile(item.authorUsername) { profileResult ->
                profileResult.onSuccess { profile ->
                    commit(profile.id.ifBlank { item.authorId }, profile.following)
                }.onFailure {
                    commit(item.authorId, item.authorFollowing)
                }
            }
        } else {
            commit(item.authorId, item.authorFollowing)
        }
    }

    private fun updateAuthorState(adapter: VideoAdapter, item: VideoItem, following: Boolean) {
        item.authorFollowing = following
        // 推荐引擎立刻知道：关注的作者按位置插入，不用等关注名单缓存过期。
        RecommendationEngine.notifyFollowChanged(item.authorId, following)
        val authorKey = item.authorId.ifBlank { item.authorUsername }
        adapter.items.forEach { candidate ->
            val candidateKey = candidate.authorId.ifBlank { candidate.authorUsername }
            if (authorKey.isNotBlank() && candidateKey == authorKey) candidate.authorFollowing = following
        }
    }

    /** 外部（例如从作者页返回）直接给出的关注状态。 */
    fun render(following: Boolean) {
        stateGeneration++
        if (busy) return
        showFollowing(following)
    }

    private fun showFollowing(following: Boolean) {
        text = if (following) UiText.text(R.string.ui_9fe1d0c9cca3, "已关注") else UiText.text(R.string.ui_eb137dee4c0a, "关注")
        refreshStyle(following)
    }

    private fun refreshStyle(following: Boolean) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setStroke(dp(1), if (following) 0xAAFFFFFF.toInt() else Color.WHITE)
            setColor(if (following) 0x33000000 else 0x19000000)
        }
        alpha = if (following) 0.86f else 1f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile private var sharedApi: IwaraApi? = null
        private fun apiFor(context: Context): IwaraApi {
            return sharedApi ?: synchronized(this) {
                sharedApi ?: IwaraApi(context.applicationContext).also { sharedApi = it }
            }
        }
    }

    private fun api(): IwaraApi = apiFor(context)
}
