package com.ling.iwaraflow

import android.content.Intent
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 在作者页关注后返回，视频流里那位作者的关注按钮必须跟着变。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FollowSyncTest {
    private fun video(id: String, authorId: String, username: String) =
        VideoItem(id, "Fixture", "Fixture 作者", emptyList(), 0, authorId = authorId, authorUsername = username)

    private fun adapter(): VideoAdapter {
        val context = RuntimeEnvironment.getApplication()
        return VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
    }

    @Test fun theAuthorPageReportsTheFollowStateItLeavesWith() {
        val data = Intent()
            .putExtra(AuthorActivity.EXTRA_ID, "author-1")
            .putExtra(AuthorActivity.EXTRA_USERNAME, "fixture")
            .putExtra(AuthorActivity.EXTRA_FOLLOWING, true)
        val result = AuthorActivity.readFollowResult(data)
        assertNotNull(result)
        assertEquals("author-1", result!!.authorId)
        assertTrue(result.following)
    }

    @Test fun aReturnWithoutAFollowStateChangesNothing() {
        assertNull(AuthorActivity.readFollowResult(null))
        assertNull(AuthorActivity.readFollowResult(
            Intent().putExtra(AuthorActivity.EXTRA_RETURN_FROM_AUTHOR, true)))
    }

    @Test fun everyVideoOfThatAuthorPicksUpTheNewState() {
        val adapter = adapter()
        try {
            adapter.replace(listOf(
                video("v1", "author-1", "fixture"),
                video("v2", "author-1", "fixture"),
                video("v3", "author-2", "someone")
            ))
            adapter.applyFollowState(AuthorActivity.FollowResult("author-1", "fixture", true))
            assertTrue(adapter.items[0].authorFollowing)
            assertTrue(adapter.items[1].authorFollowing)
            assertFalse("别的作者不受影响", adapter.items[2].authorFollowing)
        } finally { adapter.releaseAll() }
    }

    @Test fun unfollowingOnTheAuthorPageAlsoPropagates() {
        val adapter = adapter()
        try {
            adapter.replace(listOf(video("v1", "author-1", "fixture").apply { authorFollowing = true }))
            adapter.applyFollowState(AuthorActivity.FollowResult("author-1", "fixture", false))
            assertFalse(adapter.items.single().authorFollowing)
        } finally { adapter.releaseAll() }
    }

    @Test fun aBoundCardRendersTheNewFollowState() {
        val adapter = adapter()
        try {
            adapter.replace(listOf(video("v1", "author-1", "fixture")))
            val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), 0)
            adapter.onBindViewHolder(holder, 0)
            adapter.applyFollowState(AuthorActivity.FollowResult("author-1", "fixture", true))
            val button = holder.itemView.findViewById<AuthorFollowButton>(R.id.authorFollow)
            assertEquals("已关注", button.text.toString())
        } finally { adapter.releaseAll() }
    }
}
