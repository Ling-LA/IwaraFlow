package com.ling.iwaraflow

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class TripleReactionTest {
    @org.junit.Before fun manualFrameClock() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    private fun idle(ms: Long = 32) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private class Fixture(val api: IwaraApi, val holder: VideoAdapter.Holder, val item: VideoItem) {
        var followReply: ((Result<Unit>) -> Unit)? = null
        val likes = mutableListOf<Boolean>()
        val follows = mutableListOf<Pair<String, Boolean>>()
        fun hold(view: View = holder.itemView, ms: Long = 1500) {
            touch(view, MotionEvent.ACTION_DOWN); idle(ms); touch(view, MotionEvent.ACTION_UP); idle()
        }
        private fun idle(ms: Long = 1) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        private fun touch(view: View, action: Int) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0).also {
                // Exercise the installed gesture listener; child player controls are tested separately.
                shadowOf(view).onTouchListener.onTouch(view, it); it.recycle()
            }
        }
    }
    private fun fixture(liked: Boolean = false, following: Boolean = false, block: (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        AppPrefs(context).accountId = "viewer"
        context.getSharedPreferences(VideoLikeStore.FILE, 0).edit().clear().commit()
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(true)
        val history = HistoryStore(context)
        val adapter = VideoAdapter(api, history, AppPrefs(context), mock(MediaPreloadCache::class.java),
            { _, _ -> }, {}, {}, {}, favoriteController = acknowledgedFavoritesFixture())
        val item = VideoItem("triple", "Neutral fixture", "Creator", emptyList(), 5,
            liked = liked, authorId = "creator", authorFollowing = following)
        adapter.replace(listOf(item))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        val host = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        host.get().setContentView(holder.itemView)
        idle(32)
        adapter.setFullscreen(true)
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY))
        holder.itemView.layout(0, 0, 900, 450)
        idle(100) // Finish the host's requested traversal before beginning a timed gesture.
        holder.javaClass.getDeclaredField("active").apply { isAccessible = true }.set(holder, true)
        val fixture = Fixture(api, holder, item)
        doAnswer { call ->
            fixture.likes += call.getArgument<Boolean>(1)
            call.getArgument<(Result<Unit>) -> Unit>(2)(Result.success(Unit)); null
        }.`when`(api).likeVideo(anyString(), anyBoolean(), any<(Result<Unit>) -> Unit>() ?: {})
        doAnswer { call ->
            fixture.follows += call.getArgument<String>(0) to call.getArgument<Boolean>(1)
            fixture.followReply = call.getArgument(2); null
        }.`when`(api).followUser(anyString(), anyBoolean(), any<(Result<Unit>) -> Unit>() ?: {})
        try { block(fixture) } finally { adapter.releaseAll(); history.close(); host.pause().stop().destroy() }
    }
    @Test fun screenHoldAddsLikeFavoriteAndFollowAndDoesNotRepeat() = fixture { f ->
        f.hold(ms = 2200)
        assertEquals(listOf(true), f.likes); assertTrue(f.item.liked); assertTrue(f.item.localFavorite)
        assertEquals(listOf("creator" to true), f.follows)
        f.followReply!!(Result.success(Unit)); idle()
        assertTrue(f.item.authorFollowing)
        assertEquals("已关注", f.holder.itemView.findViewById<AuthorFollowButton>(R.id.authorFollow).text.toString())
        f.hold(); assertEquals(1, f.follows.size); assertEquals(1, f.likes.size)
    }
    @Test fun likeButtonHoldKeepsItsDoubleActionWithoutFollowing() = fixture { f ->
        f.hold(f.holder.itemView.findViewById(R.id.like))
        assertTrue(f.item.liked); assertTrue(f.item.localFavorite); assertTrue(f.follows.isEmpty())
    }
    @Test fun knownLikeAndFollowAreNeverToggledOff() = fixture(liked = true, following = true) { f ->
        f.hold()
        assertTrue(f.likes.isEmpty()); assertTrue(f.follows.isEmpty())
        assertTrue(f.item.liked); assertTrue(f.item.authorFollowing); assertTrue(f.item.localFavorite)
    }
    @Test fun failedFollowKeepsTheConfirmedLikeAndFavorite() = fixture { f ->
        f.hold(); f.followReply!!(Result.failure(java.io.IOException("offline"))); idle()
        assertTrue(f.item.liked); assertTrue(f.item.localFavorite); assertFalse(f.item.authorFollowing)
        assertTrue(f.holder.itemView.findViewById<View>(R.id.authorFollow).isEnabled)
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("关注失败"))
    }
    @Test fun aLateFollowReplyCannotAffectAnotherAccount() = fixture { f ->
        f.hold(); AppPrefs(RuntimeEnvironment.getApplication()).accountId = "another-viewer"
        f.followReply!!(Result.success(Unit)); idle()
        assertFalse(f.item.authorFollowing)
    }
    @Test fun releasingBeforeTheDeadlineDoesNothing() = fixture { f ->
        f.hold(ms = 900); idle(2000)
        assertTrue(f.likes.isEmpty()); assertTrue(f.follows.isEmpty()); assertFalse(f.item.localFavorite)
    }
    @Test fun ownVideoIsLikedAndSavedWithoutFollowingSelf() = fixture { f ->
        f.item.authorId = "viewer"; f.hold()
        assertTrue(f.item.localFavorite); assertTrue(f.item.liked); assertTrue(f.follows.isEmpty())
        assertFalse(f.item.authorFollowing)
    }
    @Test fun serverFollowStateAvoidsAnUnnecessaryWrite() = fixture { f ->
        f.item.authorUsername = "creator-name"
        doAnswer { call ->
            call.getArgument<(Result<IwaraAuthor>) -> Unit>(1)(Result.success(IwaraAuthor("creator", "Creator", "creator-name", following = true)))
            null
        }.`when`(f.api).getAuthorProfile(anyString(), any<(Result<IwaraAuthor>) -> Unit>() ?: {})
        f.hold(); idle()
        assertTrue(f.item.authorFollowing); assertTrue(f.follows.isEmpty())
    }
    @Test fun aPendingManualFollowIsNotOverwrittenByTheTriple() = fixture { f ->
        f.holder.itemView.findViewById<View>(R.id.authorFollow).isEnabled = false
        f.hold(); idle()
        assertTrue(f.item.liked); assertTrue(f.item.localFavorite); assertTrue(f.follows.isEmpty())
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("正在处理中"))
    }
}
