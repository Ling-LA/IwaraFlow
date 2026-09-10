package com.ling.iwaraflow

import java.util.concurrent.Executors

/**
 * 把 Iwara 官方点赞完整同步进本地“已看”表。
 *
 * 推荐刷新时只顺手读了点赞列表的第一页，而服务端一页最多 50 条，点赞多的账号
 * 剩下的老点赞永远不会被标记，于是一直在推荐里回来。这里在后台把所有页翻完，
 * 不占冷启动首屏的网络：本次刷新照旧用第一页，同步完的结果下次刷新生效。
 */
class LikedVideoSync(
    private val api: IwaraApi,
    private val history: HistoryStore,
    private val prefs: AppPrefs
) {
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var running = false

    fun syncIfStale(force: Boolean = false) {
        if (closed || running || !api.isLoggedIn()) return
        val age = System.currentTimeMillis() - prefs.likedSyncAt
        if (!force && prefs.likedSyncAt > 0L && age < SYNC_INTERVAL_MS) return
        running = true
        runCatching { io.execute { try { syncBlocking() } finally { running = false } } }
            .onFailure { running = false }
    }

    /** 菜单里手动触发：完成后回调这次标记的条数（未登录回调 -1），回调在后台线程。 */
    fun syncNow(onDone: (Int) -> Unit) {
        if (closed) return
        if (!api.isLoggedIn()) { onDone(-1); return }
        running = true
        runCatching {
            io.execute {
                val marked = try { syncBlocking() } finally { running = false }
                if (!closed) onDone(marked)
            }
        }.onFailure { running = false }
    }

    /** 返回这次标记的点赞条数。中途失败不写时间戳，下次启动会重来。 */
    fun syncBlocking(): Int {
        var page = 0
        var marked = 0
        while (!closed && page < MAX_PAGES) {
            val result = runCatching { api.getFavoritesPageBlocking(page) }.getOrNull() ?: return marked
            history.markSeen(result.videos.map { it.id })
            marked += result.videos.size
            if (!result.hasMore) break
            page += 1
        }
        if (!closed) prefs.likedSyncAt = System.currentTimeMillis()
        return marked
    }

    fun close() {
        closed = true
        io.shutdownNow()
    }

    companion object {
        /** 50 条一页，最多读到 5000 个点赞。 */
        const val MAX_PAGES = 100
        const val SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
