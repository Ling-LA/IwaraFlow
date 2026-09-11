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
 * 关注作者的更新以前是权重最高的来源，结果推荐页刷出来整屏都是已经关注的人，
 * 推荐也就不成其为推荐了。现在改成按位置定量插入：每几条没关注的内容插一条关注的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationMixTest {
    private fun video(id: String) = VideoItem(id, id, "作者", emptyList(), 1, authorId = "author-$id")

    /** 订阅流给 `sub-*`，各榜单给 `<sort>-*`，两边都给足量，让插入比例说了算。 */
    private fun api(perSource: Int = 40): IwaraApi {
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(true)
        `when`(api.getCurrentUserBlocking()).thenThrow(IllegalStateException("no session in test"))
        `when`(api.getVideosBlocking(anyString(), anyInt(), anyInt())).thenAnswer { invocation ->
            val sort = invocation.getArgument<String>(0)
            val page = invocation.getArgument<Int>(1)
            (0 until perSource).map { video("$sort-p$page-$it") }
        }
        `when`(api.getSubscribedVideosBlocking(anyInt(), anyInt())).thenAnswer { invocation ->
            val page = invocation.getArgument<Int>(0)
            (0 until perSource).map { video("sub-p$page-$it") }
        }
        return api
    }

    private fun history(): HistoryStore {
        val history = mock(HistoryStore::class.java)
        `when`(history.preferenceProfile()).thenReturn(PreferenceProfile(emptyMap(), emptyMap()))
        `when`(history.isSeen(anyString())).thenReturn(false)
        return history
    }

    private fun feed(): List<VideoItem> {
        val engine = RecommendationEngine(api(), history(), random = Random(5))
        val delivered = CountDownLatch(1)
        val result = AtomicReference<Result<List<VideoItem>>>()
        engine.load(true) { outcome -> result.set(outcome); delivered.countDown() }
        assertTrue("推荐没有返回", delivered.await(15, TimeUnit.SECONDS))
        val feed = result.get().getOrThrow()
        engine.close()
        return feed
    }

    private fun followed(item: VideoItem) = item.id.startsWith("sub-")

    @Test fun followedAuthorsDoNotTakeOverTheFeed() {
        val feed = feed()
        val share = feed.count(::followed) * 100 / feed.size
        // 每 DISCOVERY_RUN 条插一条，占比就是 1/(RUN+1)，给舍入留点余量。
        val expected = 100 / (RecommendationEngine.DISCOVERY_RUN + 1)
        assertTrue("关注作者占了 $share%，不该霸屏", share <= expected + 6)
        assertTrue("关注作者一条都没有也不对，实际 $share%", share > 0)
    }

    @Test fun followedAuthorsComeAtAFixedSpacing() {
        val feed = feed()
        val positions = feed.withIndex().filter { followed(it.value) }.map { it.index }
        assertTrue("关注作者的更新必须出现在结果里", positions.size >= 3)
        // 相邻两条关注作者之间应当隔着 DISCOVERY_RUN 条没关注的。
        positions.zipWithNext { a, b ->
            assertEquals("两条关注作者之间的间隔不对：$positions",
                RecommendationEngine.DISCOVERY_RUN + 1, b - a)
        }
        assertTrue("开头不该就是关注作者", positions.first() >= RecommendationEngine.DISCOVERY_RUN)
    }

    @Test fun theFeedStillLeadsWithDiscovery() {
        val feed = feed()
        val head = feed.take(RecommendationEngine.DISCOVERY_RUN)
        assertTrue("前几条应该全是没关注过的内容：${head.map { it.id }}", head.none(::followed))
    }
}
