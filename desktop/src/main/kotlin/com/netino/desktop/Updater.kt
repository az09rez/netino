package com.netino.desktop

import com.netino.vpn.core.LocalRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the project's GitHub Releases, on this system's own channel ([UpdateChannel]: Windows
 * "windows-v<version>", Linux "linux-v<version>"; the Android app's are "v<version>"):
 * check → download the installer → verify its SHA-256 against the channel's SHA256SUMS → run it and exit.
 * The portable copy downloads the new archive next to itself instead (it can't be replaced while it runs).
 */
object Updater {

    /** Recent releases of every app; the newest one on this system's channel is ours. */
    private const val RELEASES = "https://api.github.com/repos/az09rez/netino/releases?per_page=20"

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

    /** The portable zip / tar.gz carries a marker file next to the app's jars (added by CI). */
    val portable: Boolean get() = Platform.appDir?.let { File(it, "portable").exists() } ?: false

    private val channel get() = UpdateChannel.current

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

    fun isNewer(remote: String, local: String = version) = UpdateChannel.isNewer(remote, local)

    /** At most every 6 hours unless [force]; a development build ("0") never updates itself. */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (version == "0" || _state.value is State.Downloading) return@withContext
        val last = Store.read("update_checked")?.toLongOrNull() ?: 0
        if (!force && System.currentTimeMillis() - last < 6 * 3_600_000L) return@withContext
        if (force) _state.value = State.Checking
        runCatching {
            val body = anyRoute { c ->
                c.newCall(Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()).execute()
                    .use { r -> check(r.isSuccessful) { "HTTP ${r.code}" }; r.body!!.string() }
            }
            Store.write("update_checked", System.currentTimeMillis().toString())
            channel.pick(body, version, portable)
        }.onSuccess { r -> _state.value = if (r != null) State.Available(r) else if (force) State.UpToDate else _state.value }
            .onFailure { if (force) _state.value = State.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** Downloads and verifies the file; then [install] runs it. */
    suspend fun download(release: Release) = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = State.Downloading(release, 0f)
            val dir = if (portable) Platform.installRoot!!.parentFile
                // Linux: in Netino's own folder, not the shared /tmp another user could prepare
                else (if (Platform.isLinux) File(Store.dir, "update") else File(System.getProperty("java.io.tmpdir"), "netino-update"))
                    .apply { deleteRecursively(); mkdirs() }
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
            // Linux (a new channel) never installs a file it couldn't check; Windows keeps its older behaviour
            if (release.sumsUrl == null && channel.strict) { out.delete(); error("no checksums in the release") }
            release.sumsUrl?.let { url ->
                val sums = anyRoute { c -> c.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() } }
                // The sums list the files as CI named them; the release has the same names
                val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
                    .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == release.name }?.first()
                val actual = MessageDigest.getInstance("SHA-256").let { md ->
                    out.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                if (expected == null && channel.strict) { out.delete(); error("${release.name} is not in the checksums") }
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
            // The new archive sits next to the old folder: the user unpacks it over this one
            Platform.reveal(file)
            return false
        }
        val exe = Win.launcher ?: return false
        if (Platform.isLinux) return installDeb(file, exe)
        val cmd = "ping -n 3 127.0.0.1 >nul & start \"\" /wait msiexec /i \"${file.absolutePath}\" /passive & start \"\" \"$exe\" --restarted"
        return runCatching { ProcessBuilder("cmd.exe", "/c", cmd).start() }.isSuccess
    }

    /**
     * Linux: the .deb through pkexec (the desktop asks for the password), then Netino starts again. Detached
     * (setsid) so it carries on after this Netino exits; dpkg can replace the files of a running program.
     */
    private fun installDeb(file: File, exe: String): Boolean {
        if (!Platform.has("pkexec")) return false
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val cmd = "sleep 1; pkexec dpkg -i ${q(file.absolutePath)} || pkexec apt-get install -y ${q(file.absolutePath)}; exec ${q(exe)} --restarted"
        val launcher = if (Platform.has("setsid")) listOf("setsid", "sh", "-c", cmd) else listOf("sh", "-c", cmd)
        return runCatching { ProcessBuilder(launcher).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.isSuccess
    }
}
