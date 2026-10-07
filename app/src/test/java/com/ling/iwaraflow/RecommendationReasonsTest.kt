package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationReasonsTest {
    private val taste = PreferenceProfile(emptyMap(), mapOf("dance" to 4.0), positiveTagWeights = mapOf("dance" to 4.0))
    private fun item(id: String) = VideoItem(id, "Fixture", "Author $id", listOf("dance"), 20, views = 500,
        createdAt = System.currentTimeMillis(), authorId = "author-$id")
    private fun inferred(ranker: RecommendationRanker, item: VideoItem) = ranker.reasonFor(item.id)!!.startsWith("猜你喜欢")
    private fun remember(ranker: RecommendationRanker, items: List<VideoItem>) {
        items.forEachIndexed { i, video -> ranker.remember(video) {
            it.sources += listOf(RecommendationCandidate.Source.DATE, RecommendationCandidate.Source.TRENDING,
                RecommendationCandidate.Source.POPULARITY, RecommendationCandidate.Source.MONTH_TOP)[i % 4]
        } }
    }

    @Test fun commonPositiveTagDoesNotOverwriteExplicitReasons() {
        val ranker = RecommendationRanker().apply { profile = taste }
        val cases: List<Pair<(RecommendationCandidate) -> Unit, String>> = listOf(
            ({ it: RecommendationCandidate -> it.subscribed = true }) to "你关注的作者更新了",
            ({ it: RecommendationCandidate -> it.exploration = true }) to "为你探索的新内容",
            ({ it: RecommendationCandidate -> it.classic = true }) to "历史上的高赞作品",
            ({ it: RecommendationCandidate -> it.matchedTags += "dance" }) to "你最近常看 #dance",
            ({ it: RecommendationCandidate -> it.matchedAuthorId = "author-fixture" }) to "你喜欢这位作者的作品"
        )
        val videos = cases.mapIndexed { index, (configure, _) -> item("explicit-$index").also { ranker.remember(it, configure) } }
        ranker.assignInterestReasons(videos, taste)
        videos.zip(cases).forEach { (video, case) ->
            assertEquals(case.second, ranker.reasonFor(video.id))
            assertEquals(ranker.candidateFor(video.id)!!.reason(), ranker.reasonFor(video.id))
        }
    }

    @Test fun saturatedInterestsStillKeepChartAndFreshReasons() {
        val ranker = RecommendationRanker().apply { profile = taste }
        val videos = (0 until 80).map { item("popular-$it") }
        remember(ranker, videos)
        val originalOrder = videos.map { it.id }
        ranker.assignInterestReasons(videos, taste)
        val positions = videos.indices.filter { inferred(ranker, videos[it]) }
        assertEquals(20, positions.size)
        assertTrue(positions.zipWithNext().all { (a, b) -> b - a >= 4 })
        assertEquals(originalOrder, videos.map { it.id })
        assertTrue(videos.any { ranker.reasonFor(it.id) == "热门榜上的作品" })
        assertTrue(videos.any { ranker.reasonFor(it.id) == "流行榜上的作品" })
        assertTrue(videos.any { ranker.reasonFor(it.id) == "近期的高赞作品" })
        assertTrue(videos.any { ranker.reasonFor(it.id) == "最近的新投稿" })
        videos.forEach { assertEquals(ranker.candidateFor(it.id)!!.reason(), ranker.reasonFor(it.id)) }
    }

    @Test fun scarceSourceReasonsAreNotReplacedByInterestInference() {
        val ranker = RecommendationRanker()
        val videos = (0 until 4).map { item("scarce-$it") }
        remember(ranker, videos) // One candidate from each chart/fresh source.
        ranker.assignInterestReasons(videos, taste)
        assertTrue(videos.none { inferred(ranker, it) })
        assertEquals(4, videos.map { ranker.reasonFor(it.id) }.distinct().size)
    }

    @Test fun noAdjacentInferencesAcrossBlockBoundaryAndShortFeedsKeepOriginalReasons() {
        val ranker = RecommendationRanker()
        val videos = (0 until 12).map { i -> item("boundary-$i").copy(tags = listOf(if (i == 3 || i == 4) "strong" else "dance")) }
        val profile = taste.copy(tagWeights = taste.tagWeights + ("strong" to 8.0),
            positiveTagWeights = taste.positiveTagWeights + ("strong" to 8.0))
        remember(ranker, videos)
        ranker.assignInterestReasons(videos, profile)
        assertTrue(inferred(ranker, videos[3])); assertFalse(inferred(ranker, videos[4]))
        val positions = videos.indices.filter { inferred(ranker, videos[it]) }
        assertTrue(positions.zipWithNext().all { (a, b) -> b - a >= 4 })
        ranker.assignInterestReasons(videos.take(3), profile)
        assertTrue(videos.take(3).none { inferred(ranker, it) })
    }

    @Test fun manualOnlyNegativeAndRemovedInterestsDoNotProduceInferences() {
        val ranker = RecommendationRanker()
        val videos = (0 until 12).map { item("profile-$it") }
        remember(ranker, videos)
        ranker.assignInterestReasons(videos, taste)
        assertTrue(videos.any { inferred(ranker, it) })
        val profiles = listOf(
            taste.copy(positiveTagWeights = emptyMap(), manualTagPreferences = mapOf("dance" to 1)),
            taste.copy(manualTagPreferences = mapOf("dance" to -1)),
            taste.copy(tagWeights = mapOf("dance" to -2.0)),
            taste.copy(mutedTags = setOf("dance")),
            PreferenceProfile(emptyMap(), emptyMap())
        )
        for (profile in profiles) {
            ranker.assignInterestReasons(videos, profile)
            assertTrue(videos.none { inferred(ranker, it) })
        }
    }

    @Test fun finalAssemblyAndLiveRerankingKeepTheSameBoundWithoutChangingScoresOrOrder() {
        val videos = (0 until 40).map { item("feed-$it") }
        fun feed(profile: PreferenceProfile): Pair<RecommendationRanker, List<VideoItem>> {
            val ranker = RecommendationRanker(Random(42)).apply { this.profile = profile }
            val pool = videos.associate { v -> v.id to RecommendationCandidate(v).also { it.sources += RecommendationCandidate.Source.DATE } }
            return ranker to ranker.assemble(ranker.rank(pool), emptySet(), emptySet(), emptyList())
        }
        val (ranker, result) = feed(taste)
        val (_, withoutEvidence) = feed(taste.copy(positiveTagWeights = emptyMap()))
        assertEquals(withoutEvidence.map { it.id }, result.map { it.id })
        assertEquals(10, result.count { inferred(ranker, it) })
        assertTrue(result.any { ranker.reasonFor(it.id) == "最近的新投稿" })
        val reranked = ranker.rerank(result, taste, System.currentTimeMillis())
        assertEquals(10, reranked.count { inferred(ranker, it) })
        // Live reranking uses its supplied profile even if the stored profile is older.
        ranker.rerank(reranked, taste.copy(positiveTagWeights = emptyMap()), System.currentTimeMillis())
        assertTrue(reranked.none { inferred(ranker, it) })
    }
}
