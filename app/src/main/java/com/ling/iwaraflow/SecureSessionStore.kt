package com.ling.iwaraflow

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext

    // 建 MasterKey 要走 Android Keystore，冷启动时这一步曾经卡在主线程上。
    // 改成懒加载后由 warmUp() 在后台线程提前准备好。
    private val prefs: SharedPreferences by lazy { openPrefs() }

    fun warmUp() {
        prefs.contains("refresh_token")
    }

    private fun openPrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "iwara_flow_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Throwable) {
        // 极少数损坏/不兼容的 Keystore 设备上保证应用仍能启动。
        // 用户密码从不落盘；这里只有短期 token。
        appContext.getSharedPreferences("iwara_flow_session_fallback", Context.MODE_PRIVATE)
    }

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) {
            if (value == null) prefs.edit().remove("refresh_token").apply()
            else prefs.edit().putString("refresh_token", value).apply()
        }

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) {
            if (value == null) prefs.edit().remove("access_token").apply()
            else prefs.edit().putString("access_token", value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
