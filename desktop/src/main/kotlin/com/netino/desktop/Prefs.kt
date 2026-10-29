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

    /** Persian or English; until the user picks one, Windows' display language decides. The UI recomposes on change. */
    private val _fa = MutableStateFlow((p.getProperty("lang") ?: systemLanguage()) == "fa")
    val faFlow: StateFlow<Boolean> = _fa
    val fa get() = _fa.value
    fun setLanguage(fa: Boolean) { _fa.value = fa; set("lang", if (fa) "fa" else "en") }

    /** Point Windows' proxy setting at Netino while connected (off: only programs set up by hand use it). */
    var systemProxy: Boolean
        get() = bool("systemProxy", true)
        set(v) = set("systemProxy", v)

    /** Full tunnel (every program, through a network adapter; needs administrator rights) instead of the system proxy. */
    private val _tun = MutableStateFlow(bool("tunMode", false))
    /** The same, for the screens showing it (home and settings stay in step). */
    val tunFlow: StateFlow<Boolean> = _tun
    var tunMode: Boolean
        get() = _tun.value
        set(v) { _tun.value = v; set("tunMode", v) }

    /** Full tunnel only: if the tunnel drops, Windows Firewall keeps everything from leaving outside it. */
    var killSwitch: Boolean
        get() = bool("killSwitch", false)
        set(v) = set("killSwitch", v)

    /** Share the connection with other devices on the local network (HTTP / SOCKS on every interface). */
    var shareLan: Boolean
        get() = bool("shareLan", false)
        set(v) = set("shareLan", v)

    /** The last server that connected and when ("id|millis"), for the home page's "last connection". */
    var lastConnection: String
        get() = p.getProperty("lastConnection", "")
        set(v) = set("lastConnection", v)

    /** The window's last place and size ("x,y,width,height,maximized", in window units); empty the first time. */
    var windowBounds: String
        get() = p.getProperty("window", "")
        set(v) = set("window", v)

    /** Programs (exe names) that don't go through Netino. */
    var directApps: List<String>
        get() = p.getProperty("directApps", "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = set("directApps", v.distinct().joinToString("|"))

    /** Windows notifications: connection dropped, server switched, subscription running out, update. */
    var notifications: Boolean
        get() = bool("notifications", true)
        set(v) = set("notifications", v)

    /** Ctrl+Alt+N connects / disconnects from anywhere. */
    var hotkey: Boolean
        get() = bool("hotkey", true)
        set(v) = set("hotkey", v)

    /** The WARP button makes WARP in WARP (two layers: harder to block) or plain WARP (faster). */
    var warpInWarp: Boolean
        get() = bool("warpInWarp", true)
        set(v) = set("warpInWarp", v)

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

/**
 * "fa" when Windows is set to Persian: its display language, or its region format (Persian or Iran, which many
 * keep with an English Windows); otherwise "en".
 */
private fun systemLanguage(): String {
    val locales = listOf(java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY), java.util.Locale.getDefault(java.util.Locale.Category.FORMAT))
    return if (locales.any { it.language == "fa" || it.country == "IR" }) "fa" else "en"
}

/** Persian / English text. */
object L {
    fun t(fa: String, en: String) = if (Prefs.fa) fa else en
}
