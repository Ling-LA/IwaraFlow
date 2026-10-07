package com.ling.iwaraflow

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
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
                sources = listOf(VideoSource("fixture", android.net.Uri.fromFile(mediaFile).toString(), 1)))
            // 作者页现在只留一份作品列表，"能播的" 是从它里面筛出来的（playbackIssue 为空），
            // 所以夹具直接进 works 就行。
            @Suppress("UNCHECKED_CAST")
            (field(author, "works") as MutableList<VideoItem>).add(video)
            invoke(author, "openWork", video)
        }
        instrumentation.waitForIdleSync()
        playFixture(author, R.id.authorPager)
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

    private fun playFixture(activity: Activity, pagerId: Int) {
        // Let the fixture really play past 1.5s, then navigation must persist that progress.
        // Observe the current bound player without injecting a seek while its source is being
        // prepared. A ViewPager rebind can replace it between any two main-thread turns.
        val deadline = SystemClock.uptimeMillis() + 45_000
        var lastState = "No bound player"
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main {
                val recycler = activity.findViewById<ViewPager2>(pagerId).getChildAt(0) as RecyclerView
                val holder = recycler.findViewHolderForAdapterPosition(0)
                val player = holder?.let { field(it, "player") as? ExoPlayer }
                if (player != null) {
                    if (!player.playWhenReady) player.play()
                    ready = player.isPlaying && player.currentPosition >= 1_500 && player.videoSize.width > 0
                    lastState = "playing=${player.isPlaying} state=${player.playbackState} " +
                        "position=${player.currentPosition} size=${player.videoSize.width} " +
                        "error=${player.playerError?.errorCodeName}"
                } else lastState = "No bound player"
            }
            if (ready) return
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
        app.getSharedPreferences(AppPrefs.FILE, 0).edit().putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).putBoolean(PlaybackGuide.key(false), true).commit()
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
                        authorId = "fixture-author", sources = listOf(VideoSource("fixture", android.net.Uri.fromFile(mediaFile).toString(), 1)))
                    val adapter = field(home, "adapter") as VideoAdapter
                    adapter.replace(listOf(item))
                    adapter.setActive(0)
                }
                instrumentation.waitForIdleSync()
                for (kind in listOf(SavedVideosActivity.KIND_HISTORY, SavedVideosActivity.KIND_FAVORITES)) {
                    playFixture(home, R.id.pager)
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

                playFixture(home, R.id.pager)
                main { invoke(home, "openAuthor", "fixture-author", "Fixture author", "") }
                var author = awaitActivity(AuthorActivity::class.java) as AuthorActivity
                playAuthorFixture(author, mediaFile)
                main { assertFalse((field(home, "adapter") as VideoAdapter).isActivePlaying()) }
                back(author, R.id.feedBack)
                awaitAuthorList(author)
                back(author, R.id.authorBack)
                assertSame(home, awaitActivity(MainActivityV3::class.java))

                playFixture(home, R.id.pager)
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
            playFixture(home, R.id.pager)
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

    @Test fun settingsGuideReturnsToSamePlayerAndRotatesTheActualWindow() {
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences(AppPrefs.FILE, 0)
        prefs.edit().putBoolean(PlaybackGuide.key(false), true).remove(PlaybackGuide.key(true))
            .putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).commit()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = activity }
            override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        fun descendants(view: View): List<View> = buildList {
            add(view)
            if (view is android.view.ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
        }
        fun text(activity: Activity, value: String) = descendants(activity.window.decorView)
            .filterIsInstance<android.widget.TextView>().first { it.text.toString() == value }
        fun awaitLayout(activity: Activity, horizontal: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                main {
                    val root = activity.window.decorView
                    val guide = root.findViewWithTag<View>("playback_gesture_guide")
                    ready = guide != null && (root.width > root.height) == horizontal &&
                        activity.resources.configuration.orientation == if (horizontal)
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                if (ready) { instrumentation.waitForIdleSync(); return }
                SystemClock.sleep(50)
            }
            throw AssertionError("Guide did not rotate its actual window; horizontal=$horizontal")
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        val home = instrumentation.startActivitySync(Intent(app, MainActivityV3::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivityV3
        var author: AuthorActivity? = null
        var settings: Activity? = null
        try {
            awaitActivity(MainActivityV3::class.java)
            val mediaFile = fixtureVideo()
            lateinit var homeVideo: VideoItem
            main {
                set(home, "requestSerial", (field(home, "requestSerial") as Int) + 1)
                set(home, "mode", "date"); set(home, "pagingEnabled", false)
                homeVideo = VideoItem("guide-home", "示例播放画面", "示例作者", listOf("animation"), 60,
                    sources = listOf(VideoSource("fixture", android.net.Uri.fromFile(mediaFile).toString(), 1)))
                home.playbackGuideAdapter.replace(listOf(homeVideo)); home.playbackGuideAdapter.setActive(0)
            }
            // The disabled tap-to-pause option toggles chrome without resuming a paused video.
            playFixture(home, R.id.pager)
            val fixturePlayer = awaitFixturePlayer(home, R.id.pager)
            main {
                AppPrefs(app).tapToPause = false
                home.findViewById<View>(R.id.root).performClick()
            }
            SystemClock.sleep(500)
            main { home.findViewById<View>(R.id.pauseIndicator).performClick() }
            SystemClock.sleep(300)
            main {
                assertFalse(fixturePlayer.playWhenReady)
                home.findViewById<View>(R.id.root).performClick()
            }
            SystemClock.sleep(500)
            main {
                assertFalse(fixturePlayer.playWhenReady)
                assertEquals(View.VISIBLE, home.findViewById<View>(R.id.actionPanel).visibility)
                assertEquals(View.GONE, home.findViewById<View>(R.id.pauseSeekBar).visibility)
                home.findViewById<View>(R.id.root).performClick()
            }
            SystemClock.sleep(500)
            main {
                assertFalse(fixturePlayer.playWhenReady)
                assertEquals(View.VISIBLE, home.findViewById<View>(R.id.pauseSeekBar).visibility)
                AppPrefs(app).tapToPause = true
            }
            for (fromAuthor in listOf(false, true)) {
                val playerActivity: Activity
                val pagerId: Int
                if (fromAuthor) {
                    main { home.startActivity(Intent(home, AuthorActivity::class.java).putExtra(AuthorActivity.EXTRA_ID, "fixture")) }
                    author = awaitActivity(AuthorActivity::class.java) as AuthorActivity
                    main { (field(author!!, "api") as IwaraApi).close(); set(author!!, "noMore", true) }
                    playAuthorFixture(author!!, mediaFile)
                    playerActivity = author!!; pagerId = R.id.authorPager
                } else { playerActivity = home; pagerId = R.id.pager }
                playFixture(playerActivity, pagerId)
                val adapter = (playerActivity as PlaybackGuideHost).playbackGuideAdapter
                val current = adapter.activeItem()
                main {
                    // Match the real menus: suspend before internal navigation to avoid auto-PiP.
                    adapter.suspendPlayback()
                    playerActivity.startActivity(Intent(playerActivity, SettingsActivity::class.java))
                }
                settings = awaitActivity(SettingsActivity::class.java)
                val originalSettings = settings!!
                lateinit var checkbox: android.widget.CheckBox
                lateinit var scroll: android.widget.ScrollView
                main {
                    checkbox = descendants(originalSettings.window.decorView).filterIsInstance<android.widget.CheckBox>().first()
                    checkbox.isChecked = !checkbox.isChecked // Unsaved form must survive the preview.
                    scroll = descendants(originalSettings.window.decorView).filterIsInstance<android.widget.ScrollView>().first()
                    scroll.fullScroll(View.FOCUS_DOWN)
                }
                instrumentation.waitForIdleSync()
                val checked = checkbox.isChecked; val scrollY = scroll.scrollY
                main { (text(originalSettings, "操作引导").parent as View).performClick() }
                assertSame(playerActivity, awaitActivity(playerActivity.javaClass))
                awaitLayout(playerActivity, false)
                main {
                    assertSame(current, adapter.activeItem())
                    assertTrue(playerActivity.findViewById<View>(R.id.playerView).isShown)
                    assertFalse(adapter.isActivePlaying())
                    assertNull(originalSettings.window.decorView.findViewWithTag<View>("playback_gesture_guide"))
                    text(playerActivity, "查看横屏操作").performClick()
                }
                awaitLayout(playerActivity, true)
                main { assertTrue(adapter.isFullscreen) }
                ProfileUiDeviceTest().screenshot(if (fromAuthor) "settings-guide-author-landscape" else "settings-guide-home-landscape")
                main { text(playerActivity, "查看竖屏操作").performClick() }
                awaitLayout(playerActivity, false)
                ProfileUiDeviceTest().screenshot(if (fromAuthor) "settings-guide-author-portrait" else "settings-guide-home-portrait")
                main { text(playerActivity, "查看横屏操作").performClick() }
                awaitLayout(playerActivity, true)
                if (InstrumentationRegistry.getArguments().getString("backMode") == "key")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                else main { text(playerActivity, "知道了").performClick() }
                assertSame(originalSettings, awaitActivity(SettingsActivity::class.java))
                main {
                    assertEquals(checked, checkbox.isChecked); assertEquals(scrollY, scroll.scrollY)
                    assertFalse(adapter.isFullscreen); assertSame(current, adapter.activeItem())
                    assertTrue("Playback position was reset", current!!.resumePositionMs >= 1500)
                    assertFalse(PlaybackGuide.isActive(playerActivity))
                    assertFalse(prefs.getBoolean(PlaybackGuide.key(true), false))
                    originalSettings.finish()
                }
                settings = null
                assertSame(playerActivity, awaitActivity(playerActivity.javaClass))
                main { assertSame(current, adapter.activeItem()); assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT, playerActivity.resources.configuration.orientation) }
            }
        } finally {
            main { AppPrefs(app).tapToPause = true; settings?.finish(); author?.let { PlaybackGuide.dismiss(it); it.finish() }; PlaybackGuide.dismiss(home); home.finish() }
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun fixtureVideo(): File {
        return File.createTempFile("navigation-fixture-", ".mp4", instrumentation.targetContext.cacheDir).apply {
            instrumentation.context.assets.open("navigation-fixture.mp4").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
