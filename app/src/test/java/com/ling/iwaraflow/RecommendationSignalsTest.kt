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
 * 行为 → 画像：点赞 / 看得久是正面，很快划走是负面；最近的行为权重更高；
 * 官方点赞作为种子只记一次、不算“当前兴趣”；作者按 id 也能召回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationSignalsTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String, author: String, tags: List<String>, authorId: String = "id-$author") =
        VideoItem(id, id, author, tags, 1, authorId = authorId)

    @Test fun quickSkipsPushAnAuthorAndItsTagsBelowZero() {
        history.recordInteraction(video("v1", "Alice", listOf("miku")), "skip", -0.5)
        history.recordInteraction(video("v2", "Alice", listOf("miku")), "skip", -0.5)
        val profile = history.preferenceProfile()
        assertTrue(profile.authorWeights["alice"]!! < 0)
        assertTrue(profile.tagWeights["miku"]!! < 0)
        assertTrue("不喜欢的作者的视频要被压下去", profile.score(video("v3", "Alice", listOf("miku"))) < 0)
    }

    @Test fun persistentSkipsOfAnAuthorAddALongTermPenalty() {
        repeat(HistoryStore.LONG_TERM_SKIPS - 1) { history.recordInteraction(video("v$it", "Dull", listOf("t")), "skip", -0.5) }
        val before = history.preferenceProfile()
        history.recordInteraction(video("vx", "Dull", listOf("t")), "skip", -0.5)
        val after = history.preferenceProfile()
        val authorDrop = before.authorWeights["dull"]!! - after.authorWeights["dull"]!!
        assertTrue("第 ${HistoryStore.LONG_TERM_SKIPS} 次划走后作者要多压一层：$authorDrop", authorDrop > HistoryStore.LONG_TERM_AUTHOR_PENALTY)
        val tagDrop = before.tagWeights["t"]!! - after.tagWeights["t"]!!
        assertTrue("标签同样多压一层：$tagDrop", tagDrop > HistoryStore.LONG_TERM_TAG_PENALTY)
    }

    @Test fun unlikeAndUnfavoriteAreNegative() {
        history.recordInteraction(video("v1", "Eve", listOf("e")), "like", 2.0)
        history.recordInteraction(video("v1", "Eve", listOf("e")), "unlike", -1.2)
        history.recordInteraction(video("v2", "Eve", listOf("e")), "unfavorite", -0.9)
        assertTrue(history.preferenceProfile().authorWeights["eve"]!! < 0)
    }

    @Test fun watchingLongCountsAsLikingAndOutweighsOneSkip() {
        history.recordInteraction(video("v1", "Bob", listOf("dance")), "watch", 0.8)
        history.recordInteraction(video("v2", "Bob", listOf("dance")), "skip", -0.5)
        assertTrue(history.preferenceProfile().authorWeights["bob"]!! > 0)
    }

    @Test fun recentBehaviourWeighsMoreThanOldBehaviour() {
        history.recordInteraction(video("v1", "Recent", listOf("a")), "like", 2.0)
        val now = System.currentTimeMillis()
        val fresh = history.preferenceProfileAt(now).authorWeights["recent"]!!
        val later = history.preferenceProfileAt(now + HistoryStore.SESSION_WINDOW_MS + 60_000L).authorWeights["recent"]!!
        assertTrue("刚发生的行为按当前兴趣加倍：$fresh vs $later", fresh > later * 2)
    }

    @Test fun cloudLikesSeedTheProfileOnceAndAreNotSessionInterest() {
        val liked = listOf(video("c1", "Cloud", listOf("mmd")), video("c2", "Cloud", listOf("mmd")))
        assertEquals(2, history.seedCloudLikes(liked))
        assertEquals("同一批再同步一次不重复记", 0, history.seedCloudLikes(liked))
        val profile = history.preferenceProfile()
        val weight = profile.authorWeights["cloud"]!!
        assertTrue(weight > 0)
        assertEquals("种子不乘当前兴趣倍数", HistoryStore.CLOUD_LIKE_WEIGHT * 2, weight, 1e-6)
    }

    @Test fun authorsAreAlsoWeightedByIdForRecall() {
        history.recordInteraction(video("v1", "Carol", listOf("x"), authorId = "carol-id"), "like", 2.0)
        history.recordInteraction(video("v2", "Carol", listOf("x"), authorId = "carol-id"), "favorite", 1.4)
        val profile = history.preferenceProfile()
        assertEquals(listOf("carol-id"), profile.topAuthorIds(3, RecommendationEngine.RECALL_AUTHOR_MIN_WEIGHT))
        assertEquals(listOf("x"), profile.topTags(2, RecommendationEngine.RECALL_TAG_MIN_WEIGHT))
        val renamed = VideoItem("v3", "v3", "New Name", listOf("x"), 1, authorId = "carol-id")
        assertTrue("改了名字的作者按 id 照样认得", profile.score(renamed) > 5)
    }
}
