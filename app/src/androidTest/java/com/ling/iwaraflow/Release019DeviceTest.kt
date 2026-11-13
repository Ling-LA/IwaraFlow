package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real decoder, native locale recreation and readable share media; no account mutations. */
@RunWith(AndroidJUnit4::class)
class Release019DeviceTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val context get() = test.targetContext
    private fun main(block: () -> Unit) = test.runOnMainSync(block)
    private fun field(target: Any, key: String) = target.javaClass.getDeclaredField(key).apply { isAccessible = true }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false; main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun finalVideoCanShowHideTransportAndReplayAfterRealDecoderReachesEnd() {
        val prefs = AppPrefs(context)
        val oldAuto = prefs.autoNext; val oldTap = prefs.tapToPause
        context.getSharedPreferences(AppPrefs.FILE, 0).edit().putBoolean(PlaybackGuide.key(false), true)
            .putBoolean(PlaybackGuide.key(true), true).putBoolean(OverlayPermissionPrompt.KEY_SHOWN, true).commit()
        prefs.autoNext = false; prefs.tapToPause = false
        val clip = File(context.cacheDir, "ended-019.mp4").apply {
            test.context.assets.open("browse-landscape-fixture.mp4").use { input -> outputStream().use(input::copyTo) }
        }
        val activity = test.startActivitySync(Intent(context, MainActivityV3::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivityV3
        try {
            main {
                activity.javaClass.getDeclaredMethod("invalidateRequests").apply { isAccessible = true }.invoke(activity)
                (field(activity, "api").get(activity) as IwaraApi).close()
                (field(activity, "recommender").get(activity) as RecommendationEngine).close()
                field(activity, "pagingEnabled").setBoolean(activity, false)
                activity.findViewById<View>(R.id.loading).visibility = View.GONE
                val adapter = field(activity, "adapter").get(activity) as VideoAdapter
                adapter.replace(listOf(VideoItem("end-019", "Neutral landscape", "Example creator", listOf("landscape"), 100,
                    sources = listOf(VideoSource("fixture", Uri.fromFile(clip).toString(), 1)))))
                adapter.setActive(0); adapter.resumeActive()
            }
            lateinit var holder: RecyclerView.ViewHolder
            lateinit var player: ExoPlayer
            val pager = activity.findViewById<ViewPager2>(R.id.pager)
            await("Neutral video did not decode") {
                val h = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(0)
                val p = h?.let { field(it, "player").get(it) as? ExoPlayer }
                if (h != null && p != null && p.isPlaying && p.videoSize.width > 0) { holder = h; player = p; true } else false
            }
            main { player.seekTo((player.duration - 200).coerceAtLeast(0)) }
            await("Player never reached STATE_ENDED") { player.playbackState == Player.STATE_ENDED }
            val bar = holder.itemView.findViewById<View>(R.id.pauseSeekBar)
            await("Ended transport unavailable") { bar.isShown }
            main { holder.itemView.performClick() }
            await("Ended video could not show information") { !bar.isShown && holder.itemView.findViewById<View>(R.id.actionPanel).isShown }
            main { assertEquals(Player.STATE_ENDED, player.playbackState); holder.itemView.performClick() }
            await("Ended video could not show transport again") { bar.isShown }
            ProfileUiDeviceTest().screenshot("ended-transport-019")
            main { holder.itemView.findViewById<View>(R.id.pauseIndicator).performClick() }
            await("Replay did not rewind and resume") { player.isPlaying && player.currentPosition < player.duration - 500 }
            main { player.pause() }
            main { (field(activity, "adapter").get(activity) as VideoAdapter).suspendPlayback() }
            main { (field(activity, "adapter").get(activity) as VideoAdapter).resumeActive() }
            main { assertFalse("Lifecycle return resumed a user pause", player.playWhenReady) }
        } finally {
            main { activity.finish() }; prefs.autoNext = oldAuto; prefs.tapToPause = oldTap; clip.delete()
        }
    }

    @Test fun settingsSwitchesAndPersistsAllFourNativeLanguages() {
        val original = AppLanguage.current
        var activity: Activity? = null
        try {
            for ((index, code) in AppLanguage.codes.withIndex()) {
                activity = test.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                test.waitForIdleSync()
                val page = activity!!
                main {
                    val all = views(page.window.decorView)
                    val spinner = all.filterIsInstance<Spinner>().first()
                    assertEquals(AppLanguage.names, (0 until spinner.count).map { spinner.getItemAtPosition(it).toString() })
                    spinner.setSelection(index)
                    val save = UiText.text(R.string.ui_a3030bf8f16d, "保存")
                    all.filterIsInstance<TextView>().first { it.text.toString() == save }.performClick()
                }
                await("Locale not applied: $code") { AppLanguage.current == code }
                test.waitForIdleSync(); SystemClock.sleep(350)
                activity = test.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                test.waitForIdleSync()
                val reopened = activity!!
                main {
                    val first = views(reopened.window.decorView).filterIsInstance<Spinner>().first()
                    assertEquals(index, first.selectedItemPosition)
                    assertEquals(code.substringBefore('-'), reopened.resources.configuration.locales[0].language)
                    assertEquals(UiText.text(R.string.ui_44ce7ae909bb, "搜索"), reopened.getString(R.string.ui_44ce7ae909bb))
                    assertEquals("zh-CN", Translator.TARGET)
                    val navigation = reopened.layoutInflater.inflate(R.layout.activity_main, null)
                    val metrics = reopened.resources.displayMetrics
                    navigation.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                    navigation.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
                    for (id in listOf(R.id.tabRecommend, R.id.tabLatest, R.id.tabTrending, R.id.tabPopular)) {
                        val tab = navigation.findViewById<TextView>(id)
                        assertEquals("Navigation wrapped in $code", 1, tab.lineCount)
                        assertTrue("Navigation clipped in $code", tab.paint.measureText(tab.text.toString()) <= tab.width)
                    }
                }
                ProfileUiDeviceTest().screenshot("settings-language-$code".lowercase())
                main { reopened.finish() }; test.waitForIdleSync(); activity = null
            }
        } finally { main { activity?.finish(); AppLanguage.select(original) } }
    }

    @Test fun thumbnailIsReadableJpegAndInstalledSocialAppsCanAcceptIt() {
        val image = File(context.cacheDir, "share-neutral-019.png")
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0xFF79ACC9.toInt())
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val ready = CountDownLatch(1); var result: Uri? = null
        main { ShareThumbnail.prepare(context, image.toURI().toString()) { result = it; ready.countDown() } }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNotNull(result)
        context.contentResolver.openInputStream(result!!)!!.use { input ->
            val bitmap = BitmapFactory.decodeStream(input)
            assertNotNull(bitmap); assertTrue(bitmap.width in 1..1024); bitmap.recycle()
        }
        val intent = SharePanel.imageIntent("Neutral example\nhttps://www.iwara.tv/", "Neutral", result!!)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        for (pkg in listOf("com.twitter.android", "org.telegram.messenger")) {
            val installed = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            if (installed) assertNotNull("Installed $pkg has no image share target", Intent(intent).setPackage(pkg).resolveActivity(context.packageManager))
        }
        assertFalse(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains(AppLinks.DOWNLOAD))
        image.delete()
    }

    @Test fun authorShareRendersProfileCardEvenWhenAvatarCannotLoad() {
        val biography = "<p>记录山川与晨光，分享旅行中的风景。</p><p>Landscape films · 日々の記録 · 여행 이야기</p>"
        assertEquals("Notes\nRead more", AuthorShareCard.plainText("## Notes<br>[Read more](https://example.com)"))
        val author = IwaraAuthor("neutral-author", "自然记录者 / Nature Studio", "nature_studio", biography,
            avatarUrl = File(context.cacheDir, "missing-avatar-019.png").toURI().toString())
        val ready = CountDownLatch(1); var result: Uri? = null
        main { ShareThumbnail.prepareAuthor(context, author) { result = it; ready.countDown() } }
        assertTrue("Missing avatar prevented the profile card", ready.await(15, TimeUnit.SECONDS))
        assertNotNull(result)
        val jpeg = context.contentResolver.openInputStream(result!!)!!.use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(1200, bitmap.width); assertEquals(630, bitmap.height)
        // A profile card has rendered name and biography ink outside the avatar bounds.
        fun inkIn(left: Int, top: Int, right: Int, bottom: Int): Int {
            var count = 0
            for (y in top until bottom step 2) for (x in left until right step 2) {
                if (android.graphics.Color.red(bitmap.getPixel(x, y)) < 150) count++
            }
            return count
        }
        assertTrue("Author name was not drawn", inkIn(224, 74, 1100, 130) > 100)
        assertTrue("Biography was not drawn", inkIn(64, 252, 1136, 450) > 100)
        bitmap.recycle()
        File(context.getExternalFilesDir(null), "author-share-card-019.jpg").writeBytes(jpeg)
        val send = SharePanel.imageIntent(VideoShare.authorShareText(author), author.name, result!!)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains(VideoShare.authorLinkFor(author)))
        assertFalse(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains(AppLinks.DOWNLOAD))
        val longCard = AuthorShareCard.render(author.copy(name = "Long name ".repeat(80),
            description = biography.repeat(100)), null)
        assertEquals(630, longCard.height); longCard.recycle()
        val avatar = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF5379B9.toInt()) }
        val withAvatar = AuthorShareCard.render(author, avatar)
        assertEquals("Avatar was not center-cropped into the card", 0xFF5379B9.toInt(), withAvatar.getPixel(128, 124))
        assertNotEquals("Avatar should have round corners", 0xFF5379B9.toInt(), withAvatar.getPixel(65, 61))
        withAvatar.recycle(); avatar.recycle()
    }

    @Test fun persistedTitleAndDescriptionEvidenceChangesRealDeviceRanking() {
        // Exercise the device's ICU regex engine, not the desktop JVM implementation.
        assertTrue(SearchQuery.literal("robot").matchesTitle("Robot dance"))
        assertFalse(SearchQuery.literal("robot").matchesTitle("éroboté"))
        val scripts = ContentInterest.features(VideoItem("scripts", "étoile привет", "", emptyList(), 0))
        assertTrue(scripts.title.containsAll(listOf("étoile", "привет")))
        val prefs = AppPrefs(context)
        val oldPrivate = prefs.privateBrowsing; val oldIsolated = prefs.isolateInterests
        val name = "text-device-${System.nanoTime()}.db"
        prefs.privateBrowsing = false; prefs.isolateInterests = false
        try {
            HistoryStore(context, name).use { store ->
                for (i in 1..2) store.recordInteraction(VideoItem("text-$i", "Robot dance", "creator-$i",
                    emptyList(), 30, description = "Quiet forest\nSubscribe at https://example.com"), "like", 3.0)
            }
            HistoryStore(context, name).use { store ->
                val profile = store.preferenceProfile()
                assertTrue(profile.textWeights.getValue("robot") > 0)
                assertTrue(profile.textWeights.getValue("forest") > 0)
                assertFalse(profile.textWeights.containsKey("subscribe"))
                val ranker = RecommendationRanker()
                val now = System.currentTimeMillis()
                val plain = VideoItem("candidate", "Ocean", "new creator", emptyList(), 300)
                assertTrue(ranker.scoreOf(plain.copy(title = "机器人"), profile, now) > ranker.scoreOf(plain, profile, now))
                assertTrue(ranker.scoreOf(plain.copy(description = "forest"), profile, now) > ranker.scoreOf(plain, profile, now))
                val batch = (1..256).map { plain.copy(id = "candidate-$it", title = "Robot dance $it", description = "Quiet forest $it") }
                val begin = SystemClock.elapsedRealtimeNanos()
                batch.forEach { ranker.scoreOf(it, profile, now) }
                val coldMs = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
                val warmBegin = SystemClock.elapsedRealtimeNanos()
                batch.forEach { ranker.scoreOf(it, profile, now) }
                val warmMs = (SystemClock.elapsedRealtimeNanos() - warmBegin) / 1_000_000.0
                android.util.Log.i("Recommendation019", "256 candidates: cold=${coldMs}ms cached=${warmMs}ms")
            }
        } finally {
            prefs.privateBrowsing = oldPrivate; prefs.isolateInterests = oldIsolated
            context.deleteDatabase(name)
        }
    }
}
