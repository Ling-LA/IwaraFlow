package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PlaylistStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val item = VideoItem("video", "景色", "Author", listOf("nature"), 1, thumbnailUrl = "https://i.iwara.tv/example.jpg")
    @Before fun setup() {
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).accountId = "account-a"
    }
    @Test fun membershipsSurviveReopenAndAreAccountIsolated() {
        val store = PlaylistStore(context); val snapshot = store.snapshot()
        store.confirmWrite(snapshot, item, PlaylistFolder("one", "旅行"), true)
        assertTrue(PlaylistStore(context).isFavorite("video"))
        AppPrefs(context).accountId = "account-b"
        assertFalse(store.isFavorite("video")); assertTrue(store.favoriteIds(listOf("video"), snapshot).isEmpty())
        assertFalse(store.confirmWrite(snapshot, item, PlaylistFolder("two", "不应写入"), true))
        AppPrefs(context).accountId = "account-a"
        assertEquals(setOf("one"), store.memberships("video"))
    }
    @Test fun removingOneFolderRetainsOtherMembershipAndDeletedFolderIsCleared() {
        val store = PlaylistStore(context); val s = store.snapshot()
        val one = PlaylistFolder("one", "旅行"); val two = PlaylistFolder("two", "稍后")
        store.confirmWrite(s, item, one, true); store.confirmWrite(s, item, two, true)
        store.confirmWrite(s, item, one, false)
        assertTrue(store.isFavorite(item.id)); assertEquals(setOf("two"), store.memberships(item.id))
        store.replaceFolders(s, listOf(one)); assertFalse(store.isFavorite(item.id))
    }
    @Test fun completeFolderRefreshRemovesStaleItemsButPartialPageDoesNot() {
        val store = PlaylistStore(context); val s = store.snapshot(); val folder = PlaylistFolder("folder", "旅行")
        store.confirmWrite(s, item, folder, true)
        store.cachePage(s, 0, PlaylistVideoPage(folder, emptyList(), 100, true))
        assertTrue(store.isFavorite(item.id))
        store.cachePage(s, 0, PlaylistVideoPage(folder, emptyList(), 0, false))
        assertFalse(store.isFavorite(item.id))
    }
    @Test fun cachedPagesPreserveMetadataAndWritesInvalidateOldPages() {
        val store = PlaylistStore(context); val s = store.snapshot(); val folder = PlaylistFolder("folder", "旅行")
        store.cachePage(s, 0, PlaylistVideoPage(folder, listOf(item), 1, false))
        val cached = PlaylistStore(context).page("folder", 0)!!
        assertTrue(cached.offline); assertEquals(item.thumbnailUrl, cached.items.single().thumbnailUrl)
        store.confirmWrite(s, item, folder, false)
        assertNull(store.page("folder", 0)); assertFalse(store.isFavorite(item.id))
    }
    @Test fun migrationCheckpointPersistsAndIsNotClaimedBySecondAccount() {
        val store = PlaylistStore(context); val s = store.snapshot()
        assertTrue(store.claimMigration(s)); assertTrue(store.markMigrated(s, item.id))
        assertTrue(PlaylistStore(context).migrated(s, item.id))
        AppPrefs(context).accountId = "account-b"
        assertFalse(store.claimMigration(store.snapshot())); assertFalse(store.migrated(store.snapshot(), item.id))
    }
}
