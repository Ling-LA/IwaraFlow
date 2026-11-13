package com.ling.iwaraflow

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** UI locale only. Content translation and search language selection remain independent. */
object AppLanguage {
    val codes = listOf("zh-CN", "en", "ja", "ko")
    val names = listOf("简体中文", "English", "日本語", "한국어")
    private const val KEY = "ui_language"
    @Volatile private var app: Context? = null
    @Volatile private var localized: Context? = null
    @Volatile var current: String = "zh-CN"
        private set

    fun initialize(context: Context) {
        app = context.applicationContext
        val stored = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).getString(KEY, "zh-CN")
        val system = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags() else null
        select(system?.takeIf { it.isNotBlank() } ?: stored.orEmpty())
    }

    fun select(code: String) {
        current = codes.firstOrNull { it == code || it.substringBefore('-') == code.substringBefore('-') } ?: "zh-CN"
        val context = app ?: return
        context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).edit().putString(KEY, current).apply()
        localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(current))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            val locales = android.os.LocaleList.forLanguageTags(current)
            if (manager?.applicationLocales != locales) manager?.applicationLocales = locales
        } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(current))
    }

    internal fun resources() = localized?.resources
}

/** All callers refer to a resource ID; the source fallback also supports pure JVM model tests. */
object UiText {
    fun text(id: Int, source: String, vararg args: Any?): String {
        val template = AppLanguage.resources()?.getString(id) ?: source
        return if (args.isEmpty()) template else String.format(Locale.forLanguageTag(AppLanguage.current),
            template.replace(Regex("%(?!\\d+\\\$s)"), "%%"), *args)
    }
}
