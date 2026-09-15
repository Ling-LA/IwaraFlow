package com.ling.iwaraflow

/**
 * 顶部四个流各自的「现场」：列表内容、看到第几条、翻到第几页、还能不能继续翻。
 *
 * 在推荐 / 最新 / 流行 / 人气之间来回切时，回到原来那个流应该还在原来的位置，
 * 而不是从头重新拉一遍——刷了二十条之后切去看一眼热门，回来又从第一条开始，
 * 是很容易让人恼火的那种“小事”。
 *
 * 从 [MainActivityV3] 里独立出来的一块：只是一份内存里的现场记录，
 * 不碰界面也不碰网络。
 */
class FeedSessionStore {
    /** 一个流的现场。 */
    data class Session(
        val items: List<VideoItem>,
        val currentIndex: Int,
        val currentPage: Int,
        val pagingEnabled: Boolean
    )

    private val sessions = HashMap<String, Session>()

    fun save(mode: String, session: Session) {
        if (mode !in HOME_MODES) return
        sessions[mode] = session
    }

    /** 这个流有没有存着可用的现场（空列表不算）。 */
    fun restorable(mode: String): Session? = sessions[mode]?.takeIf { it.items.isNotEmpty() }

    /** 丢掉某个流的现场：再点一次“推荐”就是“给我一批新的”，不该回到旧位置。 */
    fun forget(mode: String) { sessions.remove(mode) }

    fun clear() = sessions.clear()

    companion object {
        /** 顶栏那四个流；只有它们保留现场，搜索、作者页、收藏这些不算。 */
        val HOME_MODES = setOf("recommend", "date", "trending", "popularity")
    }
}
