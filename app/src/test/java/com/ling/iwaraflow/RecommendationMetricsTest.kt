package com.ling.iwaraflow

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 推荐质量的本地指标：单测能回答“算法有没有按规则跑”，回答不了“推荐有没有变好”。
 * 这几个数就是用来回答后一个问题的，只算本机数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationMetricsTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String, author: String = "Alice", tags: List<String> = listOf("miku")) =
        VideoItem(id, id, author, tags, 1, authorId = "id-$author")

    @Test fun anEmptyProfileReportsNothingInsteadOfDividingByZero() {
        val metrics = history.recommendationMetrics()
        assertEquals(0, metrics.samples)
        assertEquals(0.0, metrics.quickSkipRate, 1e-9)
        assertEquals(0L, metrics.averageWatchMs)
        assertTrue(metrics.summary().contains("没有足够"))
    }

    @Test fun theSkipRateAndReactionRateComeFromRecentBehaviour() {
        repeat(3) { history.recordInteraction(video("s$it", "Bob"), HistoryStore.ACTION_SKIP, -0.5) }
        history.recordInteraction(video("w1", "Alice"), "watch", 0.9)
        history.recordInteraction(video("l1", "Alice"), "like", 2.0)

        val metrics = history.recommendationMetrics()
        assertEquals("看过的 + 划走的 = 4 条", 4, metrics.samples)
        assertEquals(0.75, metrics.quickSkipRate, 1e-9)
        assertEquals("一次点赞配四条观看", 0.25, metrics.reactionRate, 1e-9)
        assertTrue("三条都是同一个作者，重复率不该是 0", metrics.authorRepeatRate > 0.0)
    }

    @Test fun watchTimeAndCompletionComeFromTheHistoryTable() {
        history.recordWatch(video("v1"), positionMs = 30_000L, durationMs = 60_000L, completed = false)
        history.recordWatch(video("v2"), positionMs = 90_000L, durationMs = 90_000L, completed = true)
        // 摘要只有在有观看行为时才写细节。
        history.recordInteraction(video("v1"), "watch", 0.9)

        val metrics = history.recommendationMetrics()
        assertEquals(60_000L, metrics.averageWatchMs)
        assertEquals(0.5, metrics.completionRate, 1e-9)
        assertTrue(metrics.summary().contains("平均播放 60 秒"))
    }

    /** 兴趣管理里的“恢复”：删掉那条不感兴趣记录，静音就解除了。 */
    @Test fun restoringAMutedAuthorOrTagUnmutesIt() {
        val item = video("v1", "Muted", listOf("banned", "other"))
        DislikeSheet.apply(RuntimeEnvironment.getApplication(), item, history, DislikeSheet.Kind.AUTHOR, "", null)
        DislikeSheet.apply(RuntimeEnvironment.getApplication(), item, history, DislikeSheet.Kind.TAG, "banned", null)

        val muted = history.preferenceProfile()
        assertTrue("muted" in muted.mutedAuthors)
        assertTrue("banned" in muted.mutedTags)

        assertTrue(history.forgetDislike(DislikeSheet.Kind.AUTHOR, "Muted") > 0)
        assertTrue(history.forgetDislike(DislikeSheet.Kind.TAG, "banned") > 0)

        val restored = history.preferenceProfile()
        assertFalse("恢复之后不再静音", "muted" in restored.mutedAuthors)
        assertFalse("banned" in restored.mutedTags)
        assertEquals("负权重也跟着没了", 0.0, restored.score(video("v2", "Muted", listOf("banned"))), 1e-9)
    }

    @Test fun restoringSomethingThatWasNeverMutedChangesNothing() {
        assertEquals(0, history.forgetDislike(DislikeSheet.Kind.AUTHOR, "nobody"))
        assertEquals(0, history.forgetDislike(DislikeSheet.Kind.TAG, ""))
    }
}
