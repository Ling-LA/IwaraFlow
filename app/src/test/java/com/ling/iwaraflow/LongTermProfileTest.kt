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
 * 长期口味不该“突然失忆”。
 *
 * 画像以前只读最近 2000 条行为：一个重度用户做完两千次别的操作之后，常年喜欢的那个作者
 * 不是慢慢淡掉，而是**整个滚出查询窗口**，一夜之间从画像里消失。现在长期那一份来自
 * 聚合表（每次行为累加、按时间衰减），行为窗口只负责最近 45 分钟的“当前兴趣”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LongTermProfileTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String, author: String, tags: List<String> = listOf("mmd")) =
        VideoItem(id, id, author, tags, 1, authorId = "id-$author")

    @Test fun anOldInterestSurvivesTheBehaviourWindowRollingOver() {
        history.recordInteraction(video("v1", "Favourite"), "like", 2.0)
        val before = history.preferenceProfile().authorWeights.getValue("favourite")
        assertTrue(before > 0)

        // 行为表整个被后来的操作挤掉了（这里直接清空，等价于滚出了窗口）。
        history.writableDatabase.delete("interactions", null, null)

        val after = history.preferenceProfile()
        assertTrue("老兴趣不该因为滚出窗口就消失：${after.authorWeights["favourite"]}",
            (after.authorWeights["favourite"] ?: 0.0) > 0.0)
        assertTrue((after.authorIdWeights["id-Favourite"] ?: 0.0) > 0.0)
        assertTrue((after.tagWeights["mmd"] ?: 0.0) > 0.0)
        assertTrue("但只剩长期那一份，比带着当前兴趣加成时小",
            after.authorWeights.getValue("favourite") < before)
    }

    /** 长期分按时间衰减，一个月折半——是淡掉，不是断崖。 */
    @Test fun theLongTermScoreFadesInsteadOfDisappearing() {
        history.recordInteraction(video("v1", "Fading"), "like", 2.0)
        history.writableDatabase.delete("interactions", null, null)
        val now = System.currentTimeMillis()
        val fresh = history.preferenceProfileAt(now).authorWeights.getValue("fading")
        val month = history.preferenceProfileAt(now + 30L * 24 * 3600 * 1000).authorWeights.getValue("fading")
        val halfYear = history.preferenceProfileAt(now + 180L * 24 * 3600 * 1000).authorWeights.getValue("fading")
        assertEquals("一个月折半", fresh / 2, month, 0.05)
        assertTrue(halfYear in 0.0..month)
        assertTrue("半年后还剩一点，但已经很小", halfYear < fresh * 0.1)
    }

    /** 搜索只是短期意图，不进长期画像；官方点赞是账号级的种子，也只留在窗口里。 */
    @Test fun shortTermIntentDoesNotEnterTheLongTermProfile() {
        history.recordInteraction(
            VideoItem("tag:clara", "clara", "", listOf("clara"), 0),
            HistoryStore.ACTION_SEARCH, HistoryStore.SEARCH_WEIGHT
        )
        history.seedCloudLikes(listOf(video("c1", "Cloud")))
        history.writableDatabase.delete("interactions", null, null)

        val profile = history.preferenceProfile()
        assertNull("搜索不该变成长期兴趣", profile.tagWeights["clara"])
        assertNull("官方点赞种子留在窗口里，不进聚合表", profile.authorWeights["cloud"])
    }

    /** “不感兴趣：当前视频”只压那一条，同样不进长期画像。 */
    @Test fun aVideoLevelDislikeStaysOutOfTheLongTermProfile() {
        DislikeSheet.apply(
            RuntimeEnvironment.getApplication(), video("v1", "Alice"), history,
            DislikeSheet.Kind.VIDEO, "", null
        )
        history.writableDatabase.delete("interactions", null, null)
        val profile = history.preferenceProfile()
        assertNull(profile.authorWeights["alice"])
        assertNull(profile.tagWeights["mmd"])
    }

    /** 兴趣管理里的“恢复”要连长期画像里那一条一起清掉，否则分数还压着，等于没恢复。 */
    @Test fun restoringAlsoClearsTheLongTermScore() {
        val item = video("v1", "Muted", listOf("banned"))
        val context = RuntimeEnvironment.getApplication()
        DislikeSheet.apply(context, item, history, DislikeSheet.Kind.AUTHOR, "", null)
        DislikeSheet.apply(context, item, history, DislikeSheet.Kind.TAG, "banned", null)
        assertTrue(history.preferenceProfile().score(item) < 0)

        history.forgetDislike(DislikeSheet.Kind.AUTHOR, "Muted")
        history.forgetDislike(DislikeSheet.Kind.TAG, "banned")
        history.writableDatabase.delete("interactions", null, null)

        assertEquals("恢复之后一点分都不该剩", 0.0, history.preferenceProfile().score(item), 1e-9)
    }
}
