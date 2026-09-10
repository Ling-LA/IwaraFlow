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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationSourcesTest {
    private fun video(id: String, authorId: String = "author-x") =
        VideoItem(id, id, "作者", emptyList(), 1, authorId = authorId)

    private fun load(engine: RecommendationEngine, skipSeen: Boolean = true): List<VideoItem> {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        engine.load(skipSeen) { outcome -> result.set(outcome); delivered.countDown() }
        assertTrue("推荐没有返回", delivered.await(15, TimeUnit.SECONDS))
        return result.get().getOrThrow()
    }

    private fun freshHistory(): HistoryStore {
        val history = mock(HistoryStore::class.java)
        `when`(history.preferenceProfile()).thenReturn(PreferenceProfile(emptyMap(), emptyMap()))
        `when`(history.isSeen(anyString())).thenReturn(false)
        return history
    }

    @Test fun theSubscriptionFeedIsOneOfTheSources() {
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(true)
        // 关注列表在后台线程刷新；让它立刻结束，免得和下面的校验抢 Mockito。
        `when`(api.getCurrentUserBlocking()).thenThrow(IllegalStateException("no session in test"))
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenReturn(listOf(video("ranked-1")))
        `when`(api.getSubscribedVideosBlocking(anyInt(), anyInt())).thenReturn(listOf(video("subscribed-1")))
        val engine = RecommendationEngine(api, freshHistory())
        val feed = load(engine)
        engine.close()
        verify(api, timeout(5_000).atLeastOnce()).getSubscribedVideosBlocking(anyInt(), anyInt())
        assertTrue("关注作者的更新必须进入候选", feed.any { it.id == "subscribed-1" })
    }

    @Test fun newUploadsAreAmongTheRankingsWeRead() {
        val api = mock(IwaraApi::class.java)
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            listOf(video("${invocation.getArgument<String>(0)}-1"))
        }
        val engine = RecommendationEngine(api, freshHistory())
        load(engine)
        engine.close()
        verify(api, atLeastOnce()).getVideosBlocking(eq("date"), anyInt(), anyInt())
    }

    @Test fun theAllTimeTopChartsAreNoLongerAskedFor() {
        val api = mock(IwaraApi::class.java)
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenReturn(listOf(video("ranked-1")))
        val engine = RecommendationEngine(api, freshHistory())
        load(engine)
        engine.close()
        // 全站历史总榜每次都是同一批老视频，拉它们只是白费一次请求。
        verify(api, never()).getVideosBlocking(eq("likes"), anyInt(), anyInt())
        verify(api, never()).getVideosBlocking(eq("views"), anyInt(), anyInt())
    }

    @Test fun deepeningWalksTheTimeOrderedListsNotTheStaticCharts() {
        val history = mock(HistoryStore::class.java)
        `when`(history.preferenceProfile()).thenReturn(PreferenceProfile(emptyMap(), emptyMap()))
        // 第一页全看过，逼它继续往后翻。
        `when`(history.isSeen(anyString())).thenAnswer { it.getArgument<String>(0).startsWith("seen") }
        val api = mock(IwaraApi::class.java)
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            val page = invocation.getArgument<Int>(1)
            if (page == 0) (0 until 6).map { video("seen-$sort-$it") }
            else (0 until 14).map { video("new-$sort-page$page-$it") }
        }
        val engine = RecommendationEngine(api, history)
        val feed = load(engine)
        engine.close()
        verify(api, atLeastOnce()).getVideosBlocking(eq("date"), eq(1), anyInt())
        verify(api, never()).getVideosBlocking(eq("trending"), eq(1), anyInt())
        assertTrue("翻页取到的新视频要进入结果", feed.any { it.id.startsWith("new-date") })
    }
}
