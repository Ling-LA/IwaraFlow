package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistPageSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val video = VideoItem("page-video", "Fixture", "Author", emptyList(), 1)
    private val page = PlaylistVideoPage(PlaylistFolder("account-a-folder", "A 收藏夹", 1), listOf(video), 1, false)

    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)

    private inner class Fixture(state: Bundle? = null) : Closeable {
        val prefs = AppPrefs(context).apply { accountId = "page-account-a" }
        val store = PlaylistStore(context)
        val responses = ArrayList<(Result<PlaylistVideoPage>) -> Unit>()
        private val apis = mockConstruction(IwaraApi::class.java)
        private val caches = mockConstruction(MediaPreloadCache::class.java)
        private val favorites = mockConstruction(PlaylistFavorites::class.java,
            withSettings().defaultAnswer { call ->
                when (call.method.name) {
                    "getStore" -> store
                    "getFolderPage" -> { responses += call.getArgument<(Result<PlaylistVideoPage>) -> Unit>(2); null }
                    else -> RETURNS_DEFAULTS.answer(call)
                }
            }) { _, _ -> }
        private val intent = Intent(context, SavedVideosActivity::class.java)
            .putExtra(SavedVideosActivity.EXTRA_KIND, SavedVideosActivity.KIND_PLAYLIST)
            .putExtra(SavedVideosActivity.EXTRA_PLAYLIST_ID, page.folder.id)
            .putExtra(SavedVideosActivity.EXTRA_PLAYLIST_TITLE, page.folder.title)
        val controller = Robolectric.buildActivity(SavedVideosActivity::class.java, intent).let { candidate ->
            try {
                // setup(Bundle) always invokes restoreInstanceState; a fresh page must use
                // setup() rather than asking Android to restore a null saved-state bundle.
                if (state == null) candidate.setup() else candidate.setup(state)
            } catch (error: Throwable) {
                runCatching { candidate.destroy() }
                favorites.close(); caches.close(); apis.close()
                throw error
            }
        }
        val activity get() = controller.get()
        val count get() = activity.findViewById<RecyclerView>(R.id.savedVideos).adapter!!.itemCount
        fun switchAccount() { prefs.accountId = "page-account-b" }
        override fun close() {
            try { controller.pause().stop().destroy() }
            finally { favorites.close(); caches.close(); apis.close() }
        }
    }

    @Test fun oldPageCannotReloadAPlaylistUsingTheNewAccount() {
        Fixture().use { f ->
            assertEquals(1, f.responses.size)
            f.responses.single()(Result.success(page))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, f.count)
            f.switchAccount()
            invoke(f.activity, "loadLibraryPage", true)
            assertTrue(f.activity.isFinishing)
            assertEquals(0, f.count)
            assertEquals("An old page must not issue a second request under B", 1, f.responses.size)
            assertFalse(f.store.isFavorite(video.id))
        }
    }

    @Test fun latePageResultAfterAccountSwitchIsDiscardedAndClosesThePage() {
        Fixture().use { f ->
            f.switchAccount()
            f.responses.single()(Result.success(page))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(f.activity.isFinishing)
            assertEquals(0, f.count)
            assertEquals(1, f.responses.size)
        }
    }

    @Test fun returningFromAnotherAccountDoesNotResumeOldPlaylistPlayback() {
        Fixture().use { f ->
            f.responses.single()(Result.success(page))
            shadowOf(Looper.getMainLooper()).idle()
            f.controller.pause()
            f.switchAccount()
            f.controller.resume()
            assertTrue(f.activity.isFinishing)
            assertEquals(0, f.count)
            assertEquals(1, f.responses.size)
        }
    }

    @Test fun restoredPageKeepsItsOriginalAccountRatherThanAdoptingTheCurrentOne() {
        val state = Bundle().apply {
            putString("playlist_page_account", "different-original-account")
            putLong("playlist_page_session", SecureSessionStore.accountRevision)
        }
        Fixture(state).use { f ->
            assertTrue(f.activity.isFinishing)
            assertEquals(0, f.count)
            assertTrue("Restored foreign page must make no playlist request", f.responses.isEmpty())
        }
    }

    @Test fun loggingOutInvalidatesThePageEvenWhenCachedAccountNameIsUnchanged() {
        Fixture().use { f ->
            SecureSessionStore(context).clearAuthentication()
            invoke(f.activity, "loadLibraryPage", true)
            assertTrue(f.activity.isFinishing)
            assertEquals(0, f.count)
            assertEquals(1, f.responses.size)
        }
    }
}
