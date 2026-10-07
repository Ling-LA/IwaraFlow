package com.ling.iwaraflow

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FollowersNavigationTest {
    @Test fun followerCardShowsIdentityAndOpensThatUsersProfile() {
        mockConstruction(IwaraApi::class.java) { api, _ -> `when`(api.isLoggedIn()).thenReturn(true) }.use { mocked ->
            val intent = Intent(RuntimeEnvironment.getApplication(), FollowingActivity::class.java).putExtra("followers", true)
            val controller = Robolectric.buildActivity(FollowingActivity::class.java, intent).setup()
            try {
                val activity = controller.get()
                val api = mocked.constructed().single()
                @Suppress("UNCHECKED_CAST")
                val meCallback = mockingDetails(api).invocations.first { it.method.name == "getCurrentUser" }.arguments[0] as (Result<IwaraAuthor>) -> Unit
                meCallback(Result.success(IwaraAuthor("me", "我的名字", "me")))
                @Suppress("UNCHECKED_CAST")
                val fansCallback = mockingDetails(api).invocations.first { it.method.name == "getFollowers" }.arguments[2] as (Result<FollowingPage>) -> Unit
                fansCallback(Result.success(FollowingPage(listOf(IwaraAuthor("fan-id", "示例粉丝", "sample_fan")), 1, false)))
                val list = activity.findViewById<RecyclerView>(R.id.followingList)
                val adapter = list.adapter as FollowingAuthorAdapter
                val holder = adapter.onCreateViewHolder(list, 0)
                adapter.onBindViewHolder(holder, 0)
                assertEquals("示例粉丝", holder.itemView.findViewById<TextView>(R.id.followingName).text)
                assertEquals("@sample_fan", holder.itemView.findViewById<TextView>(R.id.followingUsername).text)
                assertEquals("关注了你", holder.itemView.findViewById<TextView>(R.id.followingDescription).text)
                holder.itemView.performClick()
                val target = shadowOf(activity).nextStartedActivity
                assertEquals(AuthorActivity::class.java.name, target.component!!.className)
                assertEquals("fan-id", target.getStringExtra(AuthorActivity.EXTRA_ID))
                assertEquals("sample_fan", target.getStringExtra(AuthorActivity.EXTRA_USERNAME))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun knownAuthorIdStillOpensWhenUsernameIsUnavailable() {
        mockConstruction(IwaraApi::class.java) { api, _ -> `when`(api.isLoggedIn()).thenReturn(true) }.use {
            val controller = Robolectric.buildActivity(FollowingActivity::class.java).setup()
            try {
                val activity = controller.get()
                activity.javaClass.getDeclaredMethod("openAuthor", IwaraAuthor::class.java).apply { isAccessible = true }
                    .invoke(activity, IwaraAuthor("known-id", "示例作者", ""))
                val target = shadowOf(activity).nextStartedActivity
                assertEquals("known-id", target.getStringExtra(AuthorActivity.EXTRA_ID))
                assertEquals(AuthorActivity::class.java.name, target.component!!.className)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun bothPlaybackMenusKeepSettingsWithoutDuplicateInterestEntry() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                try {
                    val activity = controller.get()
                    activity.javaClass.getDeclaredMethod("showMainMenu").apply { isAccessible = true }.invoke(activity)
                    val main = ShadowDialog.getLatestDialog() as android.app.AlertDialog
                    val labels = (0 until main.listView.adapter.count).map { main.listView.adapter.getItem(it).toString() }
                    assertFalse(labels.contains("兴趣管理")); assertTrue(labels.contains("设置"))
                    val settingsIndex = labels.indexOf("设置")
                    main.listView.performItemClick(null, settingsIndex, settingsIndex.toLong())
                    assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
                    main.dismiss()
                    PageNavigation.show(activity)
                    val secondary = ShadowDialog.getLatestDialog() as AlertDialog
                    val otherLabels = (0 until secondary.listView.adapter.count).map { secondary.listView.adapter.getItem(it).toString() }
                    assertFalse(otherLabels.contains("兴趣管理")); assertTrue(otherLabels.contains("设置"))
                    assertTrue(otherLabels.contains("回到主页"))
                    secondary.dismiss()
                } finally { controller.pause().stop().destroy() }
            }
        }
    }
}
