package com.ling.iwaraflow

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录凭证和各家服务的密钥，只存在加密存储里。
 *
 * 以前 Keystore 出错时会退回普通 SharedPreferences，把 refresh / access token 明文写在
 * 磁盘上——为了“还能启动”把长期凭证暴露出去不划算。现在加密存储打不开就**只放内存**：
 * 这次进程里照常用，应用被杀掉之后重新登录即可，磁盘上一个字节都不留。
 * 老版本留下的明文文件在第一次打开时删掉。
 */
class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 加密存储打不开时的退路：只活在这个进程里。整个进程共用一份——各个页面都会各自
     * new 一个 SecureSessionStore，按实例存的话登录状态会互相看不见。
     */
    private val memory get() = processMemory

    @Volatile private var opened = false
    @Volatile private var encrypted: SharedPreferences? = null

    fun warmUp() {
        prefs()
    }

    @Synchronized
    private fun prefs(): SharedPreferences? {
        if (opened) return encrypted
        opened = true
        // 老版本可能把 token 明文写在这里，无论加密存储能不能用都先删掉。
        runCatching { appContext.deleteSharedPreferences(LEGACY_FALLBACK) }
        encrypted = try {
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
            null
        }
        return encrypted
    }

    /** 加密存储是不是真的可用；不可用时敏感数据只在内存里活到进程结束。 */
    fun isEncrypted(): Boolean = prefs() != null

    fun secret(name: String): String? =
        prefs()?.getString(name, null) ?: synchronized(memory) { memory[name] }

    fun putSecret(name: String, value: String?) {
        val store = prefs()
        if (store != null) {
            // 加密存储可用时只写它一处，内存那份根本用不上。
            if (value.isNullOrEmpty()) store.edit().remove(name).apply()
            else store.edit().putString(name, value).apply()
            return
        }
        synchronized(memory) {
            if (value.isNullOrEmpty()) memory.remove(name) else memory[name] = value
        }
    }

    var refreshToken: String?
        get() = secret("refresh_token")
        set(value) = putSecret("refresh_token", value)

    var accessToken: String?
        get() = secret("access_token")
        set(value) = putSecret("access_token", value)

    fun clear() {
        prefs()?.edit()?.clear()?.apply()
        synchronized(memory) { memory.clear() }
    }

    companion object {
        /** 进程级的内存退路，见 [memory]。 */
        private val processMemory = HashMap<String, String>()

        /** 老版本在 Keystore 出错时用过的明文文件，现在只负责删掉它。 */
        private const val LEGACY_FALLBACK = "iwara_flow_session_fallback"

        /** 翻译 / AI 服务的密钥也放这里，不再留在普通 SharedPreferences。 */
        const val KEY_TRANSLATION_KEY = "tr_key"
        const val KEY_TRANSLATION_APP_ID = "tr_app_id"
        const val KEY_TRANSLATION_HEADERS = "tr_custom_headers"
    }
}
