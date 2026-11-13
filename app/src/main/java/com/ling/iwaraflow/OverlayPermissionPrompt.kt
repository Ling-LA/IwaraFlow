package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/** Consume the first-launch prompt before opening it, including dismiss/back/rotation. */
object OverlayPermissionPrompt {
    internal const val KEY_SHOWN = "overlay_permission_prompt_shown"
    fun showOnce(activity: Activity): AlertDialog? {
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, 0)
        if (activity.isFinishing || activity.isDestroyed || prefs.getBoolean(KEY_SHOWN, false)) return null
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        if (Settings.canDrawOverlays(activity)) return null
        return AlertDialog.Builder(activity).setTitle(UiText.text(R.string.ui_b22ad705e39e, "允许悬浮窗播放"))
            .setMessage(UiText.text(R.string.ui_312bf4463675, "允许后，可在其他应用上方使用视频小窗。首次启动只询问一次，拒绝后仍可从小窗按钮手动开启。"))
            .setPositiveButton(UiText.text(R.string.ui_254f4dad7807, "去授权")) { _, _ ->
                runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"))) }
            }.setNegativeButton(UiText.text(R.string.ui_a2666f394298, "暂不开启"), null).show()
    }
}
