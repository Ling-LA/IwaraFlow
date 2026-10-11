package com.ling.iwaraflow

import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SearchLinkNavigationTest {
    private fun fixture(block: (SearchActivity, MutableList<String>) -> Unit) {
        val searches = mutableListOf<String>()
        mockConstruction(IwaraApi::class.java, withSettings().defaultAnswer { call ->
            if (call.method.name == "searchVideos") searches += call.getArgument<String>(0)
            RETURNS_DEFAULTS.answer(call)
        }).use {
            val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
            controller.get().javaClass.getDeclaredField("originalOnly").apply { isAccessible = true }
                .set(controller.get(), true)
            try { block(controller.get(), searches) }
            finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun pastedVideoShareOpensRecommendationRouteWithoutSearchingOrRepeating() = fixture { activity, searches ->
        AppPrefs(activity).autoOpenClipboardLinks = false // Explicit search input is independent of clipboard scanning.
        val input = activity.findViewById<EditText>(R.id.searchInput)
        val submit = activity.findViewById<Button>(R.id.searchSubmit)
        input.setText("标题：示例动画\n作者：Example\n链接：https://www.iwara.tv/video/shared/title?utm_source=share。")
        assertEquals("打开", submit.text.toString())
        assertNull("Typing or pasting alone must not navigate", shadowOf(activity).nextStartedActivity)
        submit.performClick()
        val launched = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivityV3::class.java.name, launched.component?.className)
        assertEquals("https://www.iwara.tv/video/shared", launched.getStringExtra(IwaraSharedLink.EXTRA_URL))
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
        assertTrue(searches.isEmpty())
        assertEquals("", activity.javaClass.getDeclaredField("query").apply { isAccessible = true }.get(activity))
        submit.performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun keyboardSearchOpensAuthorShareThroughTheExistingProfileRoute() = fixture { activity, searches ->
        val input = activity.findViewById<EditText>(R.id.searchInput)
        input.setText("作者：Example\nhttps://iwara.tv/ja/users/example")
        assertEquals("打开 Iwara 作者主页", activity.findViewById<Button>(R.id.searchSubmit).contentDescription)
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        val launched = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivityV3::class.java.name, launched.component?.className)
        assertEquals("https://www.iwara.tv/profile/example", launched.getStringExtra(IwaraSharedLink.EXTRA_URL))
        assertTrue(searches.isEmpty())
    }

    @Test fun replacingALinkWithKeywordsUsesNormalSearch() = fixture { activity, searches ->
        val input = activity.findViewById<EditText>(R.id.searchInput)
        val submit = activity.findViewById<Button>(R.id.searchSubmit)
        input.setText("https://iwara.tv/video/shared")
        input.setText("animation dance")
        assertEquals("搜索", submit.text.toString())
        submit.performClick()
        assertTrue(searches.isNotEmpty())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun unsupportedAndSpoofedLinksShowAnErrorWithoutSearchOrNavigation() = fixture { activity, searches ->
        val input = activity.findViewById<EditText>(R.id.searchInput)
        val submit = activity.findViewById<Button>(R.id.searchSubmit)
        listOf("https://iwara.tv.evil.test/video/shared", "https://iwara.tv/video/",
            "https://iwara.tv/video/%2fetc", "https://iwara.tv/search?q=test").forEach { url ->
            input.setText(url)
            submit.performClick()
            assertNotNull(input.error)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        assertTrue(searches.isEmpty())
        input.setText("animation")
        assertNull(input.error)
    }

    @Test fun authorSearchTabAlsoAcceptsVideoLinks() = fixture { activity, searches ->
        activity.findViewById<android.view.View>(R.id.tabAuthors).performClick()
        activity.findViewById<EditText>(R.id.searchInput).setText("https://iwara.tv/video/shared")
        activity.findViewById<Button>(R.id.searchSubmit).performClick()
        assertEquals("https://www.iwara.tv/video/shared",
            shadowOf(activity).nextStartedActivity.getStringExtra(IwaraSharedLink.EXTRA_URL))
        assertTrue(searches.isEmpty())
    }
}
