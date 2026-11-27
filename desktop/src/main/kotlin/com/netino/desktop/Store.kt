package com.netino.desktop

import android.content.Context
import java.io.File

/** Where Netino keeps its files: %APPDATA%\Netino on Windows, ~/.config/netino on Linux (only this user can read it). */
object Store {
    val dir: File = when {
        Platform.isWindows -> System.getenv("APPDATA")?.let { File(it, "Netino") }
        Platform.isLinux -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.config"), "netino")
        else -> null
    }.let { it ?: File(System.getProperty("user.home"), ".netino") }.apply {
        mkdirs()
        if (Platform.isLinux) { setReadable(false, false); setReadable(true, true); setExecutable(false, false); setExecutable(true, true); setWritable(false, false); setWritable(true, true) }
    }
    val context = Context(dir)

    /** Small plain values that must survive a crash (system proxy bookkeeping). */
    fun read(name: String): String? = File(dir, "$name.txt").takeIf { it.exists() }?.readText()
    fun write(name: String, value: String) = File(dir, "$name.txt").writeText(value)
    fun delete(name: String) { File(dir, "$name.txt").delete() }
}
