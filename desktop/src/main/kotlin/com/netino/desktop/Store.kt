package com.netino.desktop

import android.content.Context
import java.io.File

/** Where Netino keeps its files: %APPDATA%\Netino on Windows. */
object Store {
    val dir: File = (System.getenv("APPDATA")?.let { File(it, "Netino") } ?: File(System.getProperty("user.home"), ".netino")).apply { mkdirs() }
    val context = Context(dir)

    /** Small plain values that must survive a crash (system proxy bookkeeping). */
    fun read(name: String): String? = File(dir, "$name.txt").takeIf { it.exists() }?.readText()
    fun write(name: String, value: String) = File(dir, "$name.txt").writeText(value)
    fun delete(name: String) { File(dir, "$name.txt").delete() }
}
