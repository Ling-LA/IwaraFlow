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
 * 推荐质量指标按**曝光**算，而且分流。
 *
 * 播放器是所有页面共用的：在作者页连看一小时同一个作者，那些观看以前也会被算进
 * “推荐质量”。平均播放时长也不再读 `history.last_position`——拖到 8 分钟看十秒，
 * 那个字段会显示“看了八分钟”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImpressionMetricsTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String, author: String = "Alice", tags: List<String> = listOf("miku")) =
        VideoItem(id, id, author, tags, 1, authorId = "id-$author")

    @Test fun metricsOnlyCountTheSurfaceTheyAreAskedAbout() {
        history.recordImpression("s1", video("rec1"), HistoryStore.SURFACE_RECOMMEND, 0)
        history.noteImpressionOutcome("s1", "rec1", playedMs = 60_000, durationMs = 120_000,
            completed = false, skipped = false)
        // 作者页看了一小时：不该算进推荐质量。
        history.recordImpression("s2", video("author1"), "author", 0)
        history.noteImpressionOutcome("s2", "author1", playedMs = 3_600_000, durationMs = 3_600_000,
            completed = true, skipped = false)

        val rec = history.recommendationMetrics()
        assertEquals(1, rec.samples)
        assertEquals(60_000L, rec.averageWatchMs)
        assertEquals(0.0, rec.completionRate, 1e-9)

        val author = history.recommendationMetrics(surface = "author")
        assertEquals(1, author.samples)
        assertEquals(1.0, author.completionRate, 1e-9)
    }

    @Test fun theAverageWatchIsRealPlaytimeNotTheSeekPosition() {
        // 用户直接拖到 8 分钟，看了十秒就走：history 里的 last_position 是 8 分钟。
        history.recordWatch(video("v1"), positionMs = 480_000, durationMs = 600_000, completed = false)
        history.recordImpression("s1", video("v1"), HistoryStore.SURFACE_RECOMMEND, 0)
        history.noteImpressionOutcome("s1", "v1", playedMs = 10_000, durationMs = 600_000,
            completed = false, skipped = true)

        val metrics = history.recommendationMetrics()
        assertEquals("看了多久就是多久", 10_000L, metrics.averageWatchMs)
        assertEquals(1.0, metrics.quickSkipRate, 1e-9)
    }

    @Test fun anImpressionNobodyReachedIsNotCountedEitherWay() {
        history.recordImpression("s1", video("v1"), HistoryStore.SURFACE_RECOMMEND, 0)
        history.recordImpression("s1", video("v2"), HistoryStore.SURFACE_RECOMMEND, 1)
        history.noteImpressionOutcome("s1", "v1", 30_000, 60_000, completed = false, skipped = false)

        assertEquals("只有真的播过 / 划走过的才算样本", 1, history.recommendationMetrics().samples)
    }

    @Test fun reactionsCountAsStrongPositiveFeedback() {
        history.recordImpression("s1", video("v1"), HistoryStore.SURFACE_RECOMMEND, 0)
        history.noteImpressionReaction("s1", "v1", liked = true)
        assertEquals(1.0, history.recommendationMetrics().reactionRate, 1e-9)
    }

    /** 按来源拆开看：标签召回的作品到底看不看得下去。 */
    @Test fun sourceStatsSplitEachRecallApart() {
        history.recordImpression("s1", video("v1"), HistoryStore.SURFACE_RECOMMEND, 0, sources = "tag")
        history.noteImpressionOutcome("s1", "v1", 120_000, 300_000, completed = false, skipped = false)
        history.recordImpression("s1", video("v2"), HistoryStore.SURFACE_RECOMMEND, 1, sources = "trending")
        history.noteImpressionOutcome("s1", "v2", 2_000, 300_000, completed = false, skipped = true)
        history.recordImpression("s1", video("v3"), HistoryStore.SURFACE_RECOMMEND, 2,
            sources = "date", exploration = true)
        history.noteImpressionOutcome("s1", "v3", 1_000, 300_000, completed = false, skipped = true)

        val stats = history.impressionSourceStats().associateBy { it.source }
        assertEquals(0.0, stats.getValue("tag").skipRate, 1e-9)
        assertEquals(120_000L, stats.getValue("tag").averageWatchMs)
        assertEquals(1.0, stats.getValue("trending").skipRate, 1e-9)
        assertEquals("探索位单独算一行", 1, stats.getValue("exploration").samples)
    }

    /** 同一轮里同一条视频只记一次，重复调用不会把已有的结果冲掉。 */
    @Test fun recordingTheSameImpressionTwiceKeepsTheFirstOutcome() {
        history.recordImpression("s1", video("v1"), HistoryStore.SURFACE_RECOMMEND, 0, sources = "tag")
        history.noteImpressionOutcome("s1", "v1", 90_000, 300_000, completed = false, skipped = false)
        history.recordImpression("s1", video("v1"), HistoryStore.SURFACE_RECOMMEND, 7, sources = "date")

        val metrics = history.recommendationMetrics()
        assertEquals(1, metrics.samples)
        assertEquals(90_000L, metrics.averageWatchMs)
        assertEquals(listOf("tag"), history.impressionSourceStats().map { it.source })
    }

    /** 曝光表还空着（刚升级上来）时退回老口径，至少有个数可看。 */
    @Test fun withoutAnyImpressionItFallsBackToTheBehaviourTable() {
        history.recordInteraction(video("v1"), "watch", 0.9)
        history.recordWatch(video("v1"), positionMs = 30_000, durationMs = 60_000, completed = false)
        val metrics = history.recommendationMetrics()
        assertEquals(1, metrics.samples)
        assertEquals(30_000L, metrics.averageWatchMs)
    }
}
