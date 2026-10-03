package com.netino.vpn.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.netino.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the project's GitHub Releases:
 *  check → "update available" next to the app name → download the APK for this phone's CPU
 *  → verify its SHA-256 against the release's SHA256SUMS.txt → hand it to the system installer.
 * The installer always asks the user to confirm; the signature must match the installed app.
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/az09rez/netino/releases/latest"

    data class Release(val version: String, val apkName: String, val apkUrl: String, val sumsUrl: String?, val notes: String)

    sealed interface State {
        data object Idle : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val release: Release, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** "2.0.10" > "2.0.9"; non-numeric parts count as 0. */
    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Asset for this device: the first supported ABI with its own APK, otherwise the universal one. */
    private fun pickAsset(names: List<String>): String? {
        for (abi in Build.SUPPORTED_ABIS) names.firstOrNull { it.endsWith("-$abi.apk") }?.let { return it }
        return names.firstOrNull { it.endsWith("-universal.apk") } ?: names.firstOrNull { it.endsWith(".apk") }
    }

    /** Quiet check; at most every 6 hours unless [force]. Returns the newer release, if any. */
    suspend fun check(context: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked", 0) < 6 * 3_600_000L) {
            return@withContext (state.value as? State.Available)?.release
        }
        runCatching {
            val body = http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build())
                .execute().use { r -> check(r.isSuccessful) { "HTTP ${r.code}" }; r.body!!.string() }
            prefs.edit().putLong("checked", now).apply()
            val o = Json.parseToJsonElement(body).jsonObject
            val version = o["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
            if (!isNewer(version)) return@runCatching null
            val assets = o["assets"]!!.jsonArray.map { it.jsonObject }
            val urls = assets.associate { it["name"]!!.jsonPrimitive.content to it["browser_download_url"]!!.jsonPrimitive.content }
            val name = pickAsset(urls.keys.toList()) ?: return@runCatching null
            Release(version, name, urls.getValue(name), urls["SHA256SUMS.txt"], o["body"]?.jsonPrimitive?.content.orEmpty())
        }.getOrNull()?.also { if (_state.value !is State.Downloading) _state.value = State.Available(it) }
    }

    /** Downloads and verifies the APK, then opens the installer (or the "install unknown apps" setting). */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                _state.value = State.Downloading(release, 0f)
                val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val out = File(dir, release.apkName)
                http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 }
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total != null) _state.value = State.Downloading(release, done.toFloat() / total)
                            }
                        }
                    }
                }
                release.sumsUrl?.let { url ->
                    val sums = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
                    val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
                        .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == release.apkName }?.first()
                    val actual = MessageDigest.getInstance("SHA-256").let { md ->
                        out.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
                        md.digest().joinToString("") { "%02x".format(it) }
                    }
                    check(expected == null || expected.equals(actual, true)) { "checksum mismatch" }
                }
                out
            }.onFailure { _state.value = State.Failed(release, it.message ?: it.javaClass.simpleName) }.getOrNull()
        } ?: return
        _state.value = State.Ready(release, file)
        install(context, file)
    }

    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
