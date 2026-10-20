package com.netino.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows' own proxy setting (Settings > Network > Proxy), used by browsers and most programs.
 * The previous values are saved and put back on disconnect, and also at the next start after a crash.
 */
object SystemProxy {

    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    /** Local and private addresses never go through the proxy. */
    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;" +
        "172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    val supported = System.getProperty("os.name").startsWith("Windows")

    private interface WinInet : StdCallLibrary {
        fun InternetSetOptionW(hInternet: Pointer?, dwOption: Int, lpBuffer: Pointer?, dwBufferLength: Int): Boolean
        companion object { val INSTANCE: WinInet by lazy { Native.load("wininet", WinInet::class.java, W32APIOptions.UNICODE_OPTIONS) } }
    }

    private data class Saved(val enable: Int, val server: String?, val override: String?)

    /** Points Windows at the local HTTP proxy on [httpPort]. */
    fun enable(httpPort: Int) {
        if (!supported) return
        val ours = "127.0.0.1:$httpPort"
        val current = read()
        // Save what was there unless it is already ours (a second connect while connected)
        if (current.server != ours || current.enable == 0) Store.write("proxy_saved", "${current.enable}\n${current.server.orEmpty()}\n${current.override.orEmpty()}")
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 1)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", ours)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", BYPASS)
        Store.write("proxy_ours", ours)
        refresh()
    }

    /** Puts back what was there before (only if the setting still points at Netino). */
    fun disable() {
        if (!supported) return
        val ours = Store.read("proxy_ours") ?: return
        val current = read()
        if (current.enable != 0 && current.server == ours) {
            val saved = Store.read("proxy_saved")?.split('\n')
            val enable = saved?.getOrNull(0)?.toIntOrNull() ?: 0
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", enable)
            saved?.getOrNull(1)?.let { if (it.isEmpty()) deleteValue("ProxyServer") else Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", it) }
            saved?.getOrNull(2)?.let { if (it.isEmpty()) deleteValue("ProxyOverride") else Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", it) }
            refresh()
        }
        Store.delete("proxy_ours")
    }

    private fun deleteValue(name: String) = runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, name) }

    private fun read() = Saved(
        runCatching { Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable") }.getOrDefault(0),
        runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer") }.getOrNull(),
        runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride") }.getOrNull(),
    )

    /** Tells running programs (browsers) the setting changed. */
    private fun refresh() = runCatching {
        WinInet.INSTANCE.InternetSetOptionW(null, 39, null, 0)   // INTERNET_OPTION_SETTINGS_CHANGED
        WinInet.INSTANCE.InternetSetOptionW(null, 37, null, 0)   // INTERNET_OPTION_REFRESH
    }
}
