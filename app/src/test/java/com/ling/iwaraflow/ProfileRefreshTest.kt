package com.ling.iwaraflow

import android.content.DialogInterface
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileRefreshTest {
    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(texts(view.getChildAt(it))) }
    }
    private fun click(view: View, text: String) = texts(view).first { it.text.toString() == text }.performClick()

    @Test fun largeSystemProfileNeverHidesManualControlsAndSearchIsBounded() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("iwaraflow.db")
        HistoryStore(app).use { history ->
            repeat(100) { i -> history.recordInteraction(VideoItem("$i", "", "a", listOf("tag_$i"), 0), "like", 1.0) }
        }
        val controller = Robolectric.buildActivity(InterestActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            assertTrue(texts(root).any { it.text.toString() == "添加感兴趣" })
            assertFalse(texts(root).any { it.text.toString() == "系统兴趣管理" })
            click(root, "系统兴趣")
            assertTrue(texts(root).any { it.text.toString() == "系统兴趣管理" })
            assertEquals(24, texts(root).count { it.text.startsWith("#tag_") })
            val search = texts(root).filterIsInstance<EditText>().single()
            search.setText("tag_99")
            assertEquals(1, texts(root).count { it.text.startsWith("#tag_") })
            click(root, "手动兴趣")
            assertTrue(texts(root).any { it.text.toString() == "添加不感兴趣" })
            click(root, "已屏蔽")
            assertTrue(texts(root).any { it.text.toString() == "已屏蔽的作者和标签" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun myProfileUsesAuthorHeaderAndKeepsEmptyDescriptionHidden() {
        val app = RuntimeEnvironment.getApplication()
        SecureSessionStore(app).clear()
        val controller = Robolectric.buildActivity(MyActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.bindProfile(IwaraAuthor("fixture", "示例作者", "fixture_user", "null", "", false, false, "", 0))
            assertEquals("示例作者", activity.findViewById<TextView>(R.id.authorName).text)
            assertEquals("@fixture_user", activity.findViewById<TextView>(R.id.authorUsername).text)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.authorDescription).visibility)
            val following = activity.findViewById<TextView>(R.id.followButton)
            val fans = activity.findViewById<TextView>(R.id.friendButton)
            val favorites = activity.findViewById<TextView>(R.id.profileFavoritesButton)
            assertSame(following.parent, fans.parent); assertSame(fans.parent, favorites.parent)
            assertEquals(listOf("我的关注", "我的粉丝", "我的收藏"), listOf(following, fans, favorites).map { it.text.toString() })
            assertEquals(View.VISIBLE, favorites.visibility)
            val upload = activity.findViewById<TextView>(R.id.authorShare)
            assertEquals("上传", upload.text)
            assertSame(activity.findViewById<View>(R.id.authorBack).parent, upload.parent)
            assertEquals("暂无上传视频", MyActivity.worksStatus(0, false))
            activity.bindProfile(IwaraAuthor("fixture", "示例作者", "fixture_user", "这是简介", "", false, false, "", 0))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.authorDescription).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun danmakuTravelsFromRightToLeftAndAvoidsPageChrome() {
        assertEquals(400f, DanmakuView.horizontalPosition(400f, 80f, 0f), 0f)
        assertEquals(160f, DanmakuView.horizontalPosition(400f, 80f, .5f), 0f)
        assertEquals(-80f, DanmakuView.horizontalPosition(400f, 80f, 1f), 0f)
        val app = RuntimeEnvironment.getApplication()
        val adapter = VideoAdapter(mock(IwaraApi::class.java), mock(HistoryStore::class.java),
            AppPrefs(app), mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        try {
            adapter.replace(listOf(VideoItem("fixture", "", "", emptyList(), 0)))
            val holder = adapter.onCreateViewHolder(FrameLayout(app), 0)
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.layout(0, 0, 400, 800)
            val danmaku = (0 until (holder.itemView as ViewGroup).childCount)
                .map { (holder.itemView as ViewGroup).getChildAt(it) }.filterIsInstance<DanmakuView>().single()
            adapter.setOverlayTopInset(100)
            assertTrue(danmaku.paddingTop >= 100)
            adapter.setFullscreen(true)
            assertEquals(0, danmaku.paddingTop)
        } finally { adapter.releaseAll() }
    }

    @Test fun firstLaunchOverlayPromptCanBeDeclinedWithoutRecurring() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(AppPrefs.FILE, 0).edit().remove(OverlayPermissionPrompt.KEY_SHOWN).commit()
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val first = OverlayPermissionPrompt.showOnce(controller.get())!!
            first.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNull(OverlayPermissionPrompt.showOnce(controller.get()))
            controller.recreate()
            assertNull(OverlayPermissionPrompt.showOnce(controller.get()))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun firstLaunchGrantOpensOnlyThisAppsOverlaySettings() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(AppPrefs.FILE, 0).edit().remove(OverlayPermissionPrompt.KEY_SHOWN).commit()
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            OverlayPermissionPrompt.showOnce(activity)!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
            assertEquals("package:${activity.packageName}", intent.data.toString())
            assertNull(OverlayPermissionPrompt.showOnce(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun grantedOverlayPermissionDoesNotShowPrompt() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(AppPrefs.FILE, 0).edit().remove(OverlayPermissionPrompt.KEY_SHOWN).commit()
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try { assertNull(OverlayPermissionPrompt.showOnce(controller.get())) }
        finally { ShadowSettings.setCanDrawOverlays(false); controller.pause().stop().destroy() }
    }
}
