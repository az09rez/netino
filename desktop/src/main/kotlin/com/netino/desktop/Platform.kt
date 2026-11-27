package com.netino.desktop

import java.io.File

/**
 * Which desktop system Netino runs on. Windows and Linux share every screen and the connection engine; what
 * differs (the proxy setting, autostart, the installer and its update channel) asks here.
 */
object Platform {
    private val os = System.getProperty("os.name").orEmpty()
    val isWindows = os.startsWith("Windows")
    val isLinux = os.startsWith("Linux")

    /** Whether the desktop shows tray icons (always on Windows; on GNOME only with an extension). */
    val hasTray: Boolean by lazy { isWindows || runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }

    /** The system's name in the UI ("پراکسی ویندوز" / "پراکسی لینوکس"). */
    val nameFa get() = if (isLinux) "لینوکس" else "ویندوز"
    val nameEn get() = if (isLinux) "Linux" else "Windows"
    fun name() = L.t(nameFa, nameEn)

    /** The app's own folder in an installed or portable copy (jpackage: Netino/app on Windows, netino/lib/app on Linux). */
    val appDir: File? get() = Win.launcher?.let { File(it).absoluteFile.parentFile }?.let { bin ->
        if (isWindows) File(bin, "app") else File(bin.parentFile, "lib/app")
    }

    /** The folder the user unpacked (portable copy): Netino\ on Windows, netino/ (above bin/) on Linux. */
    val installRoot: File? get() = Win.launcher?.let { File(it).absoluteFile.parentFile }?.let { bin ->
        if (isWindows) bin else bin.parentFile
    }

    /** Opens a folder (or shows a file in it) in the system's file manager. */
    fun reveal(file: File) {
        runCatching {
            if (isWindows) ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            else ProcessBuilder("xdg-open", (if (file.isDirectory) file else file.parentFile).absolutePath).start()
        }
    }

    /** True if [cmd] is on the PATH (Linux tools that may or may not be installed: gsettings, kwriteconfig6, ...). */
    fun has(cmd: String): Boolean = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { it.isNotEmpty() && File(it, cmd).canExecute() }

    /** Runs a program and returns its output (trimmed), or null if it failed or isn't there. */
    fun run(vararg cmd: String, timeoutSec: Long = 10): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() != 0) null else out.trim()
    }.getOrNull()
}
