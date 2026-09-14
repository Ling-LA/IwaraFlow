package com.ling.iwaraflow

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 登录凭证和各家服务的密钥只进加密存储：Keystore 出问题时宁可只放内存、重新登录，
 * 也不把 refresh token 明文写到磁盘上；老版本写下的明文文件要删掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecretStorageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun tokensRoundTripAndClearOut() {
        val store = SecureSessionStore(context)
        store.refreshToken = "refresh-1"
        store.accessToken = "access-1"
        assertEquals("refresh-1", store.refreshToken)
        assertEquals("access-1", store.accessToken)
        store.clear()
        assertNull(store.refreshToken)
        assertNull(store.accessToken)
    }

    /** 老版本在 Keystore 出错时把 token 明文写在这个文件里，现在开一次就该没了。 */
    @Test fun theLegacyPlaintextFileIsDeleted() {
        context.getSharedPreferences("iwara_flow_session_fallback", Context.MODE_PRIVATE)
            .edit().putString("refresh_token", "leaked").commit()

        SecureSessionStore(context).warmUp()

        val leftover = context.getSharedPreferences("iwara_flow_session_fallback", Context.MODE_PRIVATE)
            .getString("refresh_token", null)
        assertNull("明文 token 文件必须被删掉", leftover)
    }

    /** 翻译 / AI 的密钥从普通 SharedPreferences 搬进加密存储，明文一并清掉。 */
    @Test fun translationSecretsMoveOutOfPlainPreferences() {
        val plain = context.getSharedPreferences("iwara_flow_prefs", Context.MODE_PRIVATE)
        plain.edit()
            .putString("tr_provider", Translator.PROVIDER_OPENAI)
            .putString("tr_key", "sk-legacy")
            .putString("tr_app_id", "baidu-legacy")
            .putString("tr_custom_headers", "Authorization: Bearer legacy")
            .commit()

        val config = AppPrefs(context).translation

        assertEquals("配置照常读得到", "sk-legacy", config.key)
        assertEquals("baidu-legacy", config.appId)
        assertEquals("Authorization: Bearer legacy", config.customHeaders)
        assertNull("密钥不能再留在普通 SharedPreferences 里", plain.getString("tr_key", null))
        assertNull(plain.getString("tr_app_id", null))
        assertNull(plain.getString("tr_custom_headers", null))
        assertEquals("不是密钥的配置还在原处", Translator.PROVIDER_OPENAI, plain.getString("tr_provider", null))
    }

    @Test fun savingTheConfigKeepsSecretsOutOfPlainPreferences() {
        val prefs = AppPrefs(context)
        prefs.translation = TranslationConfig(
            provider = Translator.PROVIDER_OPENAI,
            key = "sk-new",
            appId = "app-new",
            customHeaders = "X-Token: new",
            endpoint = "https://example.invalid/v1"
        )
        val plain = context.getSharedPreferences("iwara_flow_prefs", Context.MODE_PRIVATE)
        assertNull(plain.getString("tr_key", null))
        assertNull(plain.getString("tr_app_id", null))
        assertNull(plain.getString("tr_custom_headers", null))
        assertEquals("https://example.invalid/v1", plain.getString("tr_endpoint", null))

        val read = AppPrefs(context).translation
        assertEquals("sk-new", read.key)
        assertEquals("app-new", read.appId)
        assertEquals("X-Token: new", read.customHeaders)
    }
}
