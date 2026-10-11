package com.ling.iwaraflow

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClientOptimizationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun video(id: String) = VideoItem(id, "Animation $id", "Author", listOf("music"), 0)

    @Test fun failedFavoriteInsertRollsBackTheOptimisticUiAndReportsFailure() {
        HistoryStore(context).use { history ->
            history.writableDatabase.execSQL("CREATE TRIGGER refuse_favorite BEFORE INSERT ON favorites BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            val item = video("failure")
            var result: Result<Unit>? = null
            history.setLocalFavoriteAsync(item, true) { result = it }
            history.awaitWrites(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(result!!.isFailure)
            assertFalse(item.localFavorite); assertFalse(history.isLocalFavorite(item.id)); assertFalse(history.isSeen(item.id))
        }
    }

    @Test fun favoriteAndSeenAreOneTransaction() {
        HistoryStore(context).use { history ->
            history.writableDatabase.execSQL("CREATE TRIGGER refuse_seen BEFORE INSERT ON seen_videos BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertTrue(runCatching { history.setLocalFavorite(video("atomic"), true) }.isFailure)
            assertFalse(history.isLocalFavorite("atomic"))
        }
    }

    @Test fun libraryScopesAreIsolated() {
        val prefs = AppPrefs(context)
        HistoryStore(context).use { history ->
            history.setLocalFavorite(video("shared"), true)
            prefs.isolateLibrary = true; history.accountId = "alice"
            assertFalse(history.isLocalFavorite("shared"))
            history.setLocalFavorite(video("alice-only"), true)
            history.recordWatch(video("watched"), 1200, 5000, false)
            history.accountId = "bob"
            assertFalse(history.isLocalFavorite("alice-only")); assertFalse(history.isSeen("watched"))
            history.accountId = "alice"
            assertTrue(history.isLocalFavorite("alice-only")); assertTrue(history.isSeen("watched"))
            prefs.isolateLibrary = false
            assertTrue(history.isLocalFavorite("shared")); assertFalse(history.isLocalFavorite("alice-only"))
        }
    }

    @Test fun queuedLibraryScope() {
        AppPrefs(context).isolateLibrary = true
        HistoryStore(context).use { history ->
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            history.accountId = "first"
            history.post { entered.countDown(); resume.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            history.setLocalFavoriteAsync(video("queued"), true); history.accountId = "second"
            resume.countDown(); history.awaitWrites(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(history.isLocalFavorite("queued"))
            history.accountId = "first"; assertTrue(history.isLocalFavorite("queued"))
        }
    }

    @Test fun downloadPaginationHasNoThreeHundredRecordCeilingAndFiltersBeforePaging() {
        HistoryStore(context).use { history ->
            repeat(365) { history.recordDownload(video("v$it"), "1080", it.toLong()) }
            val rows = (0..7).flatMap { history.downloadRecords(48, it * 48) }
            assertEquals(365, rows.map { it.downloadId }.distinct().size); assertEquals(365, history.downloadCount())
            assertEquals(listOf("1080"), history.downloadedQualities("v364"))
            assertEquals(6, history.downloadRecords(100, query = "v36").size); assertEquals(6, history.downloadCount("v36"))
            val ascending = (0..7).flatMap { history.downloadRecords(48, it * 48, ascending = true) }.map { it.downloadId }
            assertEquals(rows.map { it.downloadId }.reversed(), ascending)
        }
    }

    @Test fun recreationPreservesPositionPauseAndOffsetButNeverSignedUrls() {
        val prefs = AppPrefs(context).apply { accountId = "owner" }
        val rows = (0..89).map { video("$it") }
        rows[55].apply { resumePositionMs = 12000; resumePlayWhenReady = false; streamUrl = "https://cdn.invalid/a?token=secret" }
        val out = Bundle()
        PagePlaybackState.save(out, prefs, rows, 55, true, page = 4, base = 200)
        val restored = PagePlaybackState.read(out, prefs)!!; val active = restored.items[restored.currentIndex]
        assertEquals("55", active.id); assertEquals(12000L, active.resumePositionMs); assertFalse(active.resumePlayWhenReady)
        assertFalse(out.getString("page_items")!!.contains("secret"))
        assertEquals(253, PagePlaybackState.start(out)); assertEquals(290, PagePlaybackState.end(out))
        prefs.accountId = "different"; assertNull(PagePlaybackState.read(out, prefs))
        prefs.accountId = "owner"; prefs.privateBrowsing = true; assertNull(PagePlaybackState.read(out, prefs))
        val privateState = Bundle(); PagePlaybackState.save(privateState, prefs, rows, 0, false)
        assertTrue(privateState.isEmpty)
    }

    @Test fun longSessionsRetainCurrentVideoAndTheUnseenTail() {
        val rows = (0..899).map { video("$it") }; val sessions = FeedSessionStore()
        sessions.save("recommend", FeedSessionStore.Session(rows, 850, 20, true))
        val restored = sessions.restorable("recommend")!!
        assertEquals(500, restored.items.size); assertEquals("850", restored.items[restored.currentIndex].id)
        assertEquals("899", restored.items.last().id)
    }

    @Test fun manualSearchAliasesDoNotTurnAndKeywordsIntoOr() {
        val terms = ManualSearchTerms(context)
        terms.set("自定义", listOf("CustomName", "customname", "", "x".repeat(81)))
        assertEquals(listOf("CustomName"), terms.get("自定义"))
        val plan = terms.merge(SearchQuery(listOf(listOf("自定义"), listOf("dance"))))
        assertTrue(plan.matchesTitle("CustomName dance")); assertFalse(plan.matchesTitle("CustomName music"))
        terms.set("自定义", emptyList()); assertTrue(terms.get("自定义").isEmpty())
    }

    @Test fun meteredBudgetIsSharedAndPersistsAtTheLimit() {
        AppPrefs(context).meteredPreloadMb = 1
        val first = PreloadBudget(context); val second = PreloadBudget(context)
        first.record(700_000); assertTrue(second.allowed())
        second.record(400_000); assertFalse(first.allowed())
        assertEquals(1_100_000L, context.getSharedPreferences("preload_budget", 0).getLong("bytes", 0))
        AppPrefs(context).meteredPreloadMb = 0; assertTrue(first.allowed())
    }

    @Test fun cancelSettingsDoesNotApplyPrivacyOrMeteredToggles() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get(); val prefs = AppPrefs(activity)
            val originalPrivate = prefs.privateBrowsing; val originalMetered = prefs.preloadOnMetered
            fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
            val views = all(activity.window.decorView)
            views.filterIsInstance<CheckBox>().first { it.text.contains("不记录浏览") }.toggle()
            views.filterIsInstance<CheckBox>().first { it.text.contains("移动 / 计量网络") }.toggle()
            views.filterIsInstance<TextView>().first { it.text.toString() == "取消" }.performClick()
            assertEquals(originalPrivate, prefs.privateBrowsing); assertEquals(originalMetered, prefs.preloadOnMetered)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun skipPenaltyDecaysInsteadOfRemainingPermanent() {
        HistoryStore(context).use { history ->
            repeat(5) { history.recordInteraction(video("skip$it"), HistoryStore.ACTION_SKIP, -.5) }
            val now = System.currentTimeMillis()
            val current = history.preferenceProfileAt(now).tagWeights.getValue("music")
            val old = history.preferenceProfileAt(now + 180L * 86400000).tagWeights.getValue("music")
            assertTrue(current < 0); assertTrue(old > current); assertTrue(old <= 0)
        }
    }
    @Test fun failedBackupInsertCannotBeReportedAsSuccessfulMerge() {
        HistoryStore(context).use { history ->
            history.setLocalFavorite(video("backup"), true)
            val text = LocalDataBackup.export(history.libraryDatabase)
            history.libraryDatabase.delete("favorites", null, null)
            history.libraryDatabase.execSQL("CREATE TRIGGER reject_backup BEFORE INSERT ON favorites BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { LocalDataBackup.restore(history.libraryDatabase, text) }
            assertFalse(history.isLocalFavorite("backup"))
        }
    }
}
