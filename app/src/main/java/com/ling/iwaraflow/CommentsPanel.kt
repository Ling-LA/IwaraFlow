package com.ling.iwaraflow

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 哔哩哔哩式的半屏评论面板。
 *
 * 面板贴在屏幕底部，高度由页面按当前视频的画面算出来（见 [panelHeight]），
 * 面板顶边正好对齐画面底边：横屏视频画面本来就在上半屏，面板直接接在下面；
 * 竖屏视频铺满整屏，打开面板时画面按比例缩到上面那块，不被面板挡住。
 * 打开面板不动播放器，视频照常播。
 *
 * 评论直接对接 Iwara 官网：列表是官方评论，回复也发到官网。
 * 写评论走 [CommentInputDialog]：键盘只顶起那个输入层，面板和视频都不动。
 */
class CommentsPanel(
    private val root: View,
    private val api: IwaraApi,
    /** 没登录就发评论时叫页面弹登录框。 */
    private val onNeedLogin: () -> Unit,
    /** 面板开合时通知页面：开着时给出画面要让出的顶部和底部留白（像素）。 */
    private val onLayoutChanged: (open: Boolean, top: Int, bottom: Int) -> Unit
) {
    private val title = root.findViewById<TextView>(R.id.commentsTitle)
    private val list = root.findViewById<RecyclerView>(R.id.commentsList)
    private val status = root.findViewById<TextView>(R.id.commentsStatus)
    private val replyBar = root.findViewById<View>(R.id.commentsReplyBar)
    private val replyTarget = root.findViewById<TextView>(R.id.commentsReplyTarget)
    private val input = root.findViewById<TextView>(R.id.commentsInput)

    private val adapter = CommentListAdapter(onReply = ::startReply, onLoadReplies = ::loadReplies)
    private var video: VideoItem? = null
    private var replyTo: IwaraComment? = null
    /** 还没发出去的文字；关掉输入层再打开还在。 */
    private var draft = ""
    private var nextPage = 0
    private var hasMore = false
    private var loadingPage = false
    private var sending = false
    /** 每次打开加一，旧请求回来时对不上就丢掉。 */
    private var generation = 0
    private var total = -1

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    init {
        list.layoutManager = LinearLayoutManager(root.context)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loadingPage) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) loadPage()
            }
        })
        root.findViewById<View>(R.id.commentsClose).setOnClickListener { close() }
        root.findViewById<View>(R.id.commentsReplyCancel).setOnClickListener { cancelReply() }
        input.setOnClickListener { openInput() }
        renderInput()
    }

    /**
     * 打开面板看 [item] 的评论。[panelHeight] 是面板高度，[videoTop] 是画面要从哪里开始
     * （通常是顶栏底边），都是像素。
     */
    fun open(item: VideoItem, panelHeight: Int, videoTop: Int) {
        val sameVideo = video?.id == item.id && isOpen
        video = item
        val params = root.layoutParams
        params.height = panelHeight
        root.layoutParams = params
        root.visibility = View.VISIBLE
        onLayoutChanged(true, videoTop, panelHeight)
        if (sameVideo) return
        generation++
        cancelReply()
        draft = ""
        renderInput()
        adapter.replaceAll(emptyList())
        nextPage = 0; hasMore = false; loadingPage = false; total = -1
        title.text = "评论"
        showStatus("加载中…")
        loadPage()
    }

    fun close() {
        if (!isOpen) return
        root.visibility = View.GONE
        onLayoutChanged(false, 0, 0)
    }

    /** 页面销毁时调，之后回来的请求全部作废。 */
    fun release() {
        generation++
        video = null
    }

    private fun loadPage() {
        val item = video ?: return
        if (loadingPage) return
        loadingPage = true
        val page = nextPage
        val gen = generation
        api.getComments(item.id, page) { result ->
            root.post {
                if (gen != generation) return@post
                loadingPage = false
                result.onSuccess { data ->
                    hasMore = data.hasMore
                    nextPage = page + 1
                    if (data.total >= 0) total = data.total
                    if (page == 0) adapter.replaceAll(data.comments) else adapter.append(data.comments)
                    refreshTitle()
                    if (adapter.itemCount == 0) showStatus("还没有评论，来说第一句") else hideStatus()
                }.onFailure {
                    if (adapter.itemCount == 0) {
                        showStatus(NetworkProxy.explain(it.message) ?: "评论加载失败：${it.message}")
                    } else {
                        Toast.makeText(root.context, "评论加载失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun loadReplies(parent: IwaraComment, done: (Result<List<IwaraComment>>) -> Unit) {
        val item = video ?: return done(Result.failure(IllegalStateException("面板已关闭")))
        val gen = generation
        api.getComments(item.id, 0, parentId = parent.id) { result ->
            root.post {
                if (gen != generation) return@post
                done(result.map { it.comments })
            }
        }
    }

    private fun refreshTitle() {
        val count = if (total >= 0) total else adapter.topLevelCount()
        title.text = if (count > 0) "评论 $count" else "评论"
    }

    private fun replyName(target: IwaraComment): String =
        target.author.name.ifBlank { target.author.username }.ifBlank { "匿名" }

    private fun startReply(target: IwaraComment) {
        replyTo = target
        replyBar.visibility = View.VISIBLE
        replyTarget.text = "回复 @${replyName(target)}：${target.body.take(40)}"
        renderInput()
        openInput()
    }

    private fun cancelReply() {
        replyTo = null
        replyBar.visibility = View.GONE
        renderInput()
    }

    /** 底部那条输入入口：有草稿显示草稿，没有就显示提示语。 */
    private fun renderInput() {
        val target = replyTo
        if (draft.isNotBlank()) {
            input.text = draft
            input.setTextColor(0xFF17324A.toInt())
        } else {
            input.text = if (target == null) "说点什么…" else "回复 @${replyName(target)}"
            input.setTextColor(0xFF8A9BAA.toInt())
        }
    }

    /** 面板所在的 Activity；布局可能被主题包装过，沿着 baseContext 往外找。 */
    private fun hostActivity(): Activity? {
        var context = root.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    private fun openInput() {
        val activity = hostActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (!api.isLoggedIn()) { onNeedLogin(); return }
        CommentInputDialog(
            activity,
            replyTo = replyTo?.let { "@${replyName(it)}" },
            draft = draft,
            onSend = ::submit,
            onDraft = { draft = it; renderInput() }
        ).show()
    }

    private fun submit(text: String) {
        val item = video ?: return
        if (sending) return
        if (!api.isLoggedIn()) { draft = text; renderInput(); onNeedLogin(); return }
        val target = replyTo
        // 回复「回复」时挂到同一条顶层评论下，官网也是这样组织的。
        val parentId = target?.let { it.parentId.ifBlank { it.id } }?.takeIf { it.isNotBlank() }
        sending = true
        draft = ""
        renderInput()
        val gen = generation
        api.postComment(item.id, text, parentId) { result ->
            root.post {
                if (gen != generation) return@post
                sending = false
                result.onSuccess { posted ->
                    if (parentId == null) {
                        adapter.prepend(posted)
                        list.scrollToPosition(0)
                    } else {
                        adapter.addReply(parentId, posted)
                    }
                    if (total >= 0 && parentId == null) total++
                    refreshTitle()
                    hideStatus()
                    cancelReply()
                    Toast.makeText(root.context, "已发送到 Iwara", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    // 没发出去的内容留作草稿，再点输入框还能接着改。
                    draft = text
                    renderInput()
                    val message = it.message ?: "评论发送失败"
                    Toast.makeText(root.context, message, Toast.LENGTH_LONG).show()
                    if (message.contains("登录")) onNeedLogin()
                }
            }
        }
    }

    private fun showStatus(text: String) { status.text = text; status.visibility = View.VISIBLE }
    private fun hideStatus() { status.visibility = View.GONE }

    companion object {
        /** 面板最矮占屏幕高度的多少：竖屏视频也得给评论留出能看的空间。 */
        const val MIN_FRACTION = 0.45f
        /** 面板最高占屏幕高度的多少：横屏视频上面至少留出画面本身。 */
        const val MAX_FRACTION = 0.70f

        /**
         * 画面按 fit 铺满屏幕宽度时的高度。[aspect] 是宽 / 高；不知道时按竖屏视频、
         * 铺满整屏来算。
         */
        fun renderedHeight(screenWidth: Int, screenHeight: Int, aspect: Float?): Int =
            if (aspect != null && aspect > 0f) (screenWidth / aspect).toInt() else screenHeight

        /**
         * 面板高度：顶栏以下、画面以下剩下的全给面板，但限制在屏幕高度的 45%～70% 之间。
         * 横屏视频（画面矮）面板就高，顶边正好接在画面底边；竖屏视频画面铺满，
         * 剩不下空间，就取最小值，画面再缩进上面那块。
         *
         * [gap] 让面板顶边比“刚好贴住画面”再往下收一点（像素）；画面随之整体下移，
         * 底边仍然贴着面板，只是离顶栏远一些。
         */
        fun panelHeight(screenWidth: Int, screenHeight: Int, topBarHeight: Int, aspect: Float?, gap: Int = 0): Int {
            val rendered = renderedHeight(screenWidth, screenHeight, aspect)
            val free = screenHeight - topBarHeight - rendered - gap.coerceAtLeast(0)
            val min = (screenHeight * MIN_FRACTION).toInt()
            val max = (screenHeight * MAX_FRACTION).toInt()
            return free.coerceIn(min, max)
        }

        /**
         * 画面区域的顶边。面板开着时画面被限制在 [top, screenHeight - panel] 里：
         * 横屏画面比这块矮，就把顶边往下挪到刚好贴住面板，画面底边 = 面板顶边；
         * 竖屏画面比这块高，顶边就是顶栏底边，画面按高度缩小、底边同样贴住面板。
         */
        fun videoTop(screenWidth: Int, screenHeight: Int, topBarHeight: Int, aspect: Float?, panel: Int): Int {
            val rendered = renderedHeight(screenWidth, screenHeight, aspect)
            return (screenHeight - panel - rendered).coerceAtLeast(topBarHeight)
        }
    }
}
