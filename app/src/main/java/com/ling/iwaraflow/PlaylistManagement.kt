package com.ling.iwaraflow

import android.app.Activity
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Long-press actions scoped to the account that opened the menu. */
internal object PlaylistManagement {
    private fun alive(activity: Activity) = !activity.isFinishing && !activity.isDestroyed
    private fun current(activity: Activity, favorites: PlaylistFavorites, session: PlaylistStore.Snapshot): Boolean {
        if (!alive(activity)) return false
        if (favorites.store.current(session)) return true
        Toast.makeText(activity, UiText.text(R.string.ui_e213f78627c3, "账号已切换，请重新打开收藏夹"), Toast.LENGTH_LONG).show()
        return false
    }

    fun folderMenu(activity: Activity, favorites: PlaylistFavorites, folder: PlaylistFolder, changed: () -> Unit) {
        val session = favorites.store.snapshot()
        if (!current(activity, favorites, session)) return
        AlertDialog.Builder(activity).setTitle(folder.title).setItems(arrayOf(UiText.text(R.string.ui_0d0cbac2eee5, "重命名"), UiText.text(R.string.ui_920fe6ef0593, "删除收藏夹"))) { _, which ->
            if (!current(activity, favorites, session)) return@setItems
            if (which == 0) rename(activity, favorites, folder, session, changed)
            else delete(activity, favorites, folder, session, changed)
        }.setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).show()
    }

    private fun rename(activity: Activity, favorites: PlaylistFavorites, folder: PlaylistFolder,
        session: PlaylistStore.Snapshot, changed: () -> Unit) {
        val input = EditText(activity).apply {
            hint = UiText.text(R.string.ui_b161b3f76a60, "收藏夹名称"); setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(100))
            setText(folder.title); setSelection(text.length); contentDescription = UiText.text(R.string.ui_7deca0e7c27f, "修改收藏夹名称")
        }
        val content = LinearLayout(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0); addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(UiText.text(R.string.ui_a8d2648ee922, "重命名收藏夹")).setView(content)
            .setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).setPositiveButton(UiText.text(R.string.ui_a3030bf8f16d, "保存"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current(activity, favorites, session)) { dialog.dismiss(); return@setOnClickListener }
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = UiText.text(R.string.ui_c20a8f9fbab1, "请输入收藏夹名称"); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                favorites.renameFolder(folder.id, name) { result ->
                    if (!alive(activity)) return@renameFolder
                    changed()
                    if (!dialog.isShowing) return@renameFolder
                    result.onSuccess { dialog.dismiss(); Toast.makeText(activity, UiText.text(R.string.ui_d83df37d5aae, "收藏夹已重命名"), Toast.LENGTH_SHORT).show() }
                        .onFailure { input.error = it.message; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
    }

    private fun delete(activity: Activity, favorites: PlaylistFavorites, folder: PlaylistFolder,
        session: PlaylistStore.Snapshot, changed: () -> Unit) {
        val dialog = AlertDialog.Builder(activity).setTitle(UiText.text(R.string.ui_880df234e233, "删除收藏夹？"))
            .setMessage(UiText.text(R.string.ui_9068284118bc, "将从 Iwara 删除「%1\$s」及其收藏记录。视频本身和其他收藏夹中的副本不会删除，此操作无法撤销。", folder.title))
            .setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).setPositiveButton(UiText.text(R.string.ui_2f9daa828907, "删除"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current(activity, favorites, session)) { dialog.dismiss(); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                favorites.deleteFolder(folder.id) { result ->
                    if (!alive(activity)) return@deleteFolder
                    changed()
                    result.onSuccess { dialog.dismiss(); Toast.makeText(activity, UiText.text(R.string.ui_5d90c208e01e, "收藏夹已删除"), Toast.LENGTH_SHORT).show() }
                        .onFailure {
                            if (dialog.isShowing) dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
        dialog.show()
    }

    fun videoMenu(activity: Activity, favorites: PlaylistFavorites, source: PlaylistFolder, item: VideoItem,
        changed: (Result<PlaylistFavorites.Change>) -> Unit) {
        val session = favorites.store.snapshot()
        if (!current(activity, favorites, session)) return
        fun perform(target: PlaylistFolder?) {
            if (!current(activity, favorites, session)) return
            val progress = AlertDialog.Builder(activity).setMessage(if (target == null) UiText.text(R.string.ui_1722f3de9db8, "正在移除…") else UiText.text(R.string.ui_c133a47d40c3, "正在移动…"))
                .setCancelable(false).show()
            val complete: (Result<PlaylistFavorites.Change>) -> Unit = { result ->
                if (alive(activity)) {
                    progress.dismiss(); changed(result)
                    Toast.makeText(activity, result.fold({ if (target == null) UiText.text(R.string.ui_3034b88ebb13, "已从当前收藏夹移除") else UiText.text(R.string.ui_86dbe9dd4323, "已移动到「%1\$s」", target.title) },
                        { it.message ?: UiText.text(R.string.ui_87c94c6dfb7b, "收藏变更未完成，请重试") }), Toast.LENGTH_LONG).show()
                }
            }
            if (target == null) favorites.removeFromFolder(item, source.id, complete)
            else favorites.moveToFolder(item, source.id, target.id, complete)
        }
        AlertDialog.Builder(activity).setTitle(item.title)
            .setItems(arrayOf(UiText.text(R.string.ui_4b10de6d4962, "从当前收藏夹移除"), UiText.text(R.string.ui_6bdc3a8b8233, "移动到其他收藏夹"))) { _, which ->
                if (!current(activity, favorites, session)) return@setItems
                if (which == 0) perform(null)
                else {
                    val loading = AlertDialog.Builder(activity).setMessage(UiText.text(R.string.ui_888f7a6a6d73, "正在读取收藏夹…")).setCancelable(false).show()
                    favorites.refreshFolders { result ->
                        if (!alive(activity)) return@refreshFolders
                        loading.dismiss()
                        if (!current(activity, favorites, session)) return@refreshFolders
                        result.onSuccess { rows ->
                            val targets = rows.filter { it.id != source.id }
                            val labels = targets.map { it.title } + UiText.text(R.string.ui_669460aacc70, "＋ 新建收藏夹")
                            AlertDialog.Builder(activity).setTitle(UiText.text(R.string.ui_8af624e17333, "移动到收藏夹")).setItems(labels.toTypedArray()) targetChoice@{ _, index ->
                                if (!current(activity, favorites, session)) return@targetChoice
                                if (index < targets.size) perform(targets[index])
                                else PlaylistPicker.createDialog(activity, favorites) { perform(it) }
                            }.setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).show()
                        }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }.setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).show()
    }
}
