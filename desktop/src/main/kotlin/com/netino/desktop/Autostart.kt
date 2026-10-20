package com.netino.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** "Start with Windows": the current user's Run key, started hidden in the tray. */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Netino"

    /** The installed launcher (jpackage sets jpackage.app-path); null while developing. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    val available get() = SystemProxy.supported && exe != null

    var enabled: Boolean
        get() = available && runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)
        set(on) {
            if (!available) return
            if (on) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"$exe\" --tray")
            else runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME) }
        }
}
