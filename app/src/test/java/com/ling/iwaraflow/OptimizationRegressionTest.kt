package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.concurrent.*
import okhttp3.mockwebserver.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OptimizationRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun video(id: String, tags: List<String> = listOf("music")) = VideoItem(id, "标题 $id", "作者", tags, 0, authorId = "author")
    private fun jwt(seconds: Long) = "header." + android.util.Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000 + seconds).toString().toByteArray(), 11) + ".signature"

    @Test fun logoutPreservesProviderSecrets() {
        val store = SecureSessionStore(app)
        try {
            store.refreshToken = "account"; store.accessToken = "access"
            store.putSecret(SecureSessionStore.KEY_TRANSLATION_KEY, "provider")
            store.clearAuthentication()
            assertNull(store.refreshToken); assertNull(store.accessToken)
            assertEquals("provider", store.secret(SecureSessionStore.KEY_TRANSLATION_KEY))
        } finally { store.clear() }
    }
    @Test fun twoApiInstancesShareOneRefresh() {
        MockWebServer().use { server ->
            val store = SecureSessionStore(app); val access = jwt(3600); store.refreshToken = jwt(7200); store.accessToken = null
            server.enqueue(MockResponse().setBody(JSONObject().put("accessToken", access).toString()).setBodyDelay(100, TimeUnit.MILLISECONDS))
            val root = server.url("/").toString().trimEnd('/')
            val a = IwaraApi(app, root); val b = IwaraApi(app, root); val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<String> { a.uploadAccessToken() }; val second = pool.submit<String> { b.uploadAccessToken() }
                assertEquals(access, first.get(5, TimeUnit.SECONDS)); assertEquals(access, second.get(5, TimeUnit.SECONDS))
                assertEquals(1, server.requestCount)
            } finally { a.close(); b.close(); pool.shutdownNow(); store.clear() }
        }
    }
    @Test fun oldRefreshCannotRestoreLoggedOutSession() {
        MockWebServer().use { server ->
            val store = SecureSessionStore(app); store.refreshToken = jwt(7200); store.accessToken = null
            server.enqueue(MockResponse().setBody(JSONObject().put("accessToken", jwt(3600)).toString()).setBodyDelay(300, TimeUnit.MILLISECONDS))
            val api = IwaraApi(app, server.url("/").toString().trimEnd('/')); val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = pool.submit<Boolean> { runCatching { api.uploadAccessToken() }.isFailure }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); store.clearAuthentication()
                assertTrue(pending.get(5, TimeUnit.SECONDS)); assertNull(store.accessToken); assertNull(store.refreshToken)
            } finally { api.close(); pool.shutdownNow(); store.clear() }
        }
    }
    @Test fun backgroundLimitLeavesAnImmediatePlaybackSlot() {
        val leases = List(RequestScheduler.MAX_BACKGROUND) { RequestScheduler.acquire(RequestScheduler.Priority.BACKGROUND) }
        val pool = Executors.newSingleThreadExecutor(); val entered = CountDownLatch(1)
        try {
            val pending = pool.submit { RequestScheduler.acquire(RequestScheduler.Priority.BACKGROUND).use { entered.countDown() } }
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS))
            RequestScheduler.acquire(RequestScheduler.Priority.PLAYBACK).close()
            leases.first().close(); assertTrue(entered.await(2, TimeUnit.SECONDS)); pending.get(2, TimeUnit.SECONDS)
        } finally { leases.forEach { it.close() }; pool.shutdownNow() }
    }
    @Test fun cancelledQueueDoesNotLeakAPermit() {
        val leases = List(RequestScheduler.MAX_RUNNING) { RequestScheduler.acquire(RequestScheduler.Priority.PLAYBACK) }
        try { assertThrows(java.io.InterruptedIOException::class.java) { RequestScheduler.acquire(RequestScheduler.Priority.INTERACTIVE) { true } } }
        finally { leases.forEach { it.close() } }
        RequestScheduler.acquire(RequestScheduler.Priority.INTERACTIVE).close()
    }
    @Test fun cancellationScopeStopsItsOwnBlockingCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody("ok"))
            val client = okhttp3.OkHttpClient.Builder().addInterceptor(RequestScheduler).build()
            val scope = RequestCancellation(); val pool = Executors.newSingleThreadExecutor()
            try {
                val old = pool.submit<Boolean> { scope.run { runCatching { client.newCall(okhttp3.Request.Builder().url(server.url("/slow")).build()).execute().use { it.body!!.string() } }.isFailure } }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); scope.cancel(); assertTrue(old.get(3, TimeUnit.SECONDS))
                client.newCall(okhttp3.Request.Builder().url(server.url("/next")).build()).execute().use { assertEquals("ok", it.body!!.string()) }
            } finally { pool.shutdownNow(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
        }
    }
    @Test fun rankingDoesNotFlattenEveryHighScoreOrEliminateNegatives() {
        assertTrue(RecommendationRanker.samplingWeight(12.0) > RecommendationRanker.samplingWeight(9.0))
        assertTrue(RecommendationRanker.samplingWeight(-100.0) > 0)
        assertTrue(RecommendationRanker.samplingWeight(100.0) / RecommendationRanker.samplingWeight(-100.0) < 55)
    }
    @Test fun manualRecallEventuallyUsesTheWholePool() {
        val tags = (0..19).associate { "tag$it" to 1 }
        val profile = PreferenceProfile(emptyMap(), emptyMap(), manualTagPreferences = tags)
        val selected = mutableSetOf<String>(); val random = java.util.Random(72)
        repeat(100) { selected += profile.topTags(2, 0.0, random) }
        assertEquals(tags.keys, selected)
    }
    @Test fun independentVideosAndAuthorsIncreaseEvidenceNotRepeatedActions() {
        val evidence = InterestEvidence(); evidence.observe("v1", "a", listOf("music")); val first = evidence.confidence("music")
        repeat(30) { evidence.observe("v1", "a", listOf("music")) }; assertEquals(first, evidence.confidence("music"), 0.0)
        evidence.observe("v2", "b", listOf("music")); assertTrue(evidence.confidence("music") > first)
    }
    @Test fun accountScopesKeepFavorites() {
        val prefs = AppPrefs(app); val history = HistoryStore(app)
        try {
            history.setManualTagPreference("shared", 1)
            history.setLocalFavorite(video("saved"), true)
            prefs.isolateInterests = true; history.accountId = "A"
            history.setManualTagPreference("music", 1); history.recordInteraction(video("liked"), "like", 2.0); history.setSystemTagMultiplier("music", .5)
            history.accountId = "B"
            assertTrue(history.manualTagPreferences().isEmpty()); assertTrue(history.preferenceProfile().tagWeights.isEmpty()); assertTrue(history.systemTagMultipliers().isEmpty())
            assertTrue(history.isLocalFavorite("saved"))
            history.accountId = "A"; assertEquals(1, history.manualTagPreferences()["music"]); assertEquals(.5, history.systemTagMultipliers()["music"]!!, .001)
            prefs.isolateInterests = false; assertEquals(mapOf("shared" to 1), history.manualTagPreferences())
        } finally { prefs.isolateInterests = false; history.close() }
    }
    @Test fun privateBrowsingSkipsLearningAndHistoryButHonorsExplicitFavorite() {
        val prefs = AppPrefs(app); val history = HistoryStore(app); prefs.privateBrowsing = true
        try {
            val v = video("private"); history.recordWatch(v, 10, 100, false); history.recordInteraction(v, "like", 2.0); history.markSeen(v.id)
            assertTrue(history.recentHistory().isEmpty()); assertTrue(history.preferenceProfile().tagWeights.isEmpty()); assertFalse(history.isSeen(v.id))
            history.setLocalFavorite(v, true); assertTrue(history.isLocalFavorite(v.id)); assertFalse(history.isSeen(v.id))
        } finally { prefs.privateBrowsing = false; history.close() }
    }
    @Test fun favoritesPaginateBeyondTheOldTwoHundredLimitWithoutDuplicates() {
        val h = HistoryStore(app)
        try {
            repeat(251) { h.setLocalFavorite(video("%03d".format(it)), true) }
            val ids = mutableListOf<String>()
            repeat(6) { offset -> val page = SavedLibrary.page(h.readableDatabase, true, offset*48); assertEquals(251, page.total); ids += page.items.map { it.id } }
            assertEquals(251, ids.size); assertEquals(251, ids.toSet().size)
            assertEquals(1, SavedLibrary.page(h.readableDatabase, true, 0, query = "标题 250").total)
            assertEquals(ids.reversed(), SavedLibrary.page(h.readableDatabase, true, 0, 1000, ascending = true).items.map { it.id })
        } finally { h.close() }
    }
    @Test fun backupRoundtripIncludesManualSystemAndMutesButNoCredentials() {
        val h = HistoryStore(app)
        try {
            h.setLocalFavorite(video("v"), true); h.setManualTagPreference("music", 1); h.mute(HistoryStore.MUTE_TAG, "animation")
            val text = LocalDataBackup.export(h.readableDatabase, multipliers = mapOf("music" to .5))
            h.writableDatabase.delete("favorites", null, null); h.writableDatabase.delete("manual_tag_preferences", null, null)
            var imported = emptyMap<String, Double>(); assertEquals(2, LocalDataBackup.restore(h.writableDatabase, text) { imported = it })
            assertTrue(h.isLocalFavorite("v")); assertEquals(1, h.manualTagPreferences()["music"]); assertEquals(.5, imported["music"]!!, .001)
            assertFalse(text.contains("refresh_token")); assertFalse(text.contains("history"))
        } finally { h.close() }
    }
    @Test fun legacyScoresExportWithinSupportedImportBounds() {
        val h = HistoryStore(app)
        try {
            h.recordInteraction(video("legacy"), "like", 2.0)
            h.writableDatabase.execSQL("UPDATE preference_entities SET score=100")
            val backup = LocalDataBackup.export(h.readableDatabase)
            h.writableDatabase.delete("preference_entities", null, null)
            assertTrue(LocalDataBackup.restore(h.writableDatabase, backup) > 0)
            h.readableDatabase.rawQuery("SELECT MAX(score) FROM preference_entities", null).use { c -> c.moveToFirst(); assertEquals(8.0, c.getDouble(0), .001) }
        } finally { h.close() }
    }
    @Test fun malformedBackupRollsBackAllRows() {
        val h = HistoryStore(app)
        try {
            h.setLocalFavorite(video("v"), true); val root = JSONObject(LocalDataBackup.export(h.readableDatabase)); h.writableDatabase.delete("favorites", null, null)
            root.put("manual_tag_preferences", org.json.JSONArray().put(JSONObject().put("tag", "music").put("preference", 99)))
            assertThrows(IllegalArgumentException::class.java) { LocalDataBackup.restore(h.writableDatabase, root.toString()) }
            assertFalse(h.isLocalFavorite("v"))
        } finally { h.close() }
    }
    @Test fun tagAliasesBlockAndUnblockTheSameConcept() {
        val h = HistoryStore(app)
        try {
            h.mute(HistoryStore.MUTE_TAG, "初音未来"); assertTrue(h.preferenceProfile().isMuted(video("v", listOf("hatsune_miku"))))
            h.unmute(HistoryStore.MUTE_TAG, "初音ミク"); assertFalse(h.preferenceProfile().isMuted(video("v", listOf("hatsune_miku"))))
        } finally { h.close() }
    }
    @Test fun mainThreadCloseDoesNotWaitForQueuedDiskWrites() {
        val h = HistoryStore(app); val hold = CountDownLatch(1); val started = CountDownLatch(1)
        h.post { started.countDown(); hold.await(3, TimeUnit.SECONDS) }; assertTrue(started.await(2, TimeUnit.SECONDS))
        try { val start = System.nanoTime(); h.close(); assertTrue((System.nanoTime() - start)/1_000_000 < 150) } finally { hold.countDown() }
    }
    @Test fun lowBandwidthNeverOpensLookaheadAndEnoughBufferIsRequired() {
        assertFalse(PreloadPolicy.enoughBandwidth(100_000, 100_000_000, 100_000, 30_000))
        assertFalse(PreloadPolicy.enoughBandwidth(3_000_000, 100_000_000, 100_000, 5_000))
        assertTrue(PreloadPolicy.enoughBandwidth(3_000_000, 100_000_000, 100_000, 30_000))
        assertEquals(0, PreloadPolicy.ahead(true, false, 1.0, 1.0)); assertEquals(2, PreloadPolicy.ahead(true, true, 1.0, .41))
    }
    @Test fun restoredFeedRetainsPositionButDropsExpiringPlaybackUrls() {
        val items = (0..79).map { video("v$it").apply { resumePositionMs = 7654; streamUrl = "https://cdn.invalid/private?token=secret" } }
        val text = FeedSessionCodec.encode(FeedSessionStore.Session(items, 30, 4, true))
        assertFalse(text.contains("secret")); val restored = FeedSessionCodec.decode(text)!!
        assertEquals("v30", restored.items[restored.currentIndex].id); assertEquals(7654L, restored.items[restored.currentIndex].resumePositionMs)
        assertNull(restored.items.first().streamUrl); assertTrue(restored.items.size <= 40); assertNull(FeedSessionCodec.decode("invalid"))
    }
    @Test fun searchChoosesMoreSpecificSeedWithoutChangingAndRules() {
        val q = SearchQuery.literal("music specific_character")
        assertEquals(listOf("specific_character"), q.seeds); assertFalse(q.matchesTitle("music")); assertTrue(q.matchesTitle("specific character music"))
        assertTrue(SearchQuery.local("初音未来").matchesTags(listOf("hatsune_miku")))
    }
    @Test fun diagnosticExportRedactsHistoricalLinksAndBearerHeaders() {
        val file = NavigationDiagnostics.writeReportFile(app, "failure https://cdn.invalid/video/secret?token=key Bearer credential")
        val value = file.readText(); assertFalse(value.contains("credential")); assertFalse(value.contains("cdn.invalid")); assertFalse(value.contains("token=key"))
    }
    @Test fun percentilesAndDraftSerializationAreStable() {
        assertEquals(95L, PlaybackMetrics.percentile((1L..100L).toList(), .95))
        val d = VideoUploadDraft("title", "body", listOf("music"), "general", true, false, true)
        assertEquals(d, UploadCoordinator.readDraft(UploadCoordinator.writeDraft(d)))
    }
    @Test fun accountChangePreventsUploadBeforeAnyBytesAreSent() {
        MockWebServer().use { server ->
            val root = server.url("/").toString().trimEnd('/')
            IwaraUploadClient({ "token" }, root, root, sessionValid = { false }).use { client ->
                assertThrows(IllegalStateException::class.java) { client.upload("v.mp4", 1, { java.io.ByteArrayInputStream(byteArrayOf(1)) }) {} }
                assertEquals(0, server.requestCount)
            }
        }
    }
}
