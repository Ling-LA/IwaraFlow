package com.ling.iwaraflow

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchMatchingIntegrationTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun titleResultsValidateAllTermsBeforePlaybackInspection() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val activity = controller.get()
        val api = mock(IwaraApi::class.java)
        val gate = mock(PlayableVideoGate::class.java)
        val apiField = SearchActivity::class.java.getDeclaredField("api").apply { isAccessible = true }
        (apiField.get(activity) as IwaraApi).close(); apiField.set(activity, api)
        val gateField = SearchActivity::class.java.getDeclaredField("gate").apply { isAccessible = true }
        (gateField.get(activity) as PlayableVideoGate).close(); gateField.set(activity, gate)
        val inspected = mutableListOf<String>()
        doAnswer { call ->
            val items = call.arguments[0] as List<VideoItem>
            inspected += items.map { it.id }
            (call.arguments[2] as (List<VideoItem>) -> Unit)(items)
            null
        }.`when`(gate).inspectAll(anyList(), anyString(), any<(List<VideoItem>) -> Unit>() ?: {})
        doAnswer { call ->
            val rows = listOf(
                VideoItem("match", "Clara 原神", "author", emptyList(), 0),
                VideoItem("one-term", "Clara", "author", listOf("genshin_impact"), 0),
                VideoItem("wrong-field", "Unrelated", "Clara 原神", emptyList(), 0)
            )
            (call.arguments[4] as (Result<List<VideoItem>>) -> Unit)(Result.success(rows))
            null
        }.`when`(api).searchVideos(anyString(), anyInt(), anyInt(), anyString(),
            any<(Result<List<VideoItem>>) -> Unit>() ?: {})
        try {
            SearchActivity::class.java.getDeclaredMethod("runSearch", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "克拉拉 原神")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(inspected.isNotEmpty())
            assertEquals(setOf("match"), inspected.toSet())
        } finally { controller.pause().stop().destroy() }
    }
}
