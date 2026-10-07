package com.ling.iwaraflow

import android.os.Looper
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchVariantsTest {
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun invoke(activity: SearchActivity, method: String, vararg args: Any) = activity.javaClass.declaredMethods
        .single { it.name == method }.apply { isAccessible = true }.invoke(activity, *args)
    @Suppress("UNCHECKED_CAST")
    private fun results(activity: SearchActivity): List<VideoItem> = field(field(activity, "videoTab")!!, "items") as List<VideoItem>
    private fun options(activity: SearchActivity): AlertDialog {
        invoke(activity, "showMatchOptions")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    @Suppress("UNCHECKED_CAST")
    private fun fixture(failOriginal: Boolean = false, deferred: MutableList<() -> Unit>? = null, block: (SearchActivity, List<Pair<String, Int>>) -> Unit) {
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get()
        val api = mock(IwaraApi::class.java)
        val gate = mock(PlayableVideoGate::class.java)
        for ((name, replacement) in listOf("api" to api, "gate" to gate)) {
            val property = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
            (property.get(activity) as? IwaraApi)?.close()
            (property.get(activity) as? PlayableVideoGate)?.close()
            property.set(activity, replacement)
        }
        val requests = mutableListOf<Pair<String, Int>>()
        doAnswer { call ->
            (call.arguments[2] as (List<VideoItem>) -> Unit)(call.arguments[0] as List<VideoItem>)
            null
        }.`when`(gate).inspectAll(anyList(), anyString(), any<(List<VideoItem>) -> Unit>() ?: {})
        doAnswer { call ->
            val word = call.arguments[0] as String; val page = call.arguments[1] as Int
            requests += word to page
            val rows = when {
                page != 0 -> emptyList()
                word == "长风" -> List(24) { VideoItem("zh-$it", "长风 作品 $it", "author", emptyList(), 0) }
                word == "changfeng" -> listOf(VideoItem("en", "Changfeng animation", "author", emptyList(), 0))
                word == "長風" -> listOf(VideoItem("ja", "長風 アニメーション", "author", emptyList(), 0))
                else -> emptyList()
            }
            val response = if (failOriginal && word == "长风") Result.failure(IllegalStateException("fixture failure")) else Result.success(rows)
            val callback = call.arguments[4] as (Result<List<VideoItem>>) -> Unit
            if (word == "changfeng" && deferred != null) deferred += { callback(response) }
            else callback(response)
            null
        }.`when`(api).searchVideos(anyString(), anyInt(), anyInt(), anyString(), any<(Result<List<VideoItem>>) -> Unit>() ?: {})
        try {
            invoke(activity, "runSearch", "长风")
            shadowOf(Looper.getMainLooper()).idle()
            block(activity, requests)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun loadsEachSelectedLanguageBeforePagingOriginalAgain() = fixture { activity, requests ->
        assertEquals(listOf("长风" to 0, "changfeng" to 0, "長風" to 0), requests.take(3))
        assertEquals(26, results(activity).size)
        assertTrue(results(activity).any { it.id == "en" })
        assertTrue(results(activity).any { it.id == "ja" })
        assertFalse((field(activity, "searchPlan") as SearchQuery).seeds.contains("Long Wind"))
    }
    @Test fun failureOfOneSpellingDoesNotBlockOtherSelectedLanguages() = fixture(true) { activity, requests ->
        assertEquals(setOf("长风", "changfeng", "長風"), requests.map { it.first }.toSet())
        assertEquals(setOf("en", "ja"), results(activity).map { it.id }.toSet())
        assertTrue(activity.findViewById<android.widget.TextView>(R.id.searchStatus).text.contains("加载失败"))
    }
    @Test fun disabledInFlightSpellingCannotReappearWhenItsOldResponseArrives() {
        val deferred = mutableListOf<() -> Unit>()
        fixture(deferred = deferred) { activity, _ ->
            assertEquals(1, deferred.size)
            val dialog = options(activity)
            dialog.listView.performItemClick(null, 0, 0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            deferred.single().invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(results(activity).any { it.id == "en" })
            assertTrue(results(activity).any { it.id == "ja" })
        }
    }
    @Test fun disabledSpellingStaysUncheckedAndCanBeEnabledAgain() = fixture { activity, requests ->
        val dialog = options(activity)
        assertTrue(dialog.listView.isItemChecked(0))
        dialog.listView.performItemClick(null, 0, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(results(activity).any { it.id == "en" })
        val reopened = options(activity)
        assertEquals(2, reopened.listView.count)
        assertFalse(reopened.listView.isItemChecked(0))
        reopened.listView.performItemClick(null, 0, 0)
        reopened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(results(activity).any { it.id == "en" })
        assertEquals(2, requests.count { it.first == "changfeng" })
    }
}
