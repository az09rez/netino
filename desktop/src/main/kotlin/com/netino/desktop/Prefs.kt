package com.netino.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Properties
import java.io.File

/** Windows-only preferences (the shared AppSettings hold everything else). */
object Prefs {
    private val file = File(Store.dir, "desktop.properties")
    private val p = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    private fun save() = runCatching { file.outputStream().use { p.store(it, null) } }
    private fun bool(key: String, def: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: def
    private fun set(key: String, v: Any) { p.setProperty(key, v.toString()); save() }

    /** Persian (default) or English; the UI recomposes when it changes. */
    private val _fa = MutableStateFlow(p.getProperty("lang", "fa") == "fa")
    val faFlow: StateFlow<Boolean> = _fa
    val fa get() = _fa.value
    fun setLanguage(fa: Boolean) { _fa.value = fa; set("lang", if (fa) "fa" else "en") }

    /** Point Windows' proxy setting at Netino while connected (off: only programs set up by hand use it). */
    var systemProxy: Boolean
        get() = bool("systemProxy", true)
        set(v) = set("systemProxy", v)

    var connectOnStart: Boolean
        get() = bool("connectOnStart", false)
        set(v) = set("connectOnStart", v)

    /** Closing the window keeps Netino running in the tray (first time a tip says so). */
    var closeToTray: Boolean
        get() = bool("closeToTray", true)
        set(v) = set("closeToTray", v)

    /** Theme: "system", "light", "dark". */
    private val _theme = MutableStateFlow(p.getProperty("theme", "system"))
    val theme: StateFlow<String> = _theme
    fun setThemeMode(v: String) { _theme.value = v; set("theme", v) }

    /** Whether the window is on screen (the live speed updates less often while hidden). */
    @Volatile var windowVisible = true
}

/** Persian / English text. */
object L {
    fun t(fa: String, en: String) = if (Prefs.fa) fa else en
}
