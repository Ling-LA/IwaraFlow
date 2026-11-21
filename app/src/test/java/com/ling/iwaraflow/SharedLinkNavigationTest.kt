package com.ling.iwaraflow

import android.content.Intent
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SharedLinkNavigationTest {
    private fun intent(url: String) = Intent(RuntimeEnvironment.getApplication(), MainActivityV3::class.java)
        .putExtra(IwaraSharedLink.EXTRA_URL, url)
    private fun item(id: String) = VideoItem(id, id, "Fixture", emptyList(), 0)
    private fun adapter(activity: MainActivityV3) = activity.javaClass.getDeclaredField("adapter")
        .apply { isAccessible = true }.get(activity) as VideoAdapter

    @Test fun sharedVideoStaysFirstAndSwipingAdvancesIntoNormalRecommendations() {
        var videoResult: ((Result<VideoItem>) -> Unit)? = null
        var recommendations: ((Result<List<VideoItem>>) -> Unit)? = null
        mockConstruction(IwaraApi::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name == "getVideo") videoResult = invocation.getArgument(1)
            RETURNS_DEFAULTS.answer(invocation)
        }).use {
            mockConstruction(RecommendationEngine::class.java, withSettings().defaultAnswer { invocation ->
                if (invocation.method.name == "load") recommendations = invocation.getArgument(1)
                RETURNS_DEFAULTS.answer(invocation)
            }).use {
                mockConstruction(PlayableVideoGate::class.java, withSettings().defaultAnswer { invocation ->
                    if (invocation.method.name == "filterPlayable") {
                        invocation.getArgument<(List<VideoItem>) -> Unit>(5)(invocation.getArgument(0))
                    }
                    RETURNS_DEFAULTS.answer(invocation)
                }).use {
                    mockConstruction(UpdateManager::class.java).use {
                        val controller = Robolectric.buildActivity(MainActivityV3::class.java,
                            intent("https://iwara.tv/video/shared")).setup()
                        videoResult!!(Result.success(item("shared")))
                        assertNotNull("Opening a shared video must request recommendations", recommendations)
                        recommendations!!(Result.success(listOf(item("shared"), item("recommended-1"), item("recommended-2"))))
                        assertEquals(listOf("shared", "recommended-1", "recommended-2"), adapter(controller.get()).items.map { it.id })
                        val pager = controller.get().findViewById<ViewPager2>(R.id.pager)
                        pager.setCurrentItem(1, false)
                        assertEquals("recommended-1", adapter(controller.get()).items[pager.currentItem].id)
                        controller.pause().stop().destroy()
                    }
                }
            }
        }
    }

    @Test fun aNewSharedLinkInvalidatesThePreviousVideoResponse() {
        val callbacks = mutableListOf<(Result<VideoItem>) -> Unit>()
        mockConstruction(IwaraApi::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name == "getVideo") callbacks += invocation.getArgument<(Result<VideoItem>) -> Unit>(1)
            RETURNS_DEFAULTS.answer(invocation)
        }).use {
            mockConstruction(RecommendationEngine::class.java).use {
                mockConstruction(UpdateManager::class.java).use {
                    val controller = Robolectric.buildActivity(MainActivityV3::class.java, intent("https://iwara.tv/video/first")).setup()
                    controller.pause().newIntent(intent("https://iwara.tv/video/second")).resume()
                    callbacks[1](Result.success(item("second")))
                    callbacks[0](Result.success(item("first")))
                    assertEquals(listOf("second"), adapter(controller.get()).items.map { it.id })
                    assertFalse(controller.get().intent.hasExtra(IwaraSharedLink.EXTRA_URL))
                    controller.pause().stop().destroy()
                }
            }
        }
    }

    @Test fun shortRecommendationHeadPrefetchesAndAppendsWithoutAnotherPageSelection() {
        val loads = mutableListOf<(Result<List<VideoItem>>) -> Unit>()
        var first: ((List<VideoItem>) -> Unit)? = null
        lateinit var finish: (List<VideoItem>) -> Unit
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(RecommendationEngine::class.java, withSettings().defaultAnswer { call ->
                if (call.method.name == "load") loads += call.getArgument<(Result<List<VideoItem>>) -> Unit>(1)
                if (call.method.name == "getRecallNote") "" else RETURNS_DEFAULTS.answer(call)
            }).use {
                mockConstruction(PlayableVideoGate::class.java, withSettings().defaultAnswer { call ->
                    if (call.method.name == "filterPlayable") {
                        first = call.getArgument(4)
                        finish = call.getArgument(5)
                    }
                    RETURNS_DEFAULTS.answer(call)
                }).use {
                    mockConstruction(UpdateManager::class.java).use {
                        val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                        try {
                            val initial = listOf(item("first"))
                            loads.single()(Result.success(initial))
                            first!!(initial)
                            finish(initial)
                            assertEquals("A single card cannot trigger a second page selection", 2, loads.size)
                            val next = (1..7).map { item("next-" + it) }
                            loads.last()(Result.success(next))
                            first!!(next.take(3))
                            val adapter = adapter(controller.get())
                            assertEquals(initial + next.take(3), adapter.items)
                            finish(next)
                            assertEquals(initial + next, adapter.items)
                            assertEquals(2, loads.size)
                        } finally { controller.pause().stop().destroy() }
                    }
                }
            }
        }
    }

    @Test fun authorLinkLaunchesTheMatchingProfile() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(RecommendationEngine::class.java).use {
                mockConstruction(UpdateManager::class.java).use {
                    val controller = Robolectric.buildActivity(MainActivityV3::class.java, intent("https://iwara.tv/profile/alice")).setup()
                    val launched = shadowOf(controller.get()).nextStartedActivity
                    assertEquals(AuthorActivity::class.java.name, launched.component?.className)
                    assertEquals("alice", launched.getStringExtra(AuthorActivity.EXTRA_USERNAME))
                    controller.pause().stop().destroy()
                }
            }
        }
    }
}
