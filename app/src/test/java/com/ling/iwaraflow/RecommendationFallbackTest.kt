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
 * 看得多、点赞多的账号很容易把一页候选全过滤掉。推荐页宁可给看过的视频，
 * 也不能因为“排除已看”变成一个空页面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationFallbackTest {
    private fun video(id: String) = VideoItem(id, id, "作者", emptyList(), 1)

    private fun engineWith(
        history: HistoryStore,
        pages: (page: Int) -> List<VideoItem>
    ): Pair<RecommendationEngine, IwaraApi> {
        val api = mock(IwaraApi::class.java)
        `when`(history.preferenceProfile()).thenReturn(PreferenceProfile(emptyMap(), emptyMap()))
        `when`(api.getVideoListPageBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            VideoListPage(pages(invocation.getArgument(1)), -1)
        }
        return RecommendationEngine(api, history) to api
    }

    private fun load(engine: RecommendationEngine): List<VideoItem> {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        engine.load(true) { outcome -> result.set(outcome); delivered.countDown() }
        assertTrue("推荐没有返回", delivered.await(15, TimeUnit.SECONDS))
        return result.get().getOrThrow()
    }

    @Test fun anAccountThatHasSeenEverythingStillGetsAFeed() {
        val history = mock(HistoryStore::class.java)
        `when`(history.isSeen(anyString())).thenReturn(true)
        val (engine, _) = engineWith(history) { page -> (0 until 6).map { video("page$page-$it") } }
        try {
            val feed = load(engine)
            assertTrue("全部看过时也必须给出视频，而不是空推荐页", feed.isNotEmpty())
        } finally { engine.close() }
    }

    @Test fun freshVideosAreStillPreferredWhenThereAreEnoughOfThem() {
        val history = mock(HistoryStore::class.java)
        `when`(history.isSeen(anyString())).thenAnswer { it.getArgument<String>(0).startsWith("seen") }
        val (engine, _) = engineWith(history) { page ->
            if (page > 0) emptyList()
            else (0 until 8).map { video("seen-$it") } + (0 until 16).map { video("new-$it") }
        }
        try {
            val feed = load(engine)
            assertEquals(16, feed.size)
            assertTrue("候选够用时不该混入看过的视频", feed.all { it.id.startsWith("new") })
        } finally { engine.close() }
    }

    @Test fun aStarvedFirstPageLooksFurtherDownTheRankings() {
        val history = mock(HistoryStore::class.java)
        `when`(history.isSeen(anyString())).thenAnswer { it.getArgument<String>(0).startsWith("seen") }
        val (engine, api) = engineWith(history) { page ->
            if (page == 0) (0 until 20).map { video("seen-$it") }
            else (0 until 14).map { video("new-page$page-$it") }
        }
        val feed = load(engine)
        engine.close()
        assertTrue("必须往后翻页才找得到没看过的视频", feed.any { it.id.startsWith("new-page") })
        assertTrue("翻页找到的新视频要排在前面", feed.first().id.startsWith("new"))
    }

    @Test fun likedVideosAreTheLastResortNotTheFirstChoice() {
        val history = mock(HistoryStore::class.java)
        `when`(history.isSeen(anyString())).thenReturn(true)
        `when`(history.isLocalFavorite(anyString())).thenAnswer { it.getArgument<String>(0).startsWith("liked") }
        val (engine, _) = engineWith(history) { page ->
            if (page > 0) emptyList()
            else (0 until 4).map { video("liked-$it") } + (0 until 4).map { video("watched-$it") }
        }
        try {
            val feed = load(engine)
            assertTrue("收藏过的不该排在看过的前面", feed.first().id.startsWith("watched"))
        } finally { engine.close() }
    }
}
