package com.ling.iwaraflow

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("iwara_flow_prefs", Context.MODE_PRIVATE)

    var skipSeen: Boolean
        get() = prefs.getBoolean("skip_seen", false)
        set(value) = prefs.edit().putBoolean("skip_seen", value).apply()

    var defaultQuality: String
        get() = prefs.getString("default_quality", "highest") ?: "highest"
        set(value) = prefs.edit().putString("default_quality", value).apply()

    var autoNext: Boolean
        get() = prefs.getBoolean("auto_next", true)
        set(value) = prefs.edit().putBoolean("auto_next", value).apply()

    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", true)
        set(value) = prefs.edit().putBoolean("auto_pip", value).apply()
}
