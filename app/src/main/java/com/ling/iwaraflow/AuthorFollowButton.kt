package com.ling.iwaraflow

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.Toast

/** Small capsule follow button used inside the vertical video feed. */
class AuthorFollowButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatTextView(context, attrs) {

    private var busy = false
    private var boundItem: VideoItem? = null
    private var boundAdapter: VideoAdapter? = null
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
        resolveCurrentState()
    }

    fun bind(item: VideoItem, adapter: VideoAdapter) {
        stateGeneration++
        boundItem = item
        boundAdapter = adapter
        busy = false
        isEnabled = true
        item.authorFollowing = adapter.api.knownFollowing(item.authorId) ?: item.authorFollowing
        showFollowing(item.authorFollowing)
        if (isAttachedToWindow) resolveCurrentState()
    }

    fun unbind() {
        stateGeneration++
        boundItem = null
        boundAdapter = null
    }

    private fun resolveCurrentState() {
        val item = boundItem ?: return
        val adapter = boundAdapter ?: return
        val api = adapter.api
        api.recentFollowing(item.authorId)?.let { item.authorFollowing = it; render(it); return }
        if (!api.isLoggedIn() || item.authorUsername.isBlank()) return
        val session = adapter.prefs.likeStates.snapshot()
        val expectedGeneration = stateGeneration
        api.getAuthorProfile(item.authorUsername) { result ->
            result.onSuccess { author ->
                post {
                    if (expectedGeneration != stateGeneration || !adapter.prefs.likeStates.isCurrent(session)) return@post
                    if (item.authorId.isBlank()) item.authorId = author.id
                    adapter.applyFollowState(AuthorActivity.FollowResult(author.id, item.authorUsername,
                        api.knownFollowing(author.id) ?: author.following))
                }
            }
        }
    }

    private fun toggleFollow() {
        if (busy) return
        val item = boundItem ?: return
        val adapter = boundAdapter ?: return
        val api = adapter.api
        if (!api.isLoggedIn()) {
            Toast.makeText(context, UiText.text(R.string.ui_79f8f482032b, "请先登录 Iwara 后再关注作者"), Toast.LENGTH_SHORT).show()
            return
        }
        if (item.authorId.isBlank() && item.authorUsername.isBlank()) {
            Toast.makeText(context, UiText.text(R.string.ui_5a8d4a2ddb54, "该视频没有可用的作者资料"), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        val request = ++stateGeneration
        val session = adapter.prefs.likeStates.snapshot()
        isEnabled = false
        text = "…"

        val desired = !(api.knownFollowing(item.authorId) ?: item.authorFollowing)
        fun finish(result: Result<Unit>) {
            post {
                if (stateGeneration != request) return@post
                busy = false
                isEnabled = true
                if (!adapter.prefs.likeStates.isCurrent(session)) return@post
                result.onSuccess {
                    showFollowing(api.knownFollowing(item.authorId) ?: desired)
                    Toast.makeText(context, if (desired) UiText.text(R.string.ui_415ceaf14d1d, "已关注 %1\$s", item.author) else UiText.text(R.string.ui_d0aaadd68b8d, "已取消关注 %1\$s", item.author), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    showFollowing(item.authorFollowing)
                    Toast.makeText(context, it.message ?: UiText.text(R.string.ui_81c13adcedb3, "关注操作失败"), Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun commit(id: String) {
            if (!adapter.prefs.likeStates.isCurrent(session)) { finish(Result.success(Unit)); return }
            item.authorId = id
            val accepted: (Result<Unit>) -> Unit = { result ->
                if (result.isSuccess && adapter.prefs.likeStates.isCurrent(session)) RecommendationEngine.notifyFollowChanged(id, desired)
                finish(result)
            }
            if (desired) api.ensureUserFollowed(id, item.authorUsername, accepted)
            else api.followUser(id, false, item.authorUsername, accepted)
        }
        if (item.authorId.isBlank()) {
            api.getAuthorProfile(item.authorUsername) { profileResult ->
                profileResult.onSuccess { profile ->
                    commit(profile.id)
                }.onFailure {
                    finish(Result.failure(it))
                }
            }
        } else {
            commit(item.authorId)
        }
    }

    /** 外部（例如从作者页返回）直接给出的关注状态。 */
    fun render(following: Boolean) {
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

}
