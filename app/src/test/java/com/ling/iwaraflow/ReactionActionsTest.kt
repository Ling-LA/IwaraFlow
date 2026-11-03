package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReactionActionsTest {
    private class Card(savedInitially: Boolean = false) : Closeable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup()
        private val context = activity.get()
        private val prefs = AppPrefs(context).apply { accountId = "reaction-actions" }
        val item = VideoItem("actions", "Fixture", "Fixture author", listOf("fixture"), 10)
        val likes = ArrayList<Boolean>()
        val favoriteActions = ArrayList<String>()
        val signals = ArrayList<String>()
        val history = HistoryStore(context)
        private var saved = savedInitially
        private val api = mock(IwaraApi::class.java) { call ->
            when (call.method.name) {
                "isLoggedIn" -> true
                "likeVideo" -> {
                    likes += call.getArgument<Boolean>(1)
                    call.getArgument<(Result<Unit>) -> Unit>(2)(Result.success(Unit))
                    null
                }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val favorites = mock(PlaylistFavorites::class.java) { call ->
            when (call.method.name) {
                "isFavorite" -> saved
                "listen" -> Closeable { }
                "add", "remove" -> {
                    favoriteActions += call.method.name
                    val desired = call.method.name == "add"
                    val changed = desired != saved
                    saved = desired
                    call.getArgument<(Result<PlaylistFavorites.Change>) -> Unit>(1)(
                        Result.success(PlaylistFavorites.Change(saved, changed)))
                    null
                }
                "showPicker" -> { favoriteActions += "picker"; null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val adapter = VideoAdapter(api, history, prefs, mock(MediaPreloadCache::class.java),
            { _, _ -> }, {}, {}, {}, onSignal = { _, action -> signals += action }, favoriteController = favorites)
        val holder: VideoAdapter.Holder
        val like: View get() = holder.itemView.findViewById(R.id.like)
        val favorite: View get() = holder.itemView.findViewById(R.id.favorite)
        init {
            prefs.likeStates.confirm(prefs.likeStates.snapshot(), item.id, true, item.likes)
            adapter.replace(listOf(item))
            holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            context.setContentView(holder.itemView)
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            holder.itemView.layout(0, 0, 1080, 1920)
            holder.javaClass.getDeclaredField("active").apply { isAccessible = true }.setBoolean(holder, true)
        }
        fun flush() {
            shadowOf(Looper.getMainLooper()).idle()
            val done = CountDownLatch(1)
            history.post { done.countDown() }
            assertTrue("Preference writes must finish", done.await(3, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun extras(): Int = history.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM extra_like_signals WHERE account_id=? AND video_id=?",
            arrayOf("reaction-actions", item.id)).use { it.moveToFirst(); it.getInt(0) }
        fun doubleTap() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            holder.itemView.performClick(); holder.itemView.performClick()
            flush()
        }
        fun combinedHold() = holder.javaClass.getDeclaredMethod("doubleReaction", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(holder, false)
        override fun close() {
            adapter.releaseAll(); history.close(); activity.pause().stop().destroy()
        }
    }

    @Test fun shortLikeTogglesWithoutExtraSignal() {
        Card().use { c ->
            c.like.performClick(); c.flush()
            assertFalse(c.item.liked)
            assertEquals(listOf(false), c.likes)
            assertEquals(9, c.item.likes)
            assertEquals(0, c.extras())
            assertFalse(c.signals.contains("extra_like"))
            c.like.performClick(); c.flush()
            assertTrue(c.item.liked)
            assertEquals(listOf(false, true), c.likes)
            assertEquals(10, c.item.likes)
            assertEquals(0, c.extras())
        }
    }

    @Test fun doubleTapOnceThenShortLikeCancels() {
        Card().use { c ->
            val before = c.history.preferenceProfile().authorWeights["fixture author"] ?: 0.0
            c.doubleTap(); c.doubleTap(); c.doubleTap()
            assertTrue(c.item.liked)
            assertEquals("Extra preference must not send another public like", emptyList<Boolean>(), c.likes)
            assertEquals(10, c.item.likes)
            assertEquals(1, c.extras())
            assertEquals(1, c.signals.count { it == "extra_like" })
            assertEquals(before + HistoryStore.EXTRA_LIKE_WEIGHT,
                c.history.preferenceProfile().authorWeights.getValue("fixture author"), 0.001)
            assertTrue(HistoryStore.EXTRA_LIKE_WEIGHT < 1.0)
            c.like.performClick(); c.flush()
            assertEquals(listOf(false), c.likes)
            assertFalse("An extra preference signal must not prevent unlike", c.item.liked)
            assertEquals(9, c.item.likes)
            assertEquals(1, c.extras())
            assertEquals(1, c.signals.count { it == "extra_like" })
            // Re-liking cannot replenish this video's once-only extra allowance.
            c.like.performClick(); c.flush(); c.doubleTap()
            assertEquals(listOf(false, true), c.likes)
            assertEquals(1, c.extras())
            assertEquals(1, c.signals.count { it == "extra_like" })
        }
    }

    @Test fun shortStarRemovesWhileHoldOnlyManages() {
        Card(savedInitially = true).use { c ->
            fun touch(action: Int) {
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, action, 15f, 15f, 0).also { c.favorite.dispatchTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
            touch(MotionEvent.ACTION_UP)
            c.flush()
            assertEquals("Releasing a completed hold must not cancel the favorite", listOf("picker"), c.favoriteActions)
            assertTrue(c.item.localFavorite)
            c.favorite.performClick(); c.flush()
            assertEquals(listOf("picker", "remove"), c.favoriteActions)
            assertFalse(c.item.localFavorite)
            assertTrue(c.signals.contains("unfavorite"))
            c.favorite.performClick(); c.flush()
            assertEquals(listOf("picker", "remove", "add"), c.favoriteActions)
            assertTrue(c.item.localFavorite)
        }
    }

    @Test fun centerHoldOnlyAddsAndDoesNotAwardExtra() {
        Card(savedInitially = true).use { c ->
            c.combinedHold(); c.flush()
            assertEquals(emptyList<Boolean>(), c.likes)
            assertEquals(listOf("add"), c.favoriteActions)
            assertTrue(c.item.liked); assertTrue(c.item.localFavorite)
            assertEquals(0, c.extras())
            assertFalse(c.signals.contains("extra_like"))
        }
    }
}
