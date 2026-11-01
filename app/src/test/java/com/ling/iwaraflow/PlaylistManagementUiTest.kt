package com.ling.iwaraflow

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable

/** Exercise the real dialogs; mocked controller boundaries keep destructive operations off the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistManagementUiTest {
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PlaylistStore.FILE, 0).edit().clear().commit()
        AppPrefs(context).accountId = "management-ui-a"
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun button(dialog: Dialog, text: String) = views(dialog.window!!.decorView)
        .filterIsInstance<TextView>().single { it.text.toString() == text }

    private class Fixture : Closeable {
        private val activityController = Robolectric.buildActivity(Activity::class.java)
        val activity = activityController.get().apply { setTheme(R.style.Theme_IwaraFlow) }
        val store = PlaylistStore(activity)
        val source = PlaylistFolder("source", "旅行收藏", 2)
        val target = PlaylistFolder("target", "稍后观看", 1)
        val item = VideoItem("selected-video", "中性示例", "示例作者", emptyList(), 10)
        val mutations = mutableListOf<List<String>>()
        var folderRefreshes = 0
        var changes = 0
        var videoResult: Result<PlaylistFavorites.Change>? = null
        var renameReply: ((Result<PlaylistFolder>) -> Unit)? = null
        var deleteReply: ((Result<Unit>) -> Unit)? = null
        var videoReply: ((Result<PlaylistFavorites.Change>) -> Unit)? = null
        var createReply: ((Result<PlaylistFolder>) -> Unit)? = null
        var pickerReply: ((Result<List<PlaylistFolder>>) -> Unit)? = null
        var selectionReply: ((Result<PlaylistFavorites.Change>) -> Unit)? = null
        val favorites = mock(PlaylistFavorites::class.java) { call ->
            // Kotlin internal methods (loadPicker) carry the module suffix on the JVM.
            when (call.method.name.substringBefore('$')) {
                "getStore" -> store
                "renameFolder" -> {
                    mutations += listOf("rename", call.getArgument<String>(0), call.getArgument<String>(1))
                    renameReply = call.getArgument(2)
                    null
                }
                "deleteFolder" -> {
                    mutations += listOf("delete", call.getArgument<String>(0))
                    deleteReply = call.getArgument(1)
                    null
                }
                "removeFromFolder" -> {
                    mutations += listOf("remove", call.getArgument<VideoItem>(0).id, call.getArgument<String>(1))
                    videoReply = call.getArgument(2)
                    null
                }
                "moveToFolder" -> {
                    mutations += listOf("move", call.getArgument<VideoItem>(0).id,
                        call.getArgument<String>(1), call.getArgument<String>(2))
                    videoReply = call.getArgument(3)
                    null
                }
                "refreshFolders" -> {
                    folderRefreshes++
                    call.getArgument<(Result<List<PlaylistFolder>>) -> Unit>(0)(Result.success(listOf(source, target)))
                    null
                }
                "createFolder" -> {
                    mutations += listOf("create", call.getArgument<String>(0))
                    createReply = call.getArgument(1)
                    null
                }
                "loadPicker" -> {
                    pickerReply = call.getArgument(1)
                    null
                }
                "applySelection" -> {
                    mutations += listOf("apply", call.getArgument<VideoItem>(0).id,
                        call.getArgument<Set<String>>(1).sorted().joinToString(","))
                    selectionReply = call.getArgument(2)
                    null
                }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }

        init { activityController.setup() }
        fun folderMenu(): AlertDialog {
            PlaylistManagement.folderMenu(activity, favorites, source) { changes++ }
            return latest()
        }
        fun videoMenu(): AlertDialog {
            PlaylistManagement.videoMenu(activity, favorites, source, item) { videoResult = it; changes++ }
            return latest()
        }
        fun picker(): Dialog {
            PlaylistPicker.show(activity, favorites, item) { videoResult = it; changes++ }
            shadowOf(Looper.getMainLooper()).idle()
            return ShadowDialog.getLatestDialog().also { assertTrue(it.isShowing) }
        }
        fun loadPicker() {
            assertNotNull("The picker must request its account-scoped folder list", pickerReply)
            pickerReply!!(Result.success(listOf(source.copy(added = true), target)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun latest(): AlertDialog {
            shadowOf(Looper.getMainLooper()).idle()
            return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
        }
        fun choose(dialog: AlertDialog, label: String) {
            val index = labels(dialog).indexOf(label)
            assertTrue("Menu must contain $label", index >= 0)
            dialog.listView.performItemClick(null, index, index.toLong())
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun labels(dialog: AlertDialog) = (0 until dialog.listView.adapter.count)
            .map { dialog.listView.adapter.getItem(it).toString() }
        fun switchAccount() { AppPrefs(activity).accountId = "management-ui-b" }
        override fun close() {
            ShadowDialog.getLatestDialog()?.dismiss()
            activityController.pause().stop().destroy()
        }
    }

    @Test fun renameIsPrefilledRejectsBlankAndSubmitsTrimmedNameOnlyOnSave() {
        Fixture().use { f ->
            val menu = f.folderMenu()
            assertEquals(listOf("重命名", "删除收藏夹"), f.labels(menu))
            assertTrue(f.mutations.isEmpty())
            f.choose(menu, "重命名")
            val dialog = f.latest()
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            assertEquals(f.source.title, input.text.toString())
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            input.setText("   "); save.performClick()
            assertNotNull(input.error)
            assertTrue(dialog.isShowing); assertTrue(f.mutations.isEmpty())
            input.setText("  新旅行收藏  "); save.performClick()
            assertEquals(listOf(listOf("rename", f.source.id, "新旅行收藏")), f.mutations)
            assertFalse("An outstanding write must disable repeated saves", save.isEnabled)
            assertEquals(0, f.changes)
            f.renameReply!!(Result.success(f.source.copy(title = "新旅行收藏")))
            assertFalse(dialog.isShowing); assertEquals(1, f.changes)
        }
    }

    @Test fun failedRenameKeepsEnteredNameAndAllowsRetry() {
        Fixture().use { f ->
            f.choose(f.folderMenu(), "重命名")
            val dialog = f.latest()
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            input.setText("稍后整理")
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.performClick()
            f.renameReply!!(Result.failure(IllegalStateException("网络暂不可用")))
            assertTrue(dialog.isShowing); assertTrue(save.isEnabled)
            assertEquals("稍后整理", input.text.toString())
            assertEquals("网络暂不可用", input.error.toString())
            save.performClick()
            assertEquals(2, f.mutations.size)
            assertEquals(listOf("rename", f.source.id, "稍后整理"), f.mutations.last())
        }
    }

    @Test fun deletingFolderRequiresSeparateConfirmationAndCancelDoesNotWrite() {
        Fixture().use { f ->
            f.choose(f.folderMenu(), "删除收藏夹")
            var confirmation = f.latest()
            assertTrue("Opening the destructive confirmation must not delete anything", f.mutations.isEmpty())
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirmation.isShowing); assertTrue(f.mutations.isEmpty())
            f.choose(f.folderMenu(), "删除收藏夹")
            confirmation = f.latest()
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(listOf(listOf("delete", f.source.id)), f.mutations)
            assertFalse(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertEquals(0, f.changes)
            f.deleteReply!!(Result.success(Unit))
            assertFalse(confirmation.isShowing); assertEquals(1, f.changes)
        }
    }

    @Test fun removeUsesOnlyCurrentFolderAndWaitsForAcknowledgement() {
        Fixture().use { f ->
            val menu = f.videoMenu()
            assertEquals(listOf("从当前收藏夹移除", "移动到其他收藏夹"), f.labels(menu))
            f.choose(menu, "从当前收藏夹移除")
            val progress = f.latest()
            assertEquals(listOf(listOf("remove", f.item.id, f.source.id)), f.mutations)
            assertEquals(0, f.folderRefreshes); assertNull(f.videoResult)
            f.videoReply!!(Result.success(PlaylistFavorites.Change(saved = true, changed = true)))
            assertFalse(progress.isShowing)
            assertTrue("Other folders may still contain this video", f.videoResult!!.getOrThrow().saved)
            assertEquals(1, f.changes)
        }
    }

    @Test fun moveExcludesSourceAndSubmitsSelectedTargetOnly() {
        Fixture().use { f ->
            f.choose(f.videoMenu(), "移动到其他收藏夹")
            val targetMenu = f.latest()
            assertEquals(listOf(f.target.title, "＋ 新建收藏夹"), f.labels(targetMenu))
            assertEquals(1, f.folderRefreshes); assertTrue(f.mutations.isEmpty())
            f.choose(targetMenu, f.target.title)
            val progress = f.latest()
            assertEquals(listOf(listOf("move", f.item.id, f.source.id, f.target.id)), f.mutations)
            assertNull(f.videoResult)
            f.videoReply!!(Result.success(PlaylistFavorites.Change(saved = true, changed = true)))
            assertFalse(progress.isShowing); assertTrue(f.videoResult!!.isSuccess)
        }
    }

    @Test fun changedAccountCannotSubmitAnAlreadyOpenRename() {
        Fixture().use { f ->
            f.choose(f.folderMenu(), "重命名")
            val dialog = f.latest()
            views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("不可提交")
            f.switchAccount()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertTrue(f.mutations.isEmpty()); assertEquals(0, f.changes)
        }
    }

    @Test fun changedAccountCannotConfirmDeletion() {
        Fixture().use { f ->
            f.choose(f.folderMenu(), "删除收藏夹")
            val dialog = f.latest()
            f.switchAccount()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertTrue(f.mutations.isEmpty()); assertEquals(0, f.changes)
        }
    }

    @Test fun changedAccountCannotChooseTargetFromAnOldMoveMenu() {
        Fixture().use { f ->
            f.choose(f.videoMenu(), "移动到其他收藏夹")
            val targets = f.latest()
            f.switchAccount()
            f.choose(targets, f.target.title)
            assertTrue(f.mutations.isEmpty())
            assertNull(f.videoResult); assertEquals(0, f.changes)
        }
    }

    @Test fun createDialogOpenedForOldAccountCannotSubmitAfterSwitch() {
        Fixture().use { f ->
            val created = mutableListOf<String>()
            PlaylistPicker.createDialog(f.activity, f.favorites) { created += it.id }
            val dialog = f.latest()
            views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("旧账号新夹")
            f.switchAccount()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertTrue(f.mutations.isEmpty()); assertTrue(created.isEmpty())
        }
    }

    @Test fun delayedCreateSuccessCannotNotifyTheNewAccount() {
        Fixture().use { f ->
            val created = mutableListOf<String>()
            PlaylistPicker.createDialog(f.activity, f.favorites) { created += it.id }
            val dialog = f.latest()
            views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("待创建")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(listOf(listOf("create", "待创建")), f.mutations)
            f.switchAccount()
            f.createReply!!(Result.success(PlaylistFolder("created-for-a", "待创建")))
            assertFalse(dialog.isShowing)
            assertTrue("An old request's success cannot continue the new account's workflow", created.isEmpty())
        }
    }

    @Test fun delayedCreateFromMoveMenuCannotStartMoveAfterAccountSwitch() {
        Fixture().use { f ->
            f.choose(f.videoMenu(), "移动到其他收藏夹")
            f.choose(f.latest(), "＋ 新建收藏夹")
            val create = f.latest()
            views(create.window!!.decorView).filterIsInstance<EditText>().single().setText("移动目的地")
            create.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            f.switchAccount()
            f.createReply!!(Result.success(PlaylistFolder("created-for-a", "移动目的地")))
            assertEquals("A late create must not trigger moveToFolder for another account",
                listOf(listOf("create", "移动目的地")), f.mutations)
            assertFalse(create.isShowing); assertNull(f.videoResult)
        }
    }

    @Test fun pickerLoadedForOldAccountCannotApplySelectionAfterSwitch() {
        Fixture().use { f ->
            val picker = f.picker(); f.loadPicker()
            val done = button(picker, "完成")
            assertTrue(done.isEnabled)
            assertEquals(2, views(picker.window!!.decorView).filterIsInstance<CheckBox>().count())
            f.switchAccount()
            done.performClick()
            assertFalse(picker.isShowing)
            assertTrue(f.mutations.isEmpty()); assertNull(f.videoResult)
        }
    }

    @Test fun pickerLoadedForOldAccountCannotOpenCreateDialogAfterSwitch() {
        Fixture().use { f ->
            val picker = f.picker(); f.loadPicker()
            val create = button(picker, "＋ 新建收藏夹")
            assertTrue(create.isEnabled)
            f.switchAccount()
            create.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(picker.isShowing)
            val latest = ShadowDialog.getLatestDialog()
            assertFalse("The stale picker must not open a new-account creation dialog", latest is AlertDialog && latest.isShowing)
            assertTrue(f.mutations.isEmpty())
        }
    }

    @Test fun delayedPickerLoadCannotDisplayOldAccountFolders() {
        Fixture().use { f ->
            val picker = f.picker()
            assertFalse(button(picker, "完成").isEnabled)
            f.switchAccount()
            f.loadPicker()
            assertFalse(picker.isShowing)
            assertTrue(views(picker.window!!.decorView).filterIsInstance<CheckBox>().none())
            assertTrue(f.mutations.isEmpty()); assertNull(f.videoResult)
        }
    }

    @Test fun delayedPickerSaveCannotReportSuccessForNewAccount() {
        Fixture().use { f ->
            val picker = f.picker(); f.loadPicker()
            button(picker, "完成").performClick()
            assertEquals(listOf(listOf("apply", f.item.id, f.source.id)), f.mutations)
            f.switchAccount()
            f.selectionReply!!(Result.success(PlaylistFavorites.Change(saved = true, changed = true)))
            assertFalse(picker.isShowing)
            assertNull(f.videoResult); assertEquals(0, f.changes)
        }
    }
}
