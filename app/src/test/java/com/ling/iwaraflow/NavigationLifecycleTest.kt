package com.ling.iwaraflow

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
class NavigationLifecycleTest {
    @Test fun mainRestartsAfterEveryChildWithoutFinishing() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val main = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                repeat(4) {
                    main.pause().stop().restart().start().resume().visible()
                    assertFalse(main.get().isFinishing)
                }
                main.pause().stop().destroy()
            }
        }
    }

    @Test fun authorBackFinishesOnlyAuthorAndDoesNotStartHome() {
        mockConstruction(IwaraApi::class.java).use {
            val context = RuntimeEnvironment.getApplication()
            val intent = Intent(context, AuthorActivity::class.java)
                .putExtra(AuthorActivity.EXTRA_ID, "test-author")
            val controller = Robolectric.buildActivity(AuthorActivity::class.java, intent).setup()
            controller.get().onBackPressedDispatcher.onBackPressed()
            assertTrue(controller.get().isFinishing)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            controller.pause().stop().destroy()
        }
    }

    @Test fun authorSystemBackLeavesVideoForListBeforeFinishingActivity() {
        mockConstruction(IwaraApi::class.java).use {
            val intent = Intent(RuntimeEnvironment.getApplication(), AuthorActivity::class.java)
                .putExtra(AuthorActivity.EXTRA_ID, "test-author")
            val controller = Robolectric.buildActivity(AuthorActivity::class.java, intent).setup()
            val activity = controller.get()
            activity.javaClass.getDeclaredField("inFeed").apply { isAccessible = true }.setBoolean(activity, true)
            activity.findViewById<View>(R.id.authorListPage).visibility = View.GONE
            activity.findViewById<View>(R.id.authorFeedPage).visibility = View.VISIBLE
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.authorListPage).visibility)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            controller.pause().stop().destroy()
        }
    }

    @Test fun constructingAuthorAdapterDoesNotTakeOwnershipFromVisibleHome() {
        val context = RuntimeEnvironment.getApplication()
        fun adapter() = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        val home = adapter()
        home.resumeActive()
        val author = adapter()
        val enabled = home.javaClass.getDeclaredField("playbackEnabled").apply { isAccessible = true }
        assertTrue(enabled.getBoolean(home))
        author.resumeActive()
        assertFalse(enabled.getBoolean(home))
        home.releaseAll()
        author.releaseAll()
    }

    @Test fun pausedAdapterMustNotRestartWhenRecyclerRebinds() {
        val context = RuntimeEnvironment.getApplication()
        val cache = mock(MediaPreloadCache::class.java)
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), cache, { _, _ -> }, {}, {}, {})
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        adapter.replace(listOf(VideoItem("test-video", "Fixture", "Fixture", emptyList(), 0)))
        adapter.setActive(0)
        adapter.pauseAll()
        adapter.setActive(0) // A late ViewPager callback is selection, not foreground permission.
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        assertNull("A background rebind must not create a player or steal playback",
            holder.itemView.findViewById<PlayerView>(R.id.playerView).player)
        adapter.releaseAll()
    }

    @Test fun recycledHolderPlayerIsReleasedOnPause() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(context), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(VideoItem("test-video", "Fixture", "Fixture", emptyList(), 0)))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onViewRecycled(holder)
        adapter.onBindViewHolder(holder, 0)
        holder.release()
        val player = mock(ExoPlayer::class.java)
        holder.javaClass.getDeclaredField("player").apply { isAccessible = true }.set(holder, player)
        holder.javaClass.getDeclaredField("active").apply { isAccessible = true }.setBoolean(holder, true)
        adapter.pauseAll()
        verify(player).release()
        holder.release()
        adapter.releaseAll()
    }
}
