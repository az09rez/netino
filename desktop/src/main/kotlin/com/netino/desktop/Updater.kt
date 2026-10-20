package com.netino.desktop

import com.netino.vpn.core.LocalRoutes
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
 * In-app updates from the project's GitHub Releases (same releases as the Android app):
 * check → download the Windows installer → verify its SHA-256 against SHA256SUMS-windows.txt → run it and exit.
 * The portable copy downloads the new zip next to itself instead (it can't be replaced while it runs).
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/az09rez/netino/releases/latest"

    data class Release(val version: String, val name: String, val url: String, val sumsUrl: String?, val page: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val version: String get() = System.getProperty("jpackage.app-version") ?: "0"

    /** The portable zip carries a marker file next to the app's jars (added by CI). */
    val portable: Boolean get() = Win.launcher?.let { File(File(it).parentFile, "app/portable").exists() } ?: false

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Directly first, then through the running connection (GitHub is slow or filtered on some networks). */
    private fun clients() = listOfNotNull(http, LocalRoutes.tunnel()?.let { LocalRoutes.via(http, it) })

    private fun <T> anyRoute(block: (OkHttpClient) -> T): T {
        var last: Throwable? = null
        for (c in clients()) try { return block(c) } catch (e: Exception) { last = e }
        throw last ?: IllegalStateException("no route")
    }

    /** "2.10.0" > "2.9.3"; non-numeric parts count as 0. */
    fun isNewer(remote: String, local: String = version): Boolean {
        val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** At most every 6 hours unless [force]; a development build ("0") never updates itself. */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (version == "0" || _state.value is State.Downloading) return@withContext
        val last = Store.read("update_checked")?.toLongOrNull() ?: 0
        if (!force && System.currentTimeMillis() - last < 6 * 3_600_000L) return@withContext
        if (force) _state.value = State.Checking
        runCatching {
            val body = anyRoute { c ->
                c.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute()
                    .use { r -> check(r.isSuccessful) { "HTTP ${r.code}" }; r.body!!.string() }
            }
            Store.write("update_checked", System.currentTimeMillis().toString())
            val o = Json.parseToJsonElement(body).jsonObject
            val v = o["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
            val urls = o["assets"]!!.jsonArray.map { it.jsonObject }
                .associate { it["name"]!!.jsonPrimitive.content to it["browser_download_url"]!!.jsonPrimitive.content }
            val suffix = if (portable) "-windows-x64-portable.zip" else "-windows-x64.msi"
            // The Windows files are attached a few minutes after the release appears
            val name = urls.keys.firstOrNull { it.endsWith(suffix) }
            if (!isNewer(v) || name == null) null
            else Release(v, name, urls.getValue(name), urls["SHA256SUMS-windows.txt"], o["html_url"]?.jsonPrimitive?.content.orEmpty())
        }.onSuccess { r -> _state.value = if (r != null) State.Available(r) else if (force) State.UpToDate else _state.value }
            .onFailure { if (force) _state.value = State.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** Downloads and verifies the file; then [install] runs it. */
    suspend fun download(release: Release) = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = State.Downloading(release, 0f)
            val dir = if (portable) File(Win.launcher!!).parentFile.parentFile
                else File(System.getProperty("java.io.tmpdir"), "netino-update").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, release.name)
            anyRoute { c ->
                c.newCall(Request.Builder().url(release.url).build()).execute().use { r ->
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
            }
            release.sumsUrl?.let { url ->
                val sums = anyRoute { c -> c.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() } }
                // The sums list the files as CI named them; the release has the same names
                val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
                    .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == release.name }?.first()
                val actual = MessageDigest.getInstance("SHA-256").let { md ->
                    out.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                if (expected != null && !expected.equals(actual, true)) { out.delete(); error("checksum mismatch") }
            }
            out
        }.onSuccess { _state.value = State.Ready(release, it) }
            .onFailure { _state.value = State.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Installed copy: the installer replaces this version, then Netino starts again. Runs detached, after
     * Netino has exited (its files are in use until then). Returns false if it couldn't be started.
     */
    fun install(file: File): Boolean {
        if (portable) {
            // The new zip sits next to the old folder: the user unpacks it over this one
            runCatching { ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start() }
            return false
        }
        val exe = Win.launcher ?: return false
        val cmd = "ping -n 3 127.0.0.1 >nul & start \"\" /wait msiexec /i \"${file.absolutePath}\" /passive & start \"\" \"$exe\" --restarted"
        return runCatching { ProcessBuilder("cmd.exe", "/c", cmd).start() }.isSuccess
    }
}
