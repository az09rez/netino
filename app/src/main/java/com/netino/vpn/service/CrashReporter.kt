package com.netino.vpn.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.netino.vpn.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the cause of the last crash so it can be shown (and copied) in the Usage tab's live report:
 *  • Java/Kotlin crashes: stack trace written by an uncaught-exception handler
 *  • native crashes (Go / C engines) and ANRs: Android's own exit records (Android 11+)
 * Nothing is sent anywhere; the user decides whether to share the report.
 */
object CrashReporter {

    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("${stamp(System.currentTimeMillis())} thread=${thread.name}\n${e.stackTraceToString().take(6000)}") }
            previous?.uncaughtException(thread, e)
        }
    }

    fun deviceLine() = "Netino ${BuildConfig.VERSION_NAME} • ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    /** Reports of crashes since the last call (each is shown once). */
    fun takePrevious(context: Context): List<String> {
        val out = mutableListOf<String>()
        if (file.exists()) {
            runCatching { out += "Crash: " + file.readText() }
            file.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val prefs = context.getSharedPreferences("crash_reporter", Context.MODE_PRIVATE)
            val seen = prefs.getLong("seen", 0)
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).filter { it.timestamp > seen }
            for (e in exits) {
                val kind = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
                    ApplicationExitInfo.REASON_CRASH -> null   // Java crash: the stack trace above is better
                    else -> null
                } ?: continue
                val trace = if (e.reason == ApplicationExitInfo.REASON_ANR)
                    runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText().take(2500) } }.getOrNull() else null
                out += "Exit ${stamp(e.timestamp)}: $kind ${e.description.orEmpty()}" + (trace?.let { "\n$it" } ?: "")
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("seen", it).apply() }
        }
        return out
    }
}
