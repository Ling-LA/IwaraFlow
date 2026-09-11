package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 候选来源。Iwara 每月新增六千多个视频（约 167 页），十三年的存量有几千页，所以
 * “推荐不出视频”从来不是片源问题——是取片源的方式问题：每次都从第 0 页拿，
 * 拿到的永远是同一批最新视频。
 *
 * 这里一律用“结果里有没有这条视频”来断言：每个来源返回带自己名字和页码的视频，
 * 没被取用的来源自然不会出现在结果里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationSourcesTest {
    private fun video(id: String) = VideoItem(id, id, "作者", emptyList(), 1, authorId = "author-$id")

    private fun load(engine: RecommendationEngine): List<VideoItem> =
        loadOnce(engine).also { engine.close() }

    /** 同一个引擎要连着刷几次时用这个：close 之后线程池就没了。 */
    private fun loadOnce(engine: RecommendationEngine): List<VideoItem> {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        engine.load(true) { outcome -> result.set(outcome); delivered.countDown() }
        assertTrue("推荐没有返回", delivered.await(15, TimeUnit.SECONDS))
        return result.get().getOrThrow()
    }

    private fun history(seen: (String) -> Boolean = { false }): HistoryStore {
        val history = mock(HistoryStore::class.java)
        `when`(history.preferenceProfile()).thenReturn(PreferenceProfile(emptyMap(), emptyMap()))
        `when`(history.isSeen(anyString())).thenAnswer { seen(it.getArgument(0)) }
        return history
    }

    /** 每个榜单返回 `<sort>-p<page>-<n>`，订阅流返回 `subscribed-p<page>-<n>`。 */
    private fun api(loggedIn: Boolean, perSource: Int = 6): IwaraApi {
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(loggedIn)
        `when`(api.getCurrentUserBlocking()).thenThrow(IllegalStateException("no session in test"))
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            val page = invocation.getArgument<Int>(1)
            // total = -1：服务端没回报总数，抽页范围只受策略上限约束。
            VideoListPage((0 until perSource).map { video("$sort-p$page-$it") }, -1)
        }
        `when`(api.getSubscribedVideoPageBlocking(anyInt(), anyInt())).thenAnswer { invocation ->
            val page = invocation.getArgument<Int>(0)
            VideoListPage((0 until perSource).map { video("subscribed-p$page-$it") }, -1)
        }
        return api
    }

    private fun pageOf(id: String): Int = id.substringAfter("-p").substringBefore("-").toInt()

    @Test fun theSubscriptionFeedIsOneOfTheSources() {
        val feed = load(RecommendationEngine(api(loggedIn = true), history()))
        assertTrue("关注作者的更新必须进入候选", feed.any { it.id.startsWith("subscribed-") })
    }

    @Test fun newUploadsAreOneOfTheSources() {
        val feed = load(RecommendationEngine(api(loggedIn = false), history()))
        assertTrue("最新投稿必须进入候选", feed.any { it.id.startsWith("date-p0-") })
    }

    @Test fun theAllTimeTopChartsAreNoLongerRead() {
        val feed = load(RecommendationEngine(api(loggedIn = false), history()))
        // 全站历史总榜每次都是同一批老视频，拉它们只是白费一次请求。
        assertTrue("不该再取 likes 总榜", feed.none { it.id.startsWith("likes-") })
        assertTrue("不该再取 views 总榜", feed.none { it.id.startsWith("views-") })
        assertTrue("热门榜单仍然保留", feed.any { it.id.startsWith("trending-") })
    }

    @Test fun everyRefreshAlsoReachesIntoTheArchiveNotJustTheFirstPage() {
        // 这是整个问题的根子：以前四个来源全取第 0 页，刷一百次也是那一两百条。
        val feed = load(RecommendationEngine(api(loggedIn = true), history()))
        assertTrue("必须有来自存档深处的候选，而不是清一色第 0 页",
            feed.any { pageOf(it.id) > 0 })
    }

    @Test fun twoRefreshesDoNotLandOnTheSamePages() {
        val first = load(RecommendationEngine(api(loggedIn = false), history(), random = Random(1)))
        val second = load(RecommendationEngine(api(loggedIn = false), history(), random = Random(9)))
        val deepFirst = first.map { pageOf(it.id) }.filter { it > 0 }.toSet()
        val deepSecond = second.map { pageOf(it.id) }.filter { it > 0 }.toSet()
        assertTrue("两次刷新抽到的页要不一样，否则等于没换", deepFirst != deepSecond)
    }

    @Test fun theSampledPagesGoDeepEnoughToMatter() {
        // 一页 36 条、每月约六千条，光最近半年就一千多页，十三年的存量更深。
        // 只在前几页里打转是没有意义的。
        val engine = RecommendationEngine(api(loggedIn = true), history(), random = Random(7))
        val pages = (0..3).flatMap { round -> engine.requestsFor(round) }
            .filter { it.page > 0 }
            .map { it.page }
        engine.close()
        assertTrue("没有抽到任何深页", pages.isNotEmpty())
        assertTrue("抽页要能翻过一个月（约 167 页），实测最深只有 ${pages.maxOrNull()}",
            pages.any { it > 167 })
        assertTrue("抽页不能越界", pages.all { it <= RecommendationEngine.ARCHIVE_DEPTH })
    }

    /**
     * 列表接口会回传总条数，所以抽页范围不用猜：读出来一算就知道最后一页在哪。
     * 一页 36 条、总共 3600 条，就是第 0..99 页，再往后抽都是空的。
     */
    @Test fun theSampledRangeComesFromTheCountTheServerReports() {
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(false)
        `when`(api.getCurrentUserBlocking()).thenThrow(IllegalStateException("no session in test"))
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            val page = invocation.getArgument<Int>(1)
            VideoListPage((0 until 6).map { video("$sort-p$page-$it") }, 3600)
        }
        val engine = RecommendationEngine(api, history(), random = Random(4))
        try {
            loadOnce(engine)
            val pages = (0..8).flatMap { engine.requestsFor(it) }.filter { it.page > 0 }.map { it.page }
            assertTrue("没抽到页，测不出范围", pages.isNotEmpty())
            assertTrue("抽页超出了服务端说的总页数：${pages.maxOrNull()}", pages.all { it <= 99 })
        } finally { engine.close() }
    }

    /** 服务端不回报总条数时的退路：抽到空页说明列表没那么长，之后就不该再往那么深抽。 */
    @Test fun anEmptyDeepPageStopsItFromDiggingThatDeepAgain() {
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(false)
        `when`(api.getCurrentUserBlocking()).thenThrow(IllegalStateException("no session in test"))
        // 第 200 页往后一条都没有——模拟列表到底了。
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            val page = invocation.getArgument<Int>(1)
            val videos = if (page > 200) emptyList() else (0 until 6).map { video("$sort-p$page-$it") }
            VideoListPage(videos, -1)
        }
        val engine = RecommendationEngine(api, history(), random = Random(11))
        try {
            val before = (0..6).flatMap { engine.requestsFor(it) }.maxOf { it.page }
            assertTrue("这个种子本来就没抽到深页，测不出收敛：$before", before > 200)
            repeat(6) { loadOnce(engine) }
            val after = (0..6).flatMap { engine.requestsFor(it) }.maxOf { it.page }
            assertTrue("抽到空页之后还在往更深处抽：$before -> $after", after < before)
            assertTrue("上限不该一路收到首页附近：$after", after >= RecommendationEngine.MIN_DEPTH)
        } finally { engine.close() }
    }

    @Test fun seeingEverythingOnTheFirstPageIsNotRunningOutOfVideos() {
        // 首页全看过。存量还有几千页，这时候必须还能推得出东西。
        val seenFirstPage = { id: String -> id.contains("-p0-") }
        val feed = load(RecommendationEngine(api(loggedIn = true), history(seenFirstPage), random = Random(3)))
        assertTrue("看完首页不该就没得推了", feed.isNotEmpty())
        assertTrue("推出来的必须是没看过的存档视频", feed.all { pageOf(it.id) > 0 })
    }
}
