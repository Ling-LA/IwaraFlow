package com.ling.iwaraflow

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("iwara_flow_prefs", Context.MODE_PRIVATE)

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

    /** 暂停时是否显示播放三角。 */
    var showPauseIndicator: Boolean
        get() = prefs.getBoolean("show_pause_indicator", true)
        set(value) = prefs.edit().putBoolean("show_pause_indicator", value).apply()

    /** 暂停控制行里前进 / 后退一次跳多少秒。 */
    var skipSeconds: Int
        get() = prefs.getInt("skip_seconds", DEFAULT_SKIP_SECONDS).coerceIn(1, 600)
        set(value) = prefs.edit().putInt("skip_seconds", value.coerceIn(1, 600)).apply()

    /** 翻译服务配置，见 [TranslationConfig]。 */
    var translation: TranslationConfig
        get() = TranslationConfig(
            provider = prefs.getString("tr_provider", Translator.PROVIDER_GOOGLE) ?: Translator.PROVIDER_GOOGLE,
            key = prefs.getString("tr_key", "") ?: "",
            region = prefs.getString("tr_region", "") ?: "",
            appId = prefs.getString("tr_app_id", "") ?: "",
            endpoint = prefs.getString("tr_endpoint", "") ?: "",
            customMethod = prefs.getString("tr_custom_method", "GET") ?: "GET",
            customBody = prefs.getString("tr_custom_body", "") ?: "",
            customHeaders = prefs.getString("tr_custom_headers", "") ?: "",
            customResultPath = prefs.getString("tr_custom_result_path", "") ?: "",
            customLangPath = prefs.getString("tr_custom_lang_path", "") ?: "",
            model = prefs.getString("tr_model", "") ?: "",
            aiVendor = prefs.getString("tr_ai_vendor", Translator.AI_VENDOR_OPENAI) ?: Translator.AI_VENDOR_OPENAI
        )
        set(value) = prefs.edit()
            .putString("tr_provider", value.provider)
            .putString("tr_key", value.key)
            .putString("tr_region", value.region)
            .putString("tr_app_id", value.appId)
            .putString("tr_endpoint", value.endpoint)
            .putString("tr_custom_method", value.customMethod)
            .putString("tr_custom_body", value.customBody)
            .putString("tr_custom_headers", value.customHeaders)
            .putString("tr_custom_result_path", value.customResultPath)
            .putString("tr_custom_lang_path", value.customLangPath)
            .putString("tr_model", value.model)
            .putString("tr_ai_vendor", value.aiVendor)
            .apply()

    companion object {
        const val DEFAULT_SKIP_SECONDS = 15
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
