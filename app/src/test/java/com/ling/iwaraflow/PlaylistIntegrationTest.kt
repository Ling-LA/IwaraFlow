package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.widget.CheckBox
import android.widget.FrameLayout
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
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistIntegrationTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)

    @Test fun delayedLikeFinishesOriginalFavoriteWithoutLockingTheReboundCard() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context).apply { accountId = "playlist-integration" }
        lateinit var likeResponse: (Result<Unit>) -> Unit
        val api = mock(IwaraApi::class.java) { call ->
            when (call.method.name) {
                "isLoggedIn" -> true
                "likeVideo" -> { likeResponse = call.getArgument(2); null }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        val savedIds = mutableListOf<String>()
        val favorites = mock(PlaylistFavorites::class.java) { call ->
            when (call.method.name) {
                "listen" -> Closeable { }
                "add" -> { savedIds += call.getArgument<VideoItem>(0).id; null }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        val history = HistoryStore(context)
        val adapter = VideoAdapter(api, history, prefs, mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}, favoriteController = favorites)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val original = VideoItem("original", "Original", "Author", emptyList(), 10)
            val next = original.copy(id = "next", title = "Next")
            adapter.replace(listOf(original, next))
            val holder = adapter.onCreateViewHolder(FrameLayout(activity.get()), 0)
            activity.get().setContentView(holder.itemView)
            adapter.onBindViewHolder(holder, 0)
            field(holder, "active").setBoolean(holder, true)
            invoke(holder, "doubleReaction", false)
            // Reuse the holder while the original remote like has not completed.
            adapter.onBindViewHolder(holder, 1)
            likeResponse(Result.success(Unit))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("original"), savedIds)
            assertFalse("Finishing an old double action must not lock the new video's favorite", field(holder, "favoriteBusy").getBoolean(holder))
            holder.itemView.findViewById<android.view.View>(R.id.favorite).performClick()
            assertEquals(listOf("original", "next"), savedIds)
        } finally { adapter.releaseAll(); history.close(); activity.pause().stop().destroy() }
    }

    @Test fun likedHistoryOnlyReloadsForMembershipChangesNotCountRefreshes() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context).apply { accountId = "history-filter-integration" }
        val likes = prefs.likeStates
        likes.confirm(likes.snapshot(), "history-like", true, 10)
        mockConstruction(IwaraApi::class.java).use {
            val controller = Robolectric.buildActivity(SavedVideosActivity::class.java).setup()
            val activity = controller.get()
            try {
                activity.window.decorView.findViewWithTag<CheckBox>("history_liked_only").isChecked = true
                shadowOf(Looper.getMainLooper()).idle()
                val before = field(activity, "listSerial").getInt(activity)
                likes.confirm(likes.snapshot(), "history-like", true, 11)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Public count refresh must not restart the history query", before, field(activity, "listSerial").getInt(activity))
                likes.confirm(likes.snapshot(), "history-like", false, 10)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("An actual unlike must refresh the filtered history", field(activity, "listSerial").getInt(activity) > before)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
