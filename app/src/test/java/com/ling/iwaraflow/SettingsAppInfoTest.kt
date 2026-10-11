package com.ling.iwaraflow

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsAppInfoTest {
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun text(root: View, label: String) = views(root).filterIsInstance<TextView>().first { it.text.toString() == label }
    private fun options(list: ListView) = (0 until list.adapter.count).map {
        list.adapter.getItem(it).toString()
    }

    @Test fun settingsChecksManuallyAndReleasesUpdaterOnExit() {
        mockConstruction(UpdateManager::class.java).use { mocked ->
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val activity = controller.get()
            val updates = mocked.constructed().single()
            verify(updates).tryContinueInstall()
            verify(updates, never()).check(anyBoolean())
            (text(activity.window.decorView, "检查更新").parent as View).performClick()
            verify(updates).check(true)
            assertFalse(activity.isFinishing)
            controller.pause().stop().restart().resume()
            verify(updates, times(2)).tryContinueInstall()
            controller.pause().stop().destroy()
            verify(updates).close()
        }
    }

    @Test fun settingsAboutStillOpensGithubWithoutLeavingTheSettingsPage() {
        mockConstruction(UpdateManager::class.java).use {
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val activity = controller.get()
            (text(activity.window.decorView, "关于").parent as View).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val link = views(dialog.window!!.decorView).first { it.contentDescription == "打开 IwaraFlow GitHub 项目" }
            link.performClick()
            assertEquals(AboutDialog.GITHUB, shadowOf(activity).nextStartedActivity.dataString)
            assertFalse(activity.isFinishing)
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun bothPlaybackMenusKeepSettingsAndRemoveAppInfoShortcuts() {
        mockConstruction(IwaraApi::class.java).use {
            mockConstruction(UpdateManager::class.java).use {
                val controller = Robolectric.buildActivity(MainActivityV3::class.java).setup()
                val activity = controller.get()
                activity.findViewById<View>(R.id.btnMenu).performClick()
                val mainDialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
                assertTrue("设置" in options(mainDialog.listView))
                assertTrue("重新加载当前流" in options(mainDialog.listView))
                assertFalse("检查更新" in options(mainDialog.listView))
                assertFalse("关于" in options(mainDialog.listView))
                mainDialog.dismiss()
                PageNavigation.show(activity)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val labels = options(dialog.listView)
                assertTrue("回到主页" in labels)
                assertFalse("检查更新" in labels)
                assertFalse("关于" in labels)
                val position = labels.indexOf("设置")
                assertTrue(position >= 0)
                dialog.listView.performItemClick(null, position, position.toLong())
                assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
                dialog.dismiss()
                controller.pause().stop().destroy()
            }
        }
    }
}
