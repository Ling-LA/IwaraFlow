package com.ling.iwaraflow

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipboardLinkHandlerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Before fun reset() {
        AppPrefs(context).autoOpenClipboardLinks = true
        AppPrefs(context).handledClipboardLink = ""
        clipboard.clearPrimaryClip()
    }
    private fun copy(text: String, timestamp: Long) {
        clipboard.setPrimaryClip(ClipData.newPlainText("share", text))
        // Android sets this timestamp whenever a new copy replaces the primary clip.
        android.content.ClipDescription::class.java.getDeclaredMethod("setTimestamp", Long::class.javaPrimitiveType)
            .invoke(clipboard.primaryClip!!.description, timestamp)
        android.content.ClipDescription::class.java.getDeclaredMethod("setConfidenceScores", Map::class.java)
            .invoke(clipboard.primaryClip!!.description, if (text.contains("https://")) mapOf("url" to 1f) else emptyMap<String, Float>())
    }

    @Test fun onlyLatestCopyIsReadAndRecopyIsRequiredEvenAfterRestart() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val handler = ClipboardLinkHandler()
        copy("https://iwara.tv/video/old", 100)
        copy("标题：视频\n链接：https://iwara.tv/video/latest", 200)
        handler.check(activity)
        assertEquals("https://www.iwara.tv/video/latest", shadowOf(activity).nextStartedActivity.getStringExtra(IwaraSharedLink.EXTRA_URL))
        ClipboardLinkHandler().check(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        copy("https://iwara.tv/video/latest", 300)
        handler.check(activity)
        assertNotNull(shadowOf(activity).nextStartedActivity)
        copy("最新复制的是普通文字", 400)
        handler.check(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test fun disabledAndOwnCopiesDoNotNavigateAndPreferencePersists() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        copy("https://iwara.tv/profile/alice", 100)
        AppPrefs(context).autoOpenClipboardLinks = false
        assertFalse(AppPrefs(context).autoOpenClipboardLinks)
        ClipboardLinkHandler().check(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        AppPrefs(context).autoOpenClipboardLinks = true
        clipboard.primaryClip!!.description.extras = PersistableBundle().apply { putBoolean(ClipboardLinkHandler.OWN_COPY, true) }
        ClipboardLinkHandler().check(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test fun ignoresAdditionalItemsAndDefaultsToEnabled() {
        context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(AppPrefs(context).autoOpenClipboardLinks)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        clipboard.setPrimaryClip(ClipData.newPlainText("share", "普通文字").apply {
            addItem(ClipData.Item("https://iwara.tv/video/older"))
        })
        ClipboardLinkHandler().check(controller.get())
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
