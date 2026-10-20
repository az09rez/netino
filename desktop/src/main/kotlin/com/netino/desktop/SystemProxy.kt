package com.netino.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows' own proxy setting (Settings > Network > Proxy), used by browsers and most programs. Set through
 * WinINet's per-connection options (what the Settings app does): writing the registry values alone is not
 * picked up reliably on Windows 10 / 11. The previous setting is saved and put back on disconnect, and also at
 * the next start after a crash.
 */
object SystemProxy {

    /** Local and private addresses never go through the proxy. */
    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;" +
        "172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    val supported get() = Win.isWindows

    @Suppress("FunctionName")
    private interface WinInet : StdCallLibrary {
        fun InternetSetOptionW(hInternet: Pointer?, dwOption: Int, lpBuffer: Pointer?, dwBufferLength: Int): Boolean
        fun InternetQueryOptionW(hInternet: Pointer?, dwOption: Int, lpBuffer: Pointer?, lpdwBufferLength: IntByReference): Boolean
        companion object { val INSTANCE: WinInet by lazy { Native.load("wininet", WinInet::class.java, W32APIOptions.UNICODE_OPTIONS) } }
    }

    private const val OPTION_PER_CONNECTION = 75
    private const val OPTION_SETTINGS_CHANGED = 39
    private const val OPTION_REFRESH = 37
    private const val PER_CONN_FLAGS = 1
    private const val PER_CONN_PROXY_SERVER = 2
    private const val PER_CONN_PROXY_BYPASS = 3
    private const val PER_CONN_AUTOCONFIG_URL = 4
    private const val TYPE_DIRECT = 1
    private const val TYPE_PROXY = 2

    /** What the setting holds: WinINet flags (direct / proxy / PAC / auto-detect), server, bypass list, PAC URL. */
    data class Setting(val flags: Int, val server: String?, val bypass: String?, val pac: String?) {
        val proxyOn get() = flags and TYPE_PROXY != 0
    }

    // INTERNET_PER_CONN_OPTION is 16 bytes on 64-bit Windows (DWORD option, then an 8-byte union);
    // INTERNET_PER_CONN_OPTION_LIST is 32 (DWORD size, pointer connection = null for LAN, DWORD count, DWORD error, pointer options)
    private const val OPT_SIZE = 16
    private const val LIST_SIZE = 32

    private fun list(options: Memory, count: Int) = Memory(LIST_SIZE.toLong()).apply {
        clear()
        setInt(0, LIST_SIZE)
        setPointer(8, null)
        setInt(16, count)
        setPointer(24, options)
    }

    private fun wide(s: String) = Memory((s.length + 1) * 2L).apply { setWideString(0, s) }

    /** The current setting, as programs see it. */
    fun current(): Setting? = if (!supported) null else runCatching {
        val ids = intArrayOf(PER_CONN_FLAGS, PER_CONN_PROXY_SERVER, PER_CONN_PROXY_BYPASS, PER_CONN_AUTOCONFIG_URL)
        val opts = Memory(OPT_SIZE.toLong() * ids.size).apply { clear() }
        ids.forEachIndexed { i, id -> opts.setInt(i * OPT_SIZE.toLong(), id) }
        val l = list(opts, ids.size)
        check(WinInet.INSTANCE.InternetQueryOptionW(null, OPTION_PER_CONNECTION, l, IntByReference(LIST_SIZE))) { "query failed: ${Native.getLastError()}" }
        fun str(i: Int) = opts.getPointer(i * OPT_SIZE + 8L)?.getWideString(0)
        Setting(opts.getInt(8), str(1), str(2), str(3))
    }.getOrNull()

    private fun apply(s: Setting) {
        val values = buildList {
            add(PER_CONN_FLAGS to s.flags)
            add(PER_CONN_PROXY_SERVER to s.server.orEmpty())
            add(PER_CONN_PROXY_BYPASS to s.bypass.orEmpty())
            add(PER_CONN_AUTOCONFIG_URL to s.pac.orEmpty())
        }
        val opts = Memory(OPT_SIZE.toLong() * values.size).apply { clear() }
        val keep = mutableListOf<Memory>()   // strings must stay alive until the call returns
        values.forEachIndexed { i, (id, v) ->
            val at = i * OPT_SIZE.toLong()
            opts.setInt(at, id)
            when (v) {
                is Int -> opts.setInt(at + 8, v)
                is String -> opts.setPointer(at + 8, wide(v).also { keep += it })
            }
        }
        check(WinInet.INSTANCE.InternetSetOptionW(null, OPTION_PER_CONNECTION, list(opts, values.size), LIST_SIZE)) {
            "Windows refused the proxy setting (error ${Native.getLastError()})"
        }
        WinInet.INSTANCE.InternetSetOptionW(null, OPTION_SETTINGS_CHANGED, null, 0)
        WinInet.INSTANCE.InternetSetOptionW(null, OPTION_REFRESH, null, 0)
        keep.clear()
    }

    /** Points Windows at the local HTTP proxy on [httpPort]; throws if Windows doesn't take it. */
    fun enable(httpPort: Int) {
        if (!supported) return
        val ours = "127.0.0.1:$httpPort"
        val before = current()
        // Save what was there unless it is already ours (a reconnect)
        if (before != null && !(before.proxyOn && before.server == ours)) {
            Store.write("proxy_saved", listOf(before.flags.toString(), before.server.orEmpty(), before.bypass.orEmpty(), before.pac.orEmpty()).joinToString("\n"))
        }
        // Proxy only: no auto-detect / PAC, which would be tried first and could send traffic around Netino
        apply(Setting(TYPE_DIRECT or TYPE_PROXY, ours, BYPASS, null))
        Store.write("proxy_ours", ours)
        val now = current()
        check(now != null && now.proxyOn && now.server == ours) { "the Windows proxy setting didn't change" }
    }

    /** Puts back what was there before (only if the setting still points at Netino). */
    fun disable() {
        if (!supported) return
        val ours = Store.read("proxy_ours") ?: return
        val now = current()
        if (now == null || (now.proxyOn && now.server == ours)) {
            val saved = Store.read("proxy_saved")?.split('\n')
            val restored = saved?.let { Setting(it[0].toIntOrNull() ?: TYPE_DIRECT, it.getOrNull(1), it.getOrNull(2), it.getOrNull(3)?.ifEmpty { null }) }
                // Nothing saved: no proxy, auto-detect on (Windows' default)
                ?: Setting(TYPE_DIRECT or 8, null, null, null)
            // The old setting pointing at Netino too (left by a crash) would keep everything pointing at a dead proxy
            apply(if (restored.proxyOn && restored.server == ours) restored.copy(flags = restored.flags and TYPE_PROXY.inv()) else restored)
        }
        Store.delete("proxy_ours")
    }
}
