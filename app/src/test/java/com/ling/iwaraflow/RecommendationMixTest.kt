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
 * 推荐也就不成其为推荐了。现在改成按位置定量插入：每几条没关注的内容配一条关注的，
 * 插在这一组里的哪个位置则是随机的。
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

    /** 一组 = DISCOVERY_RUN 条没关注的 + 插进去的那一条。 */
    private val blockSize get() = RecommendationEngine.DISCOVERY_RUN + 1

    @Test fun everyBlockCarriesExactlyOneFollowedVideo() {
        val feed = feed()
        val blocks = feed.chunked(blockSize).filter { it.size == blockSize }
        assertTrue("结果太短，凑不出几组", blocks.size >= 4)
        blocks.forEachIndexed { index, block ->
            assertEquals("第 $index 组里关注作者的条数不对：${block.map { it.id }}",
                1, block.count(::followed))
        }
    }

    @Test fun theInsertedPositionVariesInsteadOfAlwaysLandingOnTheSameSpot() {
        val feed = feed()
        val slots = feed.chunked(blockSize).filter { it.size == blockSize }
            .map { block -> block.indexOfFirst(::followed) }
        assertTrue("结果太短，看不出规律", slots.size >= 6)
        assertTrue("每组的插入位置都一样，等于还是固定节奏：$slots", slots.toSet().size > 1)
        assertTrue("插入位置越界：$slots", slots.all { it in 0 until blockSize })
    }

    @Test fun theFollowedVideoCanBeTheVeryFirstOneInABlock() {
        // 用几个不同的种子多跑几次，"可以排在第一位"就该出现过。
        val seen = (1..12).flatMap { seed ->
            val engine = RecommendationEngine(api(), history(), random = Random(seed.toLong()))
            val delivered = CountDownLatch(1)
            val result = AtomicReference<Result<List<VideoItem>>>()
            engine.load(true) { outcome -> result.set(outcome); delivered.countDown() }
            assertTrue("推荐没有返回", delivered.await(15, TimeUnit.SECONDS))
            val feed = result.get().getOrThrow()
            engine.close()
            feed.chunked(blockSize).filter { it.size == blockSize }.map { it.indexOfFirst(::followed) }
        }.toSet()
        assertTrue("从来没排到过第一位：$seen", 0 in seen)
        assertTrue("从来没排到过最后一位：$seen", RecommendationEngine.DISCOVERY_RUN in seen)
    }
}
