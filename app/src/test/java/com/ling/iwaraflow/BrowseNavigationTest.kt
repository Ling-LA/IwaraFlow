package com.ling.iwaraflow

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowseNavigationTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)

    @Before fun skipUnrelatedFirstLaunchOverlays() {
        RuntimeEnvironment.getApplication().getSharedPreferences(AppPrefs.FILE, 0).edit()
            .putBoolean(PlaybackGuide.key(false), true).putBoolean(PlaybackGuide.key(true), true)
            .putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).commit()
    }

    @Test fun categoryOpensAsCardsAndReturningFromSettingsDoesNotAutoplay() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val activity = controller.get()
                try {
                    activity.findViewById<View>(R.id.tabLatest).performClick()
                    assertTrue(field(activity, "showingBrowseGrid").getBoolean(activity))
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.pager).visibility)
                    controller.pause().stop().restart().start().resume()
                    val adapter = field(activity, "adapter").get(activity) as VideoAdapter
                    assertFalse(field(adapter, "playbackEnabled").getBoolean(adapter))
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun cardOpensOrderedSwipeFeedAndBackKeepsSameCategoryItems() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val activity = controller.get()
                try {
                    activity.findViewById<View>(R.id.tabTrending).performClick()
                    @Suppress("UNCHECKED_CAST")
                    val states = field(activity, "browseStates").get(activity) as MutableMap<String, BrowseCatalogState>
                    val state = states.getValue("trending")
                    val videos = (0..7).map { VideoItem("v-$it", "Video $it", "Example", emptyList(), it) }
                    state.accept(0, videos, videos.size, 28)
                    invoke(activity, "showBrowseGrid")
                    invoke(activity, "openBrowseVideo", 3)
                    val pager = activity.findViewById<ViewPager2>(R.id.pager)
                    assertFalse(field(activity, "showingBrowseGrid").getBoolean(activity))
                    assertEquals(3, pager.currentItem)
                    assertEquals(videos.map { it.id }, (pager.adapter as VideoAdapter).items.map { it.id })
                    pager.setCurrentItem(4, false)
                    assertFalse("Fixture must not dismiss first-launch onboarding instead of navigating", PlaybackGuide.isActive(activity))
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertFalse(activity.isFinishing)
                    assertTrue(field(activity, "showingBrowseGrid").getBoolean(activity))
                    assertSame(state, states.getValue("trending"))
                    assertEquals(8, state.items.size)
                    assertFalse(field(pager.adapter!!, "playbackEnabled").getBoolean(pager.adapter))
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun guideTemporarilyRevealsPlayerAndRestoresPausedGrid() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val activity = controller.get()
                try {
                    activity.findViewById<View>(R.id.tabPopular).performClick()
                    @Suppress("UNCHECKED_CAST")
                    val states = field(activity, "browseStates").get(activity) as MutableMap<String, BrowseCatalogState>
                    val video = VideoItem("guide", "Neutral fixture", "Example", emptyList(), 1, resumePlayWhenReady = false)
                    states.getValue("popularity").accept(0, listOf(video), 1, 28)
                    invoke(activity, "showBrowseGrid")
                    PlaybackGuide.show(activity, false)
                    val adapter = field(activity, "adapter").get(activity) as VideoAdapter
                    assertFalse(field(activity, "showingBrowseGrid").getBoolean(activity))
                    assertTrue(field(adapter, "guideVisible").getBoolean(adapter))
                    assertSame(video, adapter.items.single())
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertFalse(PlaybackGuide.isActive(activity))
                    assertTrue(field(activity, "showingBrowseGrid").getBoolean(activity))
                    assertFalse(field(adapter, "playbackEnabled").getBoolean(adapter))
                    assertFalse(video.resumePlayWhenReady)
                } finally { PlaybackGuide.dismiss(activity); controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun guideInvalidatesPendingGridLoadAndRetriesItAfterDismissal() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val activity = controller.get()
                try {
                    activity.findViewById<View>(R.id.tabLatest).performClick()
                    val requestSerial = field(activity, "requestSerial")
                    val beforeGuide = requestSerial.getInt(activity)
                    assertTrue(field(activity, "loadingMore").getBoolean(activity))
                    PlaybackGuide.show(activity, false)
                    assertTrue(requestSerial.getInt(activity) > beforeGuide)
                    assertFalse(field(activity, "loadingMore").getBoolean(activity))
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertTrue(field(activity, "showingBrowseGrid").getBoolean(activity))
                    assertTrue("First category page must be retried after the guide", field(activity, "loadingMore").getBoolean(activity))
                    val adapter = field(activity, "adapter").get(activity) as VideoAdapter
                    assertFalse(field(adapter, "playbackEnabled").getBoolean(adapter))
                } finally { PlaybackGuide.dismiss(activity); controller.pause().stop().destroy() }
            }
        }
    }
}
