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
 * 从云端导进来的东西（官方点赞、同步的已看）是**账号级**的，本机自己的行为是设备级的。
 *
 * 以前两者混在一起：登录账号 A、同步完 A 的点赞，退出再登录 B，
 * A 的点赞仍然在给 B 算口味，A 点过的视频对 B 也算“已看”被过滤掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountScopeTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String, author: String) =
        VideoItem(id, id, author, listOf("tag-$author"), 1, authorId = "id-$author")

    @Test fun anotherAccountsCloudLikesDoNotShapeThisAccountsProfile() {
        history.accountId = "account-a"
        history.seedCloudLikes(listOf(video("v1", "Alice")))
        history.markSeen(listOf("v1"), account = "account-a")

        val a = history.preferenceProfile()
        assertTrue("自己的点赞当然算数", (a.authorIdWeights["id-Alice"] ?: 0.0) > 0.0)
        assertTrue(history.isSeen("v1"))

        history.accountId = "account-b"
        val b = history.preferenceProfile()
        assertNull("换账号后上一个账号的点赞不该再算口味", b.authorIdWeights["id-Alice"])
        assertFalse("也不该把它点过的视频算成已看", history.isSeen("v1"))
        assertFalse("v1" in history.loadStatuses(listOf("v1")).seen)
    }

    @Test fun localBehaviourStaysWithTheDeviceWhicheverAccountIsLoggedIn() {
        history.accountId = "account-a"
        history.recordInteraction(video("v2", "Bob"), "watch", 1.0)
        history.markSeen("v2")

        history.accountId = "account-b"
        val profile = history.preferenceProfile()
        assertTrue("本机看过的照旧算口味", (profile.authorIdWeights["id-Bob"] ?: 0.0) > 0.0)
        assertTrue("本机看过的照旧算已看", history.isSeen("v2"))
        assertTrue("v2" in history.loadStatuses(listOf("v2")).seen)
    }

    /** 同一条视频在两个账号下各种一次，互不干扰。 */
    @Test fun eachAccountKeepsItsOwnCopyOfACloudLike() {
        history.accountId = "account-a"
        assertEquals(1, history.seedCloudLikes(listOf(video("v3", "Cara"))))
        assertEquals("同一个账号重复同步只记一次", 0, history.seedCloudLikes(listOf(video("v3", "Cara"))))

        history.accountId = "account-b"
        assertEquals("换个账号是另一份记录", 1, history.seedCloudLikes(listOf(video("v3", "Cara"))))
    }
}
