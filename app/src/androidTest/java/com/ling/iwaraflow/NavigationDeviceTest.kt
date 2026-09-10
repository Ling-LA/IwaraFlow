package com.ling.iwaraflow

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Activity stack and real Media3 players; fixtures never require an Iwara account. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Volatile private var resumed: Activity? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
    private fun awaitActivity(type: Class<out Activity>): Activity {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            resumed?.let { activity ->
                var ready = false
                main { ready = activity.javaClass == type && !activity.isFinishing && activity.hasWindowFocus() }
                // onResume precedes the window transition; injecting Back before focus is restored
                // would send it to the finishing child rather than the page a user can interact with.
                if (ready) return activity
            }
            SystemClock.sleep(30)
        }
        val context = instrumentation.targetContext
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        var focused = false
        main { focused = resumed?.hasWindowFocus() == true }
        throw AssertionError("Expected focused ${type.simpleName}, got ${resumed?.javaClass?.simpleName}; " +
            "focus=$focused interactive=${power.isInteractive} keyguard=${keyguard.isKeyguardLocked}")
    }

    private fun back(activity: Activity, buttonId: Int) {
        when (InstrumentationRegistry.getArguments().getString("backMode", "button")) {
            "key" -> instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            "gesture" -> {
                val metrics = instrumentation.targetContext.resources.displayMetrics
                val y = metrics.heightPixels / 2
                val endX = (metrics.widthPixels * 0.6).toInt()
                // Use Android's input tool so the gesture has the same pointer metadata as a
                // touchscreen swipe, including the system-owned edge outside the app window.
                val command = "input touchscreen swipe 5 $y $endX $y 400"
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
                    it.readBytes()
                }
            }
            else -> main { activity.findViewById<View>(buttonId).performClick() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun playAuthorFixture(author: AuthorActivity, mediaFile: File) {
        main {
            val video = VideoItem("author-navigation", "Author fixture", "Fixture author", emptyList(), 0,
                sources = listOf(VideoSource("fixture", fixtureUri(mediaFile), 1)))
            @Suppress("UNCHECKED_CAST")
            (field(author, "playableWorks") as MutableList<VideoItem>).add(video)
            invoke(author, "openWork", video)
        }
        instrumentation.waitForIdleSync()
        playFixture(author, R.id.authorPager, mediaFile)
    }

    /** ViewPager2 binds the first card on a later frame, so idle alone does not guarantee a holder. */
    private fun awaitFixturePlayer(activity: Activity, pagerId: Int): ExoPlayer {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var bound: ExoPlayer? = null
            main {
                val recycler = activity.findViewById<ViewPager2>(pagerId).getChildAt(0) as RecyclerView
                val holder = recycler.findViewHolderForAdapterPosition(0)
                if (holder != null) bound = field(holder, "player") as? ExoPlayer
            }
            bound?.let { return it }
            SystemClock.sleep(30)
        }
        throw AssertionError("Fixture card never bound a player")
    }

    /**
     * 卡死 watchdog 在时间轴不前进时会用条目自己的源重建播放器。给的是不可达地址时，
     * 重建必然失败并把播放器打回 IDLE——这正是这条用例在慢机器上失败的原因。
     * 所以条目的源就用同一个本地样片，重建后仍然放得出来。
     */
    private fun fixtureUri(mediaFile: File): String = android.net.Uri.fromFile(mediaFile).toString()

    private fun playFixture(activity: Activity, pagerId: Int, mediaFile: File) {
        // The card's player is owned by the app: a rebind releases it and the stall watchdog
        // rebuilds it, and either leaves the instance this test grabbed sitting in IDLE forever.
        // So re-take the card's current player and re-arm whenever that happens.
        var player = awaitFixturePlayer(activity, pagerId)
        var armedAt = 0L
        fun arm() {
            main {
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(mediaFile)))
                player.prepare()
                player.seekTo(1_500)
                player.play()
            }
            armedAt = SystemClock.uptimeMillis()
        }
        arm()
        // The emulator decodes h264 in software. Playback is real once the timeline actually
        // advances past the seek; waiting for the decoder to also report a frame size on top of
        // that is what times out on a loaded runner, and it proves nothing extra about playback.
        val deadline = SystemClock.uptimeMillis() + 45_000
        var lastState = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            var idle = false
            main {
                ready = player.isPlaying && (player.videoSize.width > 0 || player.currentPosition > 1_700)
                idle = player.playbackState == Player.STATE_IDLE
                lastState = "playing=${player.isPlaying} state=${player.playbackState} " +
                    "position=${player.currentPosition} size=${player.videoSize.width}"
            }
            if (ready) return
            if (idle && SystemClock.uptimeMillis() - armedAt > 2_000) {
                player = awaitFixturePlayer(activity, pagerId)
                arm()
            }
            SystemClock.sleep(30)
        }
        throw AssertionError("Fixture video did not reach actual video playback: $lastState")
    }

    private fun awaitAuthorList(author: AuthorActivity) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var visible = false
            main {
                assertFalse("Back from an author's video must keep the author Activity", author.isFinishing)
                visible = author.findViewById<View>(R.id.authorListPage).visibility == View.VISIBLE
            }
            if (visible) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Author list was not restored after Back completed")
    }

    @Test fun returnPathsKeepCallerAndHomePlaybackSession() {
        // A cold CI emulator can launch/resume an Activity behind its lock screen.
        // Prepare the test device before checking focus or injecting any navigation input.
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
                it.readBytes()
            }
        }
        val app = instrumentation.targetContext.applicationContext as Application
        val session = SecureSessionStore(app)
        val originalRefresh = session.refreshToken
        val originalAccess = session.accessToken
        val positionFailures = mutableListOf<String>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = activity }
            override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        val home = instrumentation.startActivitySync(Intent(app, MainActivityV3::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivityV3
        try {
            awaitActivity(MainActivityV3::class.java)
            val mediaFile = fixtureVideo()
            for (mode in listOf("recommend", "date", "trending", "popularity")) {
                lateinit var item: VideoItem
                main {
                    set(home, "requestSerial", (field(home, "requestSerial") as Int) + 1)
                    set(home, "mode", mode)
                    set(home, "pagingEnabled", false)
                    item = VideoItem("navigation-$mode", "Navigation fixture", "Fixture author", emptyList(), 0,
                        authorId = "fixture-author", sources = listOf(VideoSource("fixture", fixtureUri(mediaFile), 1)))
                    val adapter = field(home, "adapter") as VideoAdapter
                    adapter.replace(listOf(item))
                    adapter.setActive(0)
                }
                instrumentation.waitForIdleSync()
                for (kind in listOf(SavedVideosActivity.KIND_HISTORY, SavedVideosActivity.KIND_FAVORITES)) {
                    playFixture(home, R.id.pager, mediaFile)
                    main { invoke(home, "openSavedVideos", kind) }
                    val saved = awaitActivity(SavedVideosActivity::class.java)
                    assertFalse(home.isFinishing)
                    back(saved, R.id.savedBack)
                    assertSame(home, awaitActivity(MainActivityV3::class.java))
                    assertEquals(mode, field(home, "mode"))
                    assertSame(item, (field(home, "adapter") as VideoAdapter).items.single())
                    if (item.resumePositionMs < 1_500) positionFailures += "$mode/$kind: ${item.resumePositionMs} ms"
                    SystemClock.sleep(200) // Covers the old delayed result callback too.
                    assertFalse(home.isFinishing)
                }

                playFixture(home, R.id.pager, mediaFile)
                main { invoke(home, "openAuthor", "fixture-author", "Fixture author", "") }
                var author = awaitActivity(AuthorActivity::class.java) as AuthorActivity
                playAuthorFixture(author, mediaFile)
                main { assertFalse((field(home, "adapter") as VideoAdapter).isActivePlaying()) }
                back(author, R.id.feedBack)
                awaitAuthorList(author)
                back(author, R.id.authorBack)
                assertSame(home, awaitActivity(MainActivityV3::class.java))

                playFixture(home, R.id.pager, mediaFile)
                main {
                    // A synthetic local session satisfies the UI guard; no real credentials are used.
                    SecureSessionStore(app).refreshToken = "navigation-test-only"
                    invoke(home, "openFollowingPage")
                }
                val following = awaitActivity(FollowingActivity::class.java) as FollowingActivity
                main { invoke(following, "openAuthor", IwaraAuthor("fixture-author", "Fixture author", "")) }
                author = awaitActivity(AuthorActivity::class.java) as AuthorActivity
                playAuthorFixture(author, mediaFile)
                back(author, R.id.feedBack)
                awaitAuthorList(author)
                back(author, R.id.authorBack)
                assertSame(following, awaitActivity(FollowingActivity::class.java))
                assertFalse(home.isFinishing)
                back(following, R.id.followingBack)
                assertSame(home, awaitActivity(MainActivityV3::class.java))
                assertEquals(mode, field(home, "mode"))
                if (item.resumePositionMs < 1_500) positionFailures += "$mode/following: ${item.resumePositionMs} ms"
            }
            // 搜索结果页和其它子页面走同一条返回路径，并且同样要拿走首页的播放权。
            playFixture(home, R.id.pager, mediaFile)
            main { invoke(home, "openSearchPage", "") }
            val search = awaitActivity(SearchActivity::class.java)
            main { assertFalse((field(home, "adapter") as VideoAdapter).isActivePlaying()) }
            assertFalse(home.isFinishing)
            back(search, R.id.searchBack)
            assertSame(home, awaitActivity(MainActivityV3::class.java))
            assertEquals("popularity", field(home, "mode"))

            assertTrue("Saved home position was lost: $positionFailures", positionFailures.isEmpty())
        } finally {
            main {
                session.refreshToken = originalRefresh
                session.accessToken = originalAccess
                home.finish()
            }
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun fixtureVideo(): File {
        return File(instrumentation.targetContext.cacheDir, "navigation-fixture.mp4").apply {
            instrumentation.context.assets.open("navigation-fixture.mp4").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
