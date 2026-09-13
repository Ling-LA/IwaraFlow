package com.ling.iwaraflow

import android.view.View

/**
 * 把评论面板接到一个竖滑播放页上：算面板高度和画面留白、盖一层透明遮罩
 * （点画面收起面板而不是暂停）、翻页时关面板。主页、作者页、搜索页三处播放流共用。
 */
class CommentsHost(
    /** 视频所在的区域，用它的尺寸算面板几何；还没布局时退回屏幕尺寸。 */
    private val pager: View,
    panelRoot: View,
    private val scrim: View,
    private val adapter: VideoAdapter,
    api: IwaraApi,
    /** 画面上方被顶栏 / 返回按钮占掉的高度（像素）。 */
    private val topBarHeight: () -> Int,
    /** 面板顶边比“刚好贴住画面”再往下收的距离（像素）。 */
    private val gapPx: Int,
    onNeedLogin: () -> Unit,
    onOpenAuthor: ((IwaraAuthor) -> Unit)? = null,
    /** 面板开合时通知页面（比如切换返回键的处理）。 */
    private val onOpenChanged: ((Boolean) -> Unit)? = null,
    /** 评论发送成功：交给页面记画像。 */
    onCommentPosted: ((VideoItem) -> Unit)? = null
) {
    val panel = CommentsPanel(
        root = panelRoot,
        api = api,
        onNeedLogin = onNeedLogin,
        onLayoutChanged = { open, top, bottom ->
            scrim.visibility = if (open) View.VISIBLE else View.GONE
            adapter.setVideoInsets(if (open) top else 0, if (open) bottom else 0)
            onOpenChanged?.invoke(open)
        },
        onOpenAuthor = onOpenAuthor,
        onCommentPosted = onCommentPosted
    )

    init {
        scrim.setOnClickListener { panel.close() }
    }

    val isOpen: Boolean get() = panel.isOpen

    /**
     * 打开 [item] 的评论。面板高度和画面顶边按当前视频的宽高比算：横屏视频面板顶边贴住
     * 画面底边，竖屏视频画面缩到顶栏和面板之间。播放器不动，视频照常播。
     */
    fun open(item: VideoItem, tab: CommentsPanel.Tab = CommentsPanel.Tab.COMMENTS) {
        val metrics = pager.resources.displayMetrics
        val width = pager.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = pager.height.takeIf { it > 0 } ?: metrics.heightPixels
        val topBar = topBarHeight()
        val aspect = adapter.activeVideoAspect()
        val panelHeight = CommentsPanel.panelHeight(width, height, topBar, aspect, gap = gapPx)
        val top = CommentsPanel.videoTop(width, height, topBar, aspect, panelHeight)
        panel.open(item, panelHeight, top, tab)
    }

    fun close() = panel.close()

    fun release() = panel.release()
}
