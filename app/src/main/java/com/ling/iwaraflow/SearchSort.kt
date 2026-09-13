package com.ling.iwaraflow

/**
 * 搜索结果的排序规则。
 *
 * 视频名和标签两栏默认「上传时间 · 倒序」，也就是先看到最新的作品；另外可以切到
 * 播放量和点赞数。排序键会一起带给服务端（视频列表接口的 `sort`），所以换排序键时
 * 整批结果都按新规则重新翻页，而不是只把已加载的几十条换个次序。
 *
 * 顺序 / 倒序只在本地做：Iwara 没有升序参数，翻页拿回来的永远是倒序。倒序时本地
 * 排序和服务端一致，顺序时就是把已加载的结果反过来看。
 *
 * 作者名一栏没有排序入口，固定按关注数多的优先。
 */
object SearchSort {
    enum class Key(val label: String, val api: String) {
        DATE("上传时间", "date"),
        VIEWS("播放量", "views"),
        LIKES("点赞数", "likes")
    }

    val DEFAULT_KEY = Key.DATE
    const val DEFAULT_DESCENDING = true

    private fun valueOf(item: VideoItem, key: Key): Long = when (key) {
        Key.DATE -> item.createdAt
        Key.VIEWS -> item.views.toLong()
        Key.LIKES -> item.likes.toLong()
    }

    fun comparator(key: Key, descending: Boolean): Comparator<VideoItem> {
        val byValue = Comparator<VideoItem> { a, b -> valueOf(a, key).compareTo(valueOf(b, key)) }
        // 数值相同的按 id 定序，翻页时这些视频不会来回换位置。
        return (if (descending) byValue.reversed() else byValue).thenBy { it.id }
    }

    /** 没有上传时间的结果（接口没给 createdAt）不参与按时间排序，一律排到最后。 */
    fun sorted(items: List<VideoItem>, key: Key, descending: Boolean): List<VideoItem> {
        val (dated, undated) =
            if (key == Key.DATE) items.partition { it.createdAt > 0L } else Pair(items, emptyList<VideoItem>())
        return dated.sortedWith(comparator(key, descending)) + undated
    }

    /** 关注数多的作者在前；服务端没回报关注数的排在后面，按名字定序。 */
    fun sortedAuthors(authors: List<IwaraAuthor>): List<IwaraAuthor> = authors.sortedWith(
        compareByDescending<IwaraAuthor> { it.followers }.thenBy { it.name.ifBlank { it.username } }
    )

    /** 状态栏里那句「上传时间 ↓」。 */
    fun label(key: Key, descending: Boolean): String = "${key.label} ${if (descending) "↓" else "↑"}"
}
