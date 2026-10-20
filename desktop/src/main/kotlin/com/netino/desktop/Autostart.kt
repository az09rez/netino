package com.netino.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.TimeUnit

/**
 * "Start with Windows", hidden in the tray. Run as administrator (for the full tunnel) it is a logon task with
 * the highest rights, since a Run key can't start a program elevated; otherwise the current user's Run key.
 */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Netino"

    val available get() = Win.isWindows && Win.launcher != null

    private fun schtasks(vararg args: String): Boolean = runCatching {
        val p = ProcessBuilder(listOf("schtasks.exe") + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val runKey get() = runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)

    var enabled: Boolean
        get() = available && (runKey || schtasks("/query", "/tn", NAME))
        set(on) {
            if (!available) return
            runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME) }
            if (Win.isAdmin) schtasks("/delete", "/tn", NAME, "/f")
            if (!on) return
            val cmd = "\"${Win.launcher}\" --tray"
            if (Win.isAdmin && schtasks("/create", "/tn", NAME, "/tr", cmd, "/sc", "onlogon", "/rl", "highest", "/f")) return
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, cmd)
        }
}
