package com.ling.iwaraflow

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("iwara_flow_prefs", Context.MODE_PRIVATE)

    /**
     * 密钥类的东西（翻译 / AI 的 API Key、百度 APP ID、自定义 Authorization 头）
     * 放加密存储，不留在普通 SharedPreferences 里。懒加载：只有真的读写翻译配置时才会
     * 碰 Keystore，启动路径不受影响。
     */
    private val secrets by lazy { SecureSessionStore(context) }

    var skipSeen: Boolean
        get() = prefs.getBoolean("skip_seen", true)
        set(value) = prefs.edit().putBoolean("skip_seen", value).apply()

    /** 推荐里每多少条穿插一条老片，0 = 关闭。 */
    var classicsEvery: Int
        get() = prefs.getInt("classics_every", RecommendationEngine.DEFAULT_CLASSICS_EVERY)
        set(value) = prefs.edit().putInt("classics_every", value.coerceAtLeast(0)).apply()

    var defaultQuality: String
        get() = prefs.getString("default_quality", "highest") ?: "highest"
        set(value) = prefs.edit().putString("default_quality", value).apply()

    var autoNext: Boolean
        get() = prefs.getBoolean("auto_next", true)
        set(value) = prefs.edit().putBoolean("auto_next", value).apply()

    /** 上次把 Iwara 官方点赞完整同步到“已看”的时间。 */
    var likedSyncAt: Long
        get() = prefs.getLong("liked_sync_at", 0L)
        set(value) = prefs.edit().putLong("liked_sync_at", value).apply()

    /**
     * 点一下画面是不是暂停播放。关掉之后点一下只在「信息栏 / 操作栏」和
     * 「进度条 / 快进后退 / 剩余时长」之间切换，视频照常播。
     */
    var tapToPause: Boolean
        get() = prefs.getBoolean("tap_to_pause", true)
        set(value) = prefs.edit().putBoolean("tap_to_pause", value).apply()

    /** 暂停时是否显示播放三角。 */
    var showPauseIndicator: Boolean
        get() = prefs.getBoolean("show_pause_indicator", true)
        set(value) = prefs.edit().putBoolean("show_pause_indicator", value).apply()

    /** 暂停控制行里前进 / 后退一次跳多少秒。 */
    var skipSeconds: Int
        get() = prefs.getInt("skip_seconds", DEFAULT_SKIP_SECONDS).coerceIn(1, 600)
        set(value) = prefs.edit().putInt("skip_seconds", value.coerceIn(1, 600)).apply()

    /** 翻译服务配置，见 [TranslationConfig]。密钥部分走加密存储。 */
    var translation: TranslationConfig
        get() {
            migrateSecretsIfNeeded()
            return TranslationConfig(
                provider = prefs.getString("tr_provider", Translator.PROVIDER_GOOGLE) ?: Translator.PROVIDER_GOOGLE,
                key = secrets.secret(SecureSessionStore.KEY_TRANSLATION_KEY).orEmpty(),
                region = prefs.getString("tr_region", "") ?: "",
                appId = secrets.secret(SecureSessionStore.KEY_TRANSLATION_APP_ID).orEmpty(),
                endpoint = prefs.getString("tr_endpoint", "") ?: "",
                customMethod = prefs.getString("tr_custom_method", "GET") ?: "GET",
                customBody = prefs.getString("tr_custom_body", "") ?: "",
                customHeaders = secrets.secret(SecureSessionStore.KEY_TRANSLATION_HEADERS).orEmpty(),
                customResultPath = prefs.getString("tr_custom_result_path", "") ?: "",
                customLangPath = prefs.getString("tr_custom_lang_path", "") ?: "",
                model = prefs.getString("tr_model", "") ?: "",
                aiVendor = prefs.getString("tr_ai_vendor", Translator.AI_VENDOR_OPENAI) ?: Translator.AI_VENDOR_OPENAI
            )
        }
        set(value) {
            secrets.putSecret(SecureSessionStore.KEY_TRANSLATION_KEY, value.key)
            secrets.putSecret(SecureSessionStore.KEY_TRANSLATION_APP_ID, value.appId)
            secrets.putSecret(SecureSessionStore.KEY_TRANSLATION_HEADERS, value.customHeaders)
            prefs.edit()
                .putString("tr_provider", value.provider)
                .putString("tr_region", value.region)
                .putString("tr_endpoint", value.endpoint)
                .putString("tr_custom_method", value.customMethod)
                .putString("tr_custom_body", value.customBody)
                .putString("tr_custom_result_path", value.customResultPath)
                .putString("tr_custom_lang_path", value.customLangPath)
                .putString("tr_model", value.model)
                .putString("tr_ai_vendor", value.aiVendor)
                // 老版本把密钥写在这里，迁移之后一并清掉。
                .remove("tr_key").remove("tr_app_id").remove("tr_custom_headers")
                .putBoolean(SECRETS_MIGRATED, true)
                .apply()
        }

    /** 从老版本升上来：把普通 SharedPreferences 里的密钥搬进加密存储，再把明文删掉。 */
    private fun migrateSecretsIfNeeded() {
        if (prefs.getBoolean(SECRETS_MIGRATED, false)) return
        val legacy = mapOf(
            SecureSessionStore.KEY_TRANSLATION_KEY to prefs.getString("tr_key", "").orEmpty(),
            SecureSessionStore.KEY_TRANSLATION_APP_ID to prefs.getString("tr_app_id", "").orEmpty(),
            SecureSessionStore.KEY_TRANSLATION_HEADERS to prefs.getString("tr_custom_headers", "").orEmpty()
        )
        legacy.forEach { (name, value) -> if (value.isNotEmpty()) secrets.putSecret(name, value) }
        prefs.edit()
            .remove("tr_key").remove("tr_app_id").remove("tr_custom_headers")
            .putBoolean(SECRETS_MIGRATED, true)
            .apply()
    }

    companion object {
        const val DEFAULT_SKIP_SECONDS = 15
        /** 密钥已经搬进加密存储的标记。 */
        private const val SECRETS_MIGRATED = "secrets_migrated_v1"
    }

    /** 手动代理。类型是 [NetworkProxy.TYPE_NONE] / TYPE_HTTP / TYPE_SOCKS。 */
    var proxyType: String
        get() = prefs.getString("proxy_type", NetworkProxy.TYPE_NONE) ?: NetworkProxy.TYPE_NONE
        set(value) = prefs.edit().putString("proxy_type", value).apply()

    var proxyHost: String
        get() = prefs.getString("proxy_host", "") ?: ""
        set(value) = prefs.edit().putString("proxy_host", value.trim()).apply()

    var proxyPort: Int
        get() = prefs.getInt("proxy_port", 0)
        set(value) = prefs.edit().putInt("proxy_port", value).apply()

    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", true)
        set(value) = prefs.edit().putBoolean("auto_pip", value).apply()
}
