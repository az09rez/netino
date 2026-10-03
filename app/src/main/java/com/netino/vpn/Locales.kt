package com.netino.vpn

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language: "" = follow the phone, "en", "fa".
 * Android 13+: uses the system per-app language API (also editable from system settings).
 * Older versions: the choice is stored and applied by wrapping the base context.
 */
object Locales {
    private const val PREFS = "ui_prefs"
    private const val KEY = "lang"

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()
        } else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    /** @return true if the caller must recreate its activity (pre-Android 13). */
    fun set(context: Context, lang: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (lang.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(lang)
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
        return true
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (lang.isEmpty()) return base
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        return base.createConfigurationContext(cfg)
    }
}
