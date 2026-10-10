package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.CheckBox
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
class PlaybackPolishTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Test fun networkHintRecoversWithinThreeSecondsAndUnderlinesOnlyTheAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val hint = NetworkQualityHint(controller.get()); controller.get().setContentView(hint)
        try {
            var clicks = 0; hint.offer("540p") { clicks++ }
            val text = hint.text as Spanned
            val underline = text.getSpans(0, text.length, UnderlineSpan::class.java).single()
            assertEquals("点击切换", text.subSequence(text.getSpanStart(underline), text.getSpanEnd(underline)).toString())
            assertEquals(0xFF285C7B.toInt(), hint.currentTextColor)
            hint.performClick(); assertEquals(1, clicks)
            hint.networkStable(true); idle(2999); assertEquals(View.VISIBLE, hint.visibility)
            idle(1); assertEquals(View.GONE, hint.visibility)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun renewedBufferingCancelsTheRecoveryCountdown() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val hint = NetworkQualityHint(controller.get()); controller.get().setContentView(hint)
        try {
            hint.offer("540p") {}; hint.networkStable(true); idle(2500)
            hint.networkStable(false); idle(4000); assertEquals(View.VISIBLE, hint.visibility)
            hint.networkStable(true); idle(3000); assertEquals(View.GONE, hint.visibility)
            hint.offer("360p") {}; hint.reset(); idle(4000); assertEquals(View.GONE, hint.visibility)
        } finally { controller.pause().stop().destroy() }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun searchFilterCanBeToggledWithoutLosingSeenResultsAndCoversBothVideoTabs() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AppPrefs.FILE, 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get()
        try {
            (field(activity, "api").get(activity) as IwaraApi).close()
            val history = field(activity, "history").get(activity) as HistoryStore
            history.markSeen("filter-seen")
            val checkbox = activity.findViewById<CheckBox>(R.id.searchExcludeSeen)
            for (name in listOf("videoTab", "tagTab")) {
                val state = field(activity, name).get(activity)!!
                (field(state, "loaded").get(state) as MutableList<VideoItem>).addAll(listOf(
                    VideoItem("filter-seen", "Seen", "", emptyList(), 1),
                    VideoItem("filter-new", "New", "", emptyList(), 1)))
            }
            checkbox.isChecked = true
            for (name in listOf("videoTab", "tagTab")) {
                val state = field(activity, name).get(activity)!!
                assertEquals(listOf("filter-new"), (field(state, "items").get(state) as List<VideoItem>).map { it.id })
                assertEquals(2, (field(state, "loaded").get(state) as List<VideoItem>).size)
            }
            assertTrue(AppPrefs(activity).searchExcludeSeen)
            checkbox.isChecked = false
            for (name in listOf("videoTab", "tagTab")) {
                val state = field(activity, name).get(activity)!!
                assertEquals(2, (field(state, "items").get(state) as List<VideoItem>).size)
            }
        } finally { controller.pause().stop().destroy() }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun seenOnlyPageLoadsTheNextPageInsteadOfStoppingAtAnEmptyResult() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AppPrefs.FILE, 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get(); val api = mock(IwaraApi::class.java); val gate = mock(PlayableVideoGate::class.java)
        try {
            (field(activity, "api").get(activity) as IwaraApi).close(); field(activity, "api").set(activity, api)
            (field(activity, "gate").get(activity) as PlayableVideoGate).close(); field(activity, "gate").set(activity, gate)
            field(activity, "originalOnly").set(activity, true)
            val history = field(activity, "history").get(activity) as HistoryStore
            repeat(24) { history.markSeen("page-seen-$it") }
            activity.findViewById<CheckBox>(R.id.searchExcludeSeen).isChecked = true
            val pages = mutableListOf<Int>()
            doAnswer { call ->
                val rows = call.arguments[0] as List<VideoItem>
                (call.arguments[2] as (List<VideoItem>) -> Unit)(rows); null
            }.`when`(gate).inspectAll(anyList(), anyString(), any<(List<VideoItem>) -> Unit>() ?: {})
            doAnswer { call ->
                val page = call.arguments[1] as Int; pages += page
                val rows = if (page == 0) (0 until 24).map { VideoItem("page-seen-$it", "animation", "", emptyList(), 1) }
                    else listOf(VideoItem("page-new", "animation", "", emptyList(), 1))
                (call.arguments[4] as (Result<List<VideoItem>>) -> Unit)(Result.success(rows)); null
            }.`when`(api).searchVideos(anyString(), anyInt(), anyInt(), anyString(), any<(Result<List<VideoItem>>) -> Unit>() ?: {})
            activity.javaClass.getDeclaredMethod("runSearch", String::class.java).apply { isAccessible = true }.invoke(activity, "animation")
            idle(1)
            assertEquals(listOf(0, 1), pages)
            val state = field(activity, "videoTab").get(activity)!!
            assertEquals(listOf("page-new"), (field(state, "items").get(state) as List<VideoItem>).map { it.id })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun aboutLinksDirectlyToTheProject() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get()
        fun views(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        try {
            AboutDialog.show(activity)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            views(dialog.window!!.decorView).single { it.contentDescription == "打开 IwaraFlow GitHub 项目" }.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
            assertEquals(AboutDialog.GITHUB, intent.dataString)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun currentPreloadContinuesWithoutStableNetworkAndWithoutLookaheadEnabled() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context).apply { preloadNext = false }
        val cache = mock(MediaPreloadCache::class.java)
        `when`(cache.progress(nullable(String::class.java))).thenReturn(MediaPreloadCache.Progress())
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java), prefs, cache, { _, _ -> }, {}, {}, {})
        adapter.items += VideoItem("current", "", "", emptyList(), 1).apply { streamUrl = "https://cache.test/current" }
        adapter.items += VideoItem("next", "", "", emptyList(), 1).apply { streamUrl = "https://cache.test/next" }
        field(adapter, "playbackEnabled").set(adapter, true); field(adapter, "activePosition").set(adapter, 0)
        try {
            adapter.javaClass.getDeclaredMethod("scheduleIdlePreload", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(adapter, 0)
            idle(1)
            val order = inOrder(cache)
            order.verify(cache).keepPreloads(setOf("https://cache.test/current"))
            order.verify(cache).prefetchFull("https://cache.test/current", true)
            verify(cache, never()).prefetchFull(eq("https://cache.test/next") ?: "", anyBoolean())
            prefs.preloadNext = true; idle(1000)
            verify(cache, atLeast(2)).prefetchFull("https://cache.test/current", true)
            verify(cache, never()).prefetchFull(eq("https://cache.test/next") ?: "", anyBoolean())
        } finally { adapter.releaseAll() }
    }
}
