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
 * 「不感兴趣：当前视频」是视频级的：只压这一条，不该顺手把作者和它的十个标签一起压下去。
 * 三个按钮各管各的维度，用户点哪个就是哪个意思。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoFeedbackTest {
    private lateinit var history: HistoryStore

    @Before fun open() { history = HistoryStore(RuntimeEnvironment.getApplication()) }
    @After fun close() { history.close() }

    private fun video(id: String) = VideoItem(id, id, "Alice", listOf("miku", "dance"), 1, authorId = "author-1")

    @Test fun dislikingOneVideoLeavesTheAuthorAndTagsAlone() {
        val item = video("v1")
        DislikeSheet.apply(RuntimeEnvironment.getApplication(), item, history, DislikeSheet.Kind.VIDEO, "", null)
        val profile = history.preferenceProfile()

        assertTrue("这条视频自己要被压下去", profile.videoWeights["v1"]!! < 0)
        assertNull("作者不该受牵连", profile.authorWeights["alice"])
        assertNull("作者 id 不该受牵连", profile.authorIdWeights["author-1"])
        assertNull("标签不该受牵连", profile.tagWeights["miku"])
        assertEquals("同作者同标签的另一条视频不受影响", 0.0, profile.score(video("v2")), 1e-9)
        assertTrue("被点掉的那一条要沉底", profile.score(item) < 0)
    }

    @Test fun dislikingTheAuthorStillOnlyMovesTheAuthor() {
        val item = video("v1")
        DislikeSheet.apply(RuntimeEnvironment.getApplication(), item, history, DislikeSheet.Kind.AUTHOR, "", null)
        val profile = history.preferenceProfile()

        assertTrue(profile.authorIdWeights["author-1"]!! < 0)
        assertNull("作者那一档不碰标签", profile.tagWeights["miku"])
        assertNull("也不该记成视频级反馈", profile.videoWeights["v1"])
    }

    @Test fun dislikingATagStillOnlyMovesThatTag() {
        val item = video("v1")
        DislikeSheet.apply(RuntimeEnvironment.getApplication(), item, history, DislikeSheet.Kind.TAG, "miku", null)
        val profile = history.preferenceProfile()

        assertTrue(profile.tagWeights["miku"]!! < 0)
        assertNull("标签那一档不碰另一个标签", profile.tagWeights["dance"])
        assertNull("也不碰作者", profile.authorWeights["alice"])
        assertNull(profile.videoWeights["v1"])
    }

    @Test fun theVideoScoreAddsTheVideoLevelWeight() {
        val profile = PreferenceProfile(
            authorWeights = mapOf("alice" to 1.0),
            tagWeights = emptyMap(),
            authorIdWeights = emptyMap(),
            videoWeights = mapOf("v1" to -6.0)
        )
        assertEquals(-5.0, profile.score(VideoItem("v1", "t", "Alice", emptyList(), 0)), 1e-9)
        assertEquals(1.0, profile.score(VideoItem("v2", "t", "Alice", emptyList(), 0)), 1e-9)
    }
}
