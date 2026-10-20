package com.netino.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Shell32
import java.util.concurrent.TimeUnit

/** Small Windows helpers: administrator rights and the network interface traffic normally leaves through. */
object Win {
    val isWindows = System.getProperty("os.name").startsWith("Windows")

    /** The full tunnel (a network adapter and system routes) needs administrator rights. */
    val isAdmin: Boolean by lazy { isWindows && runCatching { Advapi32Util.isCurrentProcessElevated() }.getOrDefault(false) }

    /** The installed launcher (jpackage sets jpackage.app-path); null while developing. */
    val launcher: String? get() = System.getProperty("jpackage.app-path")

    /**
     * Starts Netino again with administrator rights (Windows asks the user) and returns true if it was started;
     * the caller then exits so the new one can take over.
     */
    fun restartElevated(args: String): Boolean {
        val exe = launcher ?: return false
        val r = runCatching { Shell32.INSTANCE.ShellExecute(null, "runas", exe, args, null, 1) }.getOrNull() ?: return false
        // ShellExecute: a value above 32 means success (the user accepted the prompt)
        return r.toLong() > 32
    }

    /** Runs a PowerShell script; returns its output, or throws with its error text. */
    fun powershell(script: String, timeoutSec: Long = 30): String {
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", "-")
            .redirectErrorStream(true).start()
        p.outputStream.use { it.write(("\$ErrorActionPreference = 'Stop'\n" + script + "\nexit 0\n").toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); error("PowerShell timed out") }
        check(p.exitValue() == 0) { out.trim().lines().lastOrNull { it.isNotBlank() } ?: "PowerShell failed" }
        return out
    }

    /** PowerShell single-quoted string. */
    fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

    /** This program's own executable (the installed Netino.exe, or java.exe while developing). */
    val selfExe: String? get() = launcher ?: ProcessHandle.current().info().command().orElse(null)

    /**
     * Windows name ("Wi-Fi", "Ethernet") of the interface with the default route, before the tunnel takes it over.
     * Test cores bind to it so they measure servers over the real network, not through the tunnel.
     */
    fun defaultInterface(): String? = if (!isWindows) null else runCatching {
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Where-Object { \$_.InterfaceAlias -ne '${Controller.TUN_NAME}' } | Sort-Object { \$_.RouteMetric + (Get-NetIPInterface -InterfaceIndex \$_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric } | Select-Object -First 1 -ExpandProperty InterfaceAlias")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim().lines().lastOrNull()?.trim()
        p.waitFor(10, TimeUnit.SECONDS)
        out?.takeIf { it.isNotEmpty() && !it.contains("Exception") }
    }.getOrNull()
}
