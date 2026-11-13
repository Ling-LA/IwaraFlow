package com.ling.iwaraflow

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Release019RegressionTest {
    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
    @Test fun languagesSwitchResourcesAndDynamicLabelsWithoutChangingContentTranslation() {
        val context = RuntimeEnvironment.getApplication()
        AppLanguage.initialize(context)
        try {
            for ((code, label) in listOf("zh-CN" to "点赞数", "en" to "Likes", "ja" to "いいね数", "ko" to "좋아요 수")) {
                AppLanguage.select(code)
                assertEquals(code, AppLanguage.current)
                assertEquals(label, SearchSort.Key.LIKES.label)
                val formatted = UiText.text(R.string.ui_6c00beb19626, "打不开 %1\$s", "Neutral")
                assertTrue(formatted.contains("Neutral")); assertFalse(formatted.contains("%1"))
                AppLanguage.initialize(context)
                assertEquals(code, AppLanguage.current)
                assertEquals("zh-CN", Translator.TARGET)
            }
        } finally { AppLanguage.select("zh-CN") }
    }
    @Test fun imageShareGrantsOnlyReadableThumbnailAndPreservesLinkAndCaption() {
        val uri = Uri.parse("content://com.ling.iwaraflow.fileprovider/share_thumbnails/neutral.jpg")
        val intent = SharePanel.imageIntent("Example\nhttps://www.iwara.tv/video/example", "Example", uri)
        assertEquals("image/jpeg", intent.type)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith("/example"))
        assertTrue(SharePanel.wantsThumbnail("com.twitter.android"))
        assertTrue(SharePanel.wantsThumbnail("org.telegram.messenger"))
        assertFalse(SharePanel.wantsThumbnail("com.example.notes"))
    }
    @Test fun expiredSeasonalProfileKeepsManualAndNegativeChoicesButNoAutomaticRecall() {
        val original = PreferenceProfile(emptyMap(), mapOf("halloween" to 8.0, "xmas" to -2.0, "robot" to 3.0),
            manualTagPreferences = mapOf("halloween" to 1), positiveTagWeights = mapOf("halloween" to 5.0),
            textWeights = mapOf("halloween" to .5, "robot" to .5))
        val filtered = SeasonalInterest.withoutExpiredLearning(original, now)
        assertFalse("halloween" in filtered.tagWeights)
        assertEquals(-2.0, filtered.tagWeights.getValue("xmas"), 0.0)
        assertTrue(filtered.positiveTagWeights.isEmpty())
        assertEquals(mapOf("robot" to .5), filtered.textWeights)
        assertTrue("halloween" in filtered.topTags(4, 1.0)) // explicit choice remains
        val automatic = filtered.copy(manualTagPreferences = emptyMap())
        assertEquals(listOf("robot"), automatic.topTags(4, 1.0))
    }
    @Test fun cachedHolidayCandidateLosesStaleReasonAndBonus() {
        val c = RecommendationCandidate(VideoItem("v", "halloween", "creator", listOf("halloween"), 10))
        c.seasonalTag = "halloween"; c.matchedTags += "halloween"
        c.noteSource(RecommendationCandidate.Source.SEASONAL, 2.0)
        c.noteSource(RecommendationCandidate.Source.TAG, 3.0)
        c.noteSource(RecommendationCandidate.Source.TRENDING, 1.0)
        c.expireSeasonal(PreferenceProfile(emptyMap(), emptyMap()), now)
        assertEquals("热门榜上的作品", c.reason())
        assertEquals(1.0, c.sourceScore, .0001)
        assertTrue(c.matchedTags.isEmpty())
    }
    @Test fun knownMultiwordNamesStayTogetherAndSeparateTermsStillRequireAnd() {
        assertEquals(listOf("Hatsune Miku", "dance"), SearchQuery.terms("Hatsune Miku dance"))
        assertEquals(listOf("Hatsune", "Miku"), SearchQuery.terms("Hatsune, Miku"))
        val q = SearchQuery.local("Hatsune Miku dance")
        assertTrue(q.matchesTitle("初音ミク ダンス"))
        assertFalse(q.matchesTitle("初音ミク concert"))
        assertFalse(SearchQuery.literal("cat").matchesTitle("cafécat"))
    }
    @Test fun ambiguousNamesRequireVerifiedAliasesInsteadOfLiteralMachineTranslation() {
        assertTrue(QueryTranslator.requiresNameValidation("春雨"))
        assertTrue(QueryTranslator.requiresNameValidation("Harusame"))
        assertTrue(QueryTranslator.requiresNameValidation("ハルサメ"))
        assertTrue(QueryTranslator.requiresNameValidation("캐릭터"))
        assertFalse(QueryTranslator.requiresNameValidation("robot"))
        assertFalse(QueryTranslator.requiresNameValidation("Hatsune Miku"))
    }
    @Test fun preferencesPersistTextFeaturesAndPrivateBrowsingDoesNotAddThem() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context)
        val db = HistoryStore(context, "text-019-test.db")
        try {
            for (i in 1..2) db.recordInteraction(VideoItem("v$i", "robot", "creator$i", emptyList(), 0), "like", 3.0)
            assertTrue(db.preferenceProfile().textWeights.getValue("robot") > 0)
            prefs.privateBrowsing = true
            for (i in 3..4) db.recordInteraction(VideoItem("v$i", "forest", "creator$i", emptyList(), 0), "like", 3.0)
            assertFalse("forest" in db.preferenceProfile().textWeights)
        } finally { prefs.privateBrowsing = false; db.close() }
    }

    @Test fun versionTwelveUpgradeBackfillsSavedTitlesWithoutNewInteractions() {
        val context = RuntimeEnvironment.getApplication()
        val name = "text-upgrade-019.db"
        HistoryStore(context, name).use { old ->
            for (i in 1..2) {
                val item = VideoItem("old$i", "robot", "author$i", emptyList(), 10)
                old.recordWatch(item, 2000, 30000, false)
                old.recordInteraction(item, "like", 3.0)
            }
            val db = old.writableDatabase
            db.execSQL("CREATE TABLE legacy_interactions AS SELECT id,video_id,action,author,tags,weight,created_at,author_id,account_id FROM interactions")
            db.execSQL("DROP TABLE interactions")
            db.execSQL("ALTER TABLE legacy_interactions RENAME TO interactions")
            db.version = 12
        }
        HistoryStore(context, name).use { upgraded ->
            assertEquals(13, upgraded.readableDatabase.version)
            assertEquals(2, upgraded.recentHistory().size)
            assertTrue(upgraded.preferenceProfile().textWeights.getValue("robot") > 0)
            upgraded.readableDatabase.rawQuery("SELECT COUNT(*) FROM interactions WHERE body_features<>''", null).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0)) // no fabricated old description
            }
        }
    }
}
