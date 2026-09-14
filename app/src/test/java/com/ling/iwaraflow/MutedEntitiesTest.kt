package com.ling.iwaraflow

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「不感兴趣：作者 / 标签」是**显式**负反馈：它压过所有隐式正信号，
 * 而且永远不过期——只有兴趣管理里的“恢复”能解除。
 *
 * 以前它只是行为表里的一行，而行为表是滚动窗口（最近 3000 条、画像读最近 2000 条），
 * 刷得够多，几个月前拉黑的作者就会悄悄回到推荐里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MutedEntitiesTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String, author: String = "Alice", tags: List<String> = listOf("miku")) =
        VideoItem(id, id, author, tags, 1, authorId = "id-$author")

    @Test fun aMuteLivesInItsOwnTableInsteadOfTheBehaviourWindow() {
        history.mute(HistoryStore.MUTE_AUTHOR, "Muted")
        history.mute(HistoryStore.MUTE_AUTHOR_ID, "id-Muted")
        history.mute(HistoryStore.MUTE_TAG, "Banned")

        // 行为表里一条记录都没有：静音不靠行为窗口活着。
        assertEquals(0, history.recommendationMetrics().samples)
        val profile = history.preferenceProfile()
        assertTrue("muted" in profile.mutedAuthors)
        assertTrue("id-Muted" in profile.mutedAuthorIds)
        assertTrue("banned" in profile.mutedTags)
        assertTrue(profile.isMuted(video("v1", "Muted", listOf("other"))))
        assertTrue(profile.isMuted(video("v2", "Somebody", listOf("banned"))))
    }

    /** 行为表被新行为刷满（拉黑那条早滚出去了），静音照旧生效。 */
    @Test fun aMuteSurvivesTheBehaviourWindowRollingOver() {
        val item = video("v1", "Muted", listOf("banned"))
        DislikeSheet.apply(RuntimeEnvironment.getApplication(), item, history, DislikeSheet.Kind.AUTHOR, "", null)
        // 直接把行为表清空，等价于“刷了几千条之后那一行被挤掉了”，但快得多。
        history.writableDatabase.delete("interactions", null, null)

        val profile = history.preferenceProfile()
        assertEquals("行为表确实空了", 0, history.recommendationMetrics().samples)
        assertTrue("拉黑不该跟着行为一起过期", "muted" in profile.mutedAuthors)
        assertTrue(profile.isMuted(item))
    }

    /** 兴趣管理里的“恢复”是唯一的解除方式，两笔记录都要删掉。 */
    @Test fun restoringClearsBothTheMuteAndTheNegativeWeight() {
        val item = video("v1", "Muted", listOf("banned", "other"))
        val context = RuntimeEnvironment.getApplication()
        DislikeSheet.apply(context, item, history, DislikeSheet.Kind.AUTHOR, "", null)
        DislikeSheet.apply(context, item, history, DislikeSheet.Kind.TAG, "banned", null)
        assertTrue(history.preferenceProfile().isMuted(item))

        assertTrue(history.forgetDislike(DislikeSheet.Kind.AUTHOR, "Muted") > 0)
        assertTrue(history.forgetDislike(DislikeSheet.Kind.TAG, "banned") > 0)

        val restored = history.preferenceProfile()
        assertFalse(restored.isMuted(item))
        assertEquals("负权重也跟着没了", 0.0, restored.score(video("v2", "Muted", listOf("banned"))), 1e-9)
        assertEquals("muted_entities 也清干净了", MutedEntities(), history.mutedEntities())
    }

    /** 拉黑的作者 / 标签不再参与个性化召回，免得抓回来再被硬过滤掉，白跑一趟请求。 */
    @Test fun mutedKeysAreExcludedFromRecall() {
        val profile = PreferenceProfile(
            authorWeights = emptyMap(),
            tagWeights = mapOf("banned" to 9.0, "keep" to 1.0),
            authorIdWeights = mapOf("id-muted" to 9.0, "id-keep" to 1.0),
            mutedAuthorIds = setOf("id-muted"),
            mutedTags = setOf("banned")
        )
        assertEquals(listOf("keep"), profile.topTags(4, 0.5))
        assertEquals(listOf("id-keep"), profile.topAuthorIds(4, 0.5))
    }

    /** 老库升上来的时候，原来记在行为表里的拉黑要搬进新表。 */
    @Test fun upgradingFromTheOldSchemaKeepsExistingMutes() {
        val context = RuntimeEnvironment.getApplication()
        history.close()
        context.deleteDatabase("iwaraflow.db")
        LegacyStore(context).use { legacy ->
            legacy.writableDatabase.execSQL(
                "INSERT INTO interactions(video_id, action, author, tags, weight, created_at, author_id) " +
                    "VALUES('v1', '${HistoryStore.ACTION_DISLIKE_AUTHOR}', 'Muted', '', -2.5, 1, 'id-Muted')"
            )
            legacy.writableDatabase.execSQL(
                "INSERT INTO interactions(video_id, action, author, tags, weight, created_at, author_id) " +
                    "VALUES('v2', '${HistoryStore.ACTION_DISLIKE_TAG}', '', 'Banned', -4.0, 1, '')"
            )
        }

        history = HistoryStore(context)
        val muted = history.mutedEntities()
        assertEquals(setOf("muted"), muted.authors)
        assertEquals(setOf("id-Muted"), muted.authorIds)
        assertEquals(setOf("banned"), muted.tags)
    }

    /** 只建旧版本（v6）里这次迁移会读到的那张表，让 HistoryStore 走 6 → 7 的升级。 */
    private class LegacyStore(context: Context) : SQLiteOpenHelper(context, "iwaraflow.db", null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE interactions(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    video_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    author TEXT NOT NULL,
                    tags TEXT NOT NULL,
                    weight REAL NOT NULL,
                    created_at INTEGER NOT NULL,
                    author_id TEXT NOT NULL DEFAULT ''
                )""".trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }
    }
}
