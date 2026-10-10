package com.ling.iwaraflow

import android.os.Looper
import android.widget.CheckBox
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SearchRefreshTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(target: Any, name: String) = target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    private fun start(activity: SearchActivity) = activity.javaClass.getDeclaredMethod("runSearch", String::class.java).apply { isAccessible = true }.invoke(activity, "animation")
    private fun freshActivity() = Robolectric.buildActivity(SearchActivity::class.java).setup().also {
        field(it.get(), "originalOnly").set(it.get(), true)
        AppPrefs(it.get()).searchExcludeSeen = false
    }
    data class Request(val word: String, val page: Int, val sort: String, val respond: (Result<List<VideoItem>>) -> Unit)

    @Suppress("UNCHECKED_CAST")
    @Test fun refreshPreservesCriteriaRestartsPagesAndRejectsOldReplies() {
        RuntimeEnvironment.getApplication().getSharedPreferences(AppPrefs.FILE, 0).edit().clear().commit()
        val controller = freshActivity(); val activity = controller.get()
        val api = mock(IwaraApi::class.java); val gate = mock(PlayableVideoGate::class.java)
        val requests = mutableListOf<Request>()
        try {
            (field(activity, "api").get(activity) as IwaraApi).close(); field(activity, "api").set(activity, api)
            (field(activity, "gate").get(activity) as PlayableVideoGate).close(); field(activity, "gate").set(activity, gate)
            doAnswer { call -> (call.arguments[2] as (List<VideoItem>) -> Unit)(call.arguments[0] as List<VideoItem>); null }
                .`when`(gate).inspectAll(anyList(), anyString(), any<(List<VideoItem>) -> Unit>() ?: {})
            doAnswer { call -> requests += Request(call.arguments[0] as String, call.arguments[1] as Int, call.arguments[3] as String,
                call.arguments[4] as (Result<List<VideoItem>>) -> Unit); null }
                .`when`(api).searchVideos(anyString(), anyInt(), anyInt(), anyString(), any<(Result<List<VideoItem>>) -> Unit>() ?: {})
            start(activity)
            requests.last().respond(Result.success(List(24) { VideoItem("old-$it", "animation", "", emptyList(), 1) })); idle()
            activity.findViewById<CheckBox>(R.id.searchExcludeSeen).isChecked = true
            activity.findViewById<TextView>(R.id.sortLikes).performClick()
            requests.last().respond(Result.success(List(24) { VideoItem("liked-$it", "animation", "", emptyList(), 1) })); idle()
            activity.findViewById<TextView>(R.id.searchStatus).performClick()
            val stalePage = requests.last(); assertEquals(1, stalePage.page)
            val selected = SearchQuery(listOf(listOf("animation", "motion")))
            field(activity, "searchPlan").set(activity, selected); field(activity, "queries").set(activity, selected.seeds)
            invoke(activity, "refreshResults"); idle()
            val refresh = activity.findViewById<SwipeRefreshLayout>(R.id.searchRefresh)
            assertTrue(refresh.isRefreshing)
            assertEquals(0, requests.last().page); assertEquals(SearchSort.Key.LIKES.api, requests.last().sort)
            assertEquals(selected, field(activity, "searchPlan").get(activity))
            assertTrue(AppPrefs(activity).searchExcludeSeen)
            stalePage.respond(Result.success(listOf(VideoItem("stale", "animation", "", emptyList(), 1))))
            requests.last().respond(Result.success(listOf(VideoItem("new", "animation", "", emptyList(), 1)))); idle()
            assertEquals("motion", requests.last().word); assertTrue(refresh.isRefreshing)
            requests.last().respond(Result.failure(IllegalStateException("temporary failure"))); idle()
            assertFalse(refresh.isRefreshing)
            val state = field(activity, "videoTab").get(activity)!!
            assertEquals(listOf("new"), (field(state, "items").get(state) as List<VideoItem>).map { it.id })
            verify(api, atLeastOnce()).cancelPendingRequests()
        } finally { controller.pause().stop().destroy() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun authorsCanRefreshEmptyResultsAndRetryAfterFailure() {
        val controller = freshActivity(); val activity = controller.get(); val api = mock(IwaraApi::class.java)
        val pages = mutableListOf<Int>(); val replies = mutableListOf<(Result<List<IwaraAuthor>>) -> Unit>()
        try {
            (field(activity, "api").get(activity) as IwaraApi).close(); field(activity, "api").set(activity, api)
            doAnswer { call -> pages += call.arguments[1] as Int; replies += call.arguments[3] as (Result<List<IwaraAuthor>>) -> Unit; null }
                .`when`(api).searchUsers(anyString(), anyInt(), anyInt(), any<(Result<List<IwaraAuthor>>) -> Unit>() ?: {})
            activity.findViewById<TextView>(R.id.tabAuthors).performClick(); start(activity)
            replies.last()(Result.success(emptyList())); idle()
            val refresh = activity.findViewById<SwipeRefreshLayout>(R.id.searchRefresh)
            invoke(activity, "refreshResults"); assertTrue(refresh.isRefreshing)
            replies.last()(Result.failure(IllegalStateException("offline"))); idle(); assertFalse(refresh.isRefreshing)
            invoke(activity, "refreshResults"); replies.last()(Result.success(emptyList())); idle()
            assertFalse(refresh.isRefreshing); assertEquals(listOf(0, 0, 0), pages)
            assertEquals("AUTHORS", field(activity, "currentTab").get(activity).toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun noSubmittedSearchCannotLeaveRefreshSpinning() {
        val controller = freshActivity(); val activity = controller.get()
        try {
            val refresh = activity.findViewById<SwipeRefreshLayout>(R.id.searchRefresh)
            assertFalse(refresh.isEnabled)
            refresh.isRefreshing = true; invoke(activity, "refreshResults")
            assertFalse(refresh.isRefreshing)
        } finally { controller.pause().stop().destroy() }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun tagRefreshStaysOnTheTagEndpoint() {
        val controller = freshActivity(); val activity = controller.get(); val api = mock(IwaraApi::class.java)
        val pages = mutableListOf<Int>(); val replies = mutableListOf<(Result<List<VideoItem>>) -> Unit>()
        try {
            (field(activity, "api").get(activity) as IwaraApi).close(); field(activity, "api").set(activity, api)
            doAnswer { call -> pages += call.arguments[1] as Int; replies += call.arguments[4] as (Result<List<VideoItem>>) -> Unit; null }
                .`when`(api).getVideosByTag(anyString(), anyInt(), anyInt(), anyString(), any<(Result<List<VideoItem>>) -> Unit>() ?: {})
            activity.findViewById<TextView>(R.id.tabTags).performClick(); start(activity)
            replies.last()(Result.success(emptyList())); idle()
            invoke(activity, "refreshResults")
            replies.last()(Result.success(emptyList())); idle()
            assertEquals(listOf(0, 0), pages)
            assertFalse(activity.findViewById<SwipeRefreshLayout>(R.id.searchRefresh).isRefreshing)
            assertEquals("TAGS", field(activity, "currentTab").get(activity).toString())
        } finally { controller.pause().stop().destroy() }
    }

}
