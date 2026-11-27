package com.netino.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.TimeUnit

/**
 * "Start with Windows" / "Start with Linux", hidden in the tray. Linux: an XDG autostart entry
 * (~/.config/autostart/netino.desktop), which GNOME, KDE, Xfce, Cinnamon and MATE all read.
 * Windows: run as administrator (for the full tunnel) it is a logon task with
 * the highest rights, since a Run key can't start a program elevated; otherwise the current user's Run key.
 */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Netino"

    val available get() = (Win.isWindows || Platform.isLinux) && Win.launcher != null

    private val xdgEntry get() = java.io.File(
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.config"), "autostart/netino.desktop")

    private fun schtasks(vararg args: String): Boolean = runCatching {
        val p = ProcessBuilder(listOf("schtasks.exe") + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val runKey get() = runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)

    var enabled: Boolean
        get() = available && if (Platform.isLinux) xdgEntry.exists() else (runKey || schtasks("/query", "/tn", NAME))
        set(on) {
            if (!available) return
            if (Platform.isLinux) {
                if (!on) { xdgEntry.delete(); return }
                xdgEntry.parentFile.mkdirs()
                xdgEntry.writeText("""
                    [Desktop Entry]
                    Type=Application
                    Name=Netino
                    Comment=Netino VPN
                    Exec="${Win.launcher}" --tray
                    Icon=${Platform.installRoot?.let { java.io.File(it, "lib/Netino.png") }?.takeIf { it.exists() }?.absolutePath ?: "netino"}
                    Terminal=false
                    X-GNOME-Autostart-enabled=true
                """.trimIndent() + "\n")
                return
            }
            runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME) }
            if (Win.isAdmin) schtasks("/delete", "/tn", NAME, "/f")
            if (!on) return
            val cmd = "\"${Win.launcher}\" --tray"
            if (Win.isAdmin && schtasks("/create", "/tn", NAME, "/tr", cmd, "/sc", "onlogon", "/rl", "highest", "/f")) return
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, cmd)
        }
}
