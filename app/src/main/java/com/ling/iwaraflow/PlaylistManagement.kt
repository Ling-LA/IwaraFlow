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
        Toast.makeText(activity, "账号已切换，请重新打开收藏夹", Toast.LENGTH_LONG).show()
        return false
    }

    fun folderMenu(activity: Activity, favorites: PlaylistFavorites, folder: PlaylistFolder, changed: () -> Unit) {
        val session = favorites.store.snapshot()
        if (!current(activity, favorites, session)) return
        AlertDialog.Builder(activity).setTitle(folder.title).setItems(arrayOf("重命名", "删除收藏夹")) { _, which ->
            if (!current(activity, favorites, session)) return@setItems
            if (which == 0) rename(activity, favorites, folder, session, changed)
            else delete(activity, favorites, folder, session, changed)
        }.setNegativeButton("取消", null).show()
    }

    private fun rename(activity: Activity, favorites: PlaylistFavorites, folder: PlaylistFolder,
        session: PlaylistStore.Snapshot, changed: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "收藏夹名称"; setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(100))
            setText(folder.title); setSelection(text.length); contentDescription = "修改收藏夹名称"
        }
        val content = LinearLayout(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0); addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("重命名收藏夹").setView(content)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current(activity, favorites, session)) { dialog.dismiss(); return@setOnClickListener }
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = "请输入收藏夹名称"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                favorites.renameFolder(folder.id, name) { result ->
                    if (!alive(activity)) return@renameFolder
                    changed()
                    if (!dialog.isShowing) return@renameFolder
                    result.onSuccess { dialog.dismiss(); Toast.makeText(activity, "收藏夹已重命名", Toast.LENGTH_SHORT).show() }
                        .onFailure { input.error = it.message; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
    }

    private fun delete(activity: Activity, favorites: PlaylistFavorites, folder: PlaylistFolder,
        session: PlaylistStore.Snapshot, changed: () -> Unit) {
        val dialog = AlertDialog.Builder(activity).setTitle("删除收藏夹？")
            .setMessage("将从 Iwara 删除「${folder.title}」及其收藏记录。视频本身和其他收藏夹中的副本不会删除，此操作无法撤销。")
            .setNegativeButton("取消", null).setPositiveButton("删除", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current(activity, favorites, session)) { dialog.dismiss(); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                favorites.deleteFolder(folder.id) { result ->
                    if (!alive(activity)) return@deleteFolder
                    changed()
                    result.onSuccess { dialog.dismiss(); Toast.makeText(activity, "收藏夹已删除", Toast.LENGTH_SHORT).show() }
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
            val progress = AlertDialog.Builder(activity).setMessage(if (target == null) "正在移除…" else "正在移动…")
                .setCancelable(false).show()
            val complete: (Result<PlaylistFavorites.Change>) -> Unit = { result ->
                if (alive(activity)) {
                    progress.dismiss(); changed(result)
                    Toast.makeText(activity, result.fold({ if (target == null) "已从当前收藏夹移除" else "已移动到「${target.title}」" },
                        { it.message ?: "收藏变更未完成，请重试" }), Toast.LENGTH_LONG).show()
                }
            }
            if (target == null) favorites.removeFromFolder(item, source.id, complete)
            else favorites.moveToFolder(item, source.id, target.id, complete)
        }
        AlertDialog.Builder(activity).setTitle(item.title)
            .setItems(arrayOf("从当前收藏夹移除", "移动到其他收藏夹")) { _, which ->
                if (!current(activity, favorites, session)) return@setItems
                if (which == 0) perform(null)
                else {
                    val loading = AlertDialog.Builder(activity).setMessage("正在读取收藏夹…").setCancelable(false).show()
                    favorites.refreshFolders { result ->
                        if (!alive(activity)) return@refreshFolders
                        loading.dismiss()
                        if (!current(activity, favorites, session)) return@refreshFolders
                        result.onSuccess { rows ->
                            val targets = rows.filter { it.id != source.id }
                            val labels = targets.map { it.title } + "＋ 新建收藏夹"
                            AlertDialog.Builder(activity).setTitle("移动到收藏夹").setItems(labels.toTypedArray()) targetChoice@{ _, index ->
                                if (!current(activity, favorites, session)) return@targetChoice
                                if (index < targets.size) perform(targets[index])
                                else PlaylistPicker.createDialog(activity, favorites) { perform(it) }
                            }.setNegativeButton("取消", null).show()
                        }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }.setNegativeButton("取消", null).show()
    }
}
