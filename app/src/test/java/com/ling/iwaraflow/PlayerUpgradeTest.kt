package com.ling.iwaraflow

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerUpgradeTest {
    @Test fun holdRequiresTwoSecondsAndDoesNotClickOnRelease() {
        val view = ImageView(RuntimeEnvironment.getApplication())
        var doubles = 0; var clicks = 0
        view.setOnClickListener { clicks++ }
        val hold = HoldReaction(view, -1) { doubles++ }
        touch(view, MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1999))
        assertEquals(0, doubles)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, doubles)
        touch(view, MotionEvent.ACTION_UP)
        assertEquals(0, clicks)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(1, doubles)
        hold.cancel()
    }
    @Test fun interruptedHoldDoesNeitherReactionAndDoesNotTriggerOtherButton() {
        val view = ImageView(RuntimeEnvironment.getApplication())
        val other = ImageView(RuntimeEnvironment.getApplication())
        var doubles = 0; var clicks = 0; var otherDoubles = 0
        view.setOnClickListener { clicks++ }
        HoldReaction(view, -1) { doubles++ }
        HoldReaction(other, -1) { otherDoubles++ }
        touch(view, MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        touch(view, MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(0, doubles); assertEquals(0, clicks); assertEquals(0, otherDoubles)
        touch(view, MotionEvent.ACTION_DOWN); touch(view, MotionEvent.ACTION_CANCEL)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(0, doubles)
    }
    @Test fun holdingEitherButtonDrawsBothRingsTogetherInTheirOwnColors() {
        val context = RuntimeEnvironment.getApplication()
        val like = ImageView(context).apply { layout(0, 0, 76, 36) }
        val favorite = ImageView(context).apply { layout(0, 0, 76, 36) }
        var completed = 0
        val hold = HoldReaction(like, 0xFFFF365D.toInt(), favorite, 0xFFFFD54F.toInt()) { completed++ }
        touch(like, MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        fun arc(name: String): Pair<Float, Int> {
            val ring = hold.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(hold) as android.graphics.drawable.Drawable
            val canvas = org.mockito.Mockito.mock(android.graphics.Canvas::class.java)
            ring.draw(canvas)
            val draw = org.mockito.Mockito.mockingDetails(canvas).invocations.single { it.method.name == "drawArc" }
            assertEquals(-90f, draw.arguments[4] as Float, .01f)
            return (draw.arguments[5] as Float) to (draw.arguments[7] as android.graphics.Paint).color
        }
        val first = arc("ring"); val second = arc("partnerRing")
        assertEquals(first.first, second.first, .01f); assertEquals(180f, first.first, 2f)
        assertEquals(0xFFFF365D.toInt(), first.second); assertEquals(0xFFFFD54F.toInt(), second.second)
        touch(like, MotionEvent.ACTION_CANCEL)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(0, completed); assertEquals(0f, arc("ring").first, 0f); assertEquals(0f, arc("partnerRing").first, 0f)
    }
    @Test fun ordinaryTapStillClicks() {
        val view = ImageView(RuntimeEnvironment.getApplication()); var clicks = 0
        view.setOnClickListener { clicks++ }; HoldReaction(view, -1) { fail("tap triggered double") }
        touch(view, MotionEvent.ACTION_DOWN); touch(view, MotionEvent.ACTION_UP)
        assertEquals(1, clicks)
    }
    private fun touch(view: ImageView, action: Int) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, 10f, 10f, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    @Test fun preloadThresholdsNeverEnableLookaheadOnBadNetworkOrWhenDisabled() {
        assertEquals(0, PreloadPolicy.ahead(true, true, 0.4, 0.0))
        assertEquals(1, PreloadPolicy.ahead(true, true, 0.401, 0.0))
        assertEquals(1, PreloadPolicy.ahead(true, true, 0.99, 0.9))
        assertEquals(1, PreloadPolicy.ahead(true, true, 1.0, 0.4))
        assertEquals(2, PreloadPolicy.ahead(true, true, 1.0, 0.401))
        assertEquals(0, PreloadPolicy.ahead(true, false, 1.0, 1.0))
        assertEquals(0, PreloadPolicy.ahead(false, true, 1.0, 1.0))
    }
    @Test fun weightedRecommendationsKeepNegativeTopicsPossibleAndFavorPositiveTopics() {
        val good = VideoItem("liked", "", "a", listOf("music"), 0)
        val less = good.copy(id = "less", tags = listOf("sports"))
        val ranker = RecommendationRanker(Random(2026))
        val profile = PreferenceProfile(emptyMap(), mapOf("music" to 8.0, "sports" to -6.0))
        var goodFirst = 0; var lessFirst = 0
        repeat(2000) {
            val result = ranker.weightedOrder(listOf(good, less), profile, 0L)
            assertEquals(2, result.map { it.id }.toSet().size)
            if (result.first().id == good.id) goodFirst++ else lessFirst++
        }
        assertTrue(goodFirst > lessFirst)
        assertTrue("Negative interests must retain a chance", lessFirst > 0)
    }
    @Test fun learnedInterestsAreBoundedDecayingAndIndependentlyAdjustable() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("iwaraflow.db")
        context.getSharedPreferences("system_interest_adjustments", 0).edit().clear().commit()
        HistoryStore(context).use { history ->
            repeat(200) { i -> history.recordInteraction(VideoItem("$i", "", "a", listOf("dance"), 0), "like", 10000.0) }
            val now = System.currentTimeMillis()
            assertTrue(history.preferenceProfileAt(now).tagWeights.getValue("dance") <= 8.0)
            assertTrue(history.preferenceProfileAt(now + 180L * 86400000).tagWeights.getValue("dance") < 1.0)
            history.setManualTagPreference("dance", 1)
            history.setSystemTagMultiplier("dance", 0.0)
            assertEquals(0.0, history.preferenceProfile().tagWeights.getValue("dance"), 0.0)
            assertEquals(1.5, history.preferenceProfile().manualTagScore(VideoItem("x", "", "", listOf("dance"), 0)), 0.0)
            assertTrue(history.systemTagScores().getValue("dance") > 0)
            val revision = history.interestRevision
            history.setSystemTagMultiplier("revision", 0.5)
            assertEquals(0.5, history.systemTagMultipliers().getValue("revision"), 0.0)
            assertEquals(revision + 1, history.interestRevision)
        }
        HistoryStore(context).use { assertEquals(0.0, it.systemTagMultipliers().getValue("dance"), 0.0) }
    }
    @Test fun liveAndClassicRankingKeepNegativeTopicsPossibleAndStillHonorMutes() {
        val good = VideoItem("good", "", "a", listOf("music"), 0)
        val less = good.copy(id = "less", tags = listOf("sports"))
        val ranker = RecommendationRanker(Random(2026))
        val profile = PreferenceProfile(emptyMap(), mapOf("music" to 8.0, "sports" to -6.0))
        ranker.profile = profile
        var lessLive = 0; var lessClassic = 0
        repeat(2000) {
            if (ranker.rerank(listOf(good, less), profile, 0L).first().id == less.id) lessLive++
            if (ranker.rankClassics(listOf(good, less)).first().id == less.id) lessClassic++
        }
        assertTrue(lessLive in 1..999); assertTrue(lessClassic in 1..999)
        ranker.profile = profile.copy(mutedTags = setOf("sports"))
        assertTrue(ranker.rankClassics(listOf(less)).isEmpty())
        assertTrue(ranker.rerank(listOf(less), ranker.profile, 0L).isEmpty())
    }
    @Test fun defaultSettingsAndPublicationHostAreSafe() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        assertTrue(prefs.preloadNext); assertTrue(prefs.danmakuEnabled)
        assertTrue(UploadActivity.isOfficial("https://www.iwara.tv/upload"))
        assertFalse(UploadActivity.isOfficial("https://iwara.tv.evil.example/upload"))
        assertFalse(UploadActivity.isOfficial("http://www.iwara.tv/upload"))
    }
}
