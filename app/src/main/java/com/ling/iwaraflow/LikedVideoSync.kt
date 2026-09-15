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

    /**
     * 登录之后先把**第一页**（最多 50 个）官方点赞种进画像，再回调。
     *
     * 完整同步要翻几十页，而登录成功和「生成第一批推荐」几乎是同时发生的，
     * 于是一个用了多年 Iwara 的账号刚登录时第一屏仍然是冷启动，第二次刷新才像样。
     * 一页就足够让画像有方向；剩下的交给 [syncIfStale] 在后台慢慢补。
     *
     * 回调在后台线程，成功与否都会回调一次（失败就是没种子，照常出推荐）。
     */
    fun seedFirstPage(onDone: () -> Unit) {
        if (closed || !api.isLoggedIn()) { onDone(); return }
        runCatching {
            io.execute {
                runCatching {
                    val account = resolveAccount()
                    val first = api.getFavoritesPageBlocking(0)
                    history.markSeen(first.videos.map { it.id }, account = account)
                    history.seedCloudLikes(first.videos)
                }
                if (!closed) onDone()
            }
        }.onFailure { onDone() }
    }

    /**
     * 认一下现在登录的是谁，把账号 id 写进偏好和 [HistoryStore.accountId]。
     *
     * 官方点赞和关注名单是**账号级**的：换个账号登录，上一个账号的点赞不该
     * 继续当口味用，它点过的视频对新账号也不该算“已看”。账号变了就把关注缓存
     * 也清掉，不然 6 小时内新账号看到的还是上一个账号关注的人。
     */
    private fun resolveAccount(): String {
        val id = runCatching { api.getCurrentUserBlocking().id }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return prefs.accountId
        if (id != prefs.accountId) {
            prefs.accountId = id
            RecommendationEngine.notifyAccountChanged(id)
        }
        history.accountId = id
        return id
    }

    /** 返回这次标记的点赞条数。中途失败不写时间戳，下次启动会重来。 */
    fun syncBlocking(): Int {
        var page = 0
        var marked = 0
        val account = resolveAccount()
        while (!closed && page < MAX_PAGES) {
            val result = runCatching { api.getFavoritesPageBlocking(page) }.getOrNull() ?: return marked
            history.markSeen(result.videos.map { it.id }, account = account)
            // 最近的几百个点赞同时作为口味画像的种子（作者、标签），新装的用户马上有偏好可推。
            if (page < SEED_PAGES) history.seedCloudLikes(result.videos)
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
        /** 前几页（最近 500 个点赞）种进画像；再往前的只标记已看。 */
        const val SEED_PAGES = 10
        const val SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
