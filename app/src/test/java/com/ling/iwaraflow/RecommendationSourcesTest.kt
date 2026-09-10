package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 候选来源。以前只有四个总榜，其中 likes / views 是全站历史总榜——一组几乎不变的老视频，
 * 所以看得多的账号很快就没得可推。关注作者的更新和每天的新投稿才是取之不尽的来源。
 *
 * 这里一律用“结果里有没有这条视频”来断言：每个来源返回带自己名字的视频，
 * 没被取用的来源自然不会出现在结果里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationSourcesTest {
    private fun video(id: String) = VideoItem(id, id, "作者", emptyList(), 1, authorId = "author-$id")

    private fun load(engine: RecommendationEngine): List<VideoItem> {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        engine.load(true) { outcome -> result.set(outcome); delivered.countDown() }
        assertTrue("推荐没有返回", delivered.await(15, TimeUnit.SECONDS))
        val feed = result.get().getOrThrow()
        engine.close()
        return feed
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
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            val page = invocation.getArgument<Int>(1)
            (0 until perSource).map { video("$sort-p$page-$it") }
        }
        `when`(api.getSubscribedVideosBlocking(anyInt(), anyInt())).thenAnswer { invocation ->
            val page = invocation.getArgument<Int>(0)
            (0 until perSource).map { video("subscribed-p$page-$it") }
        }
        return api
    }

    @Test fun theSubscriptionFeedIsOneOfTheSources() {
        val feed = load(RecommendationEngine(api(loggedIn = true), history()))
        assertTrue("关注作者的更新必须进入候选", feed.any { it.id.startsWith("subscribed-") })
    }

    @Test fun newUploadsAreOneOfTheSources() {
        val feed = load(RecommendationEngine(api(loggedIn = false), history()))
        assertTrue("最新投稿必须进入候选", feed.any { it.id.startsWith("date-") })
    }

    @Test fun theAllTimeTopChartsAreNoLongerRead() {
        val feed = load(RecommendationEngine(api(loggedIn = false), history()))
        // 全站历史总榜每次都是同一批老视频，拉它们只是白费一次请求。
        assertTrue("不该再取 likes 总榜", feed.none { it.id.startsWith("likes-") })
        assertTrue("不该再取 views 总榜", feed.none { it.id.startsWith("views-") })
        assertTrue("热门榜单仍然保留", feed.any { it.id.startsWith("trending-") })
    }

    @Test fun deepeningWalksTheTimeOrderedFeedsNotTheStaticCharts() {
        // 第一页全看过，逼它继续往后翻。
        val seenFirstPage = { id: String -> id.contains("-p0-") }
        val feed = load(RecommendationEngine(api(loggedIn = true), history(seenFirstPage)))
        assertTrue("翻页要沿着最新投稿继续找", feed.any { it.id.startsWith("date-p1-") })
        assertTrue("翻页也要继续看关注作者的更新", feed.any { it.id.startsWith("subscribed-p1-") })
        assertTrue("热门榜单只取第一页，往后翻还是同一批老视频",
            feed.none { it.id.startsWith("trending-p1-") || it.id.startsWith("popularity-p1-") })
    }
}
