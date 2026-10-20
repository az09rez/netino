package com.netino.vpn.core

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Xray-core on Windows: xray.exe as a child process (config passed on stdin, never written to disk).
 * Same API as the Android wrapper around libv2ray, so the shared code (testers, WARP, ...) runs unchanged.
 */
object XrayCore {

    /** A running core process. */
    class Core internal constructor(internal val process: Process, internal val log: ArrayDeque<String>) {
        fun stopLoop() {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        val alive get() = process.isAlive
    }

    private var controller: Core? = null
    var hasGeoFiles = false
        private set
    /** Where the core's files live (geo files included). */
    lateinit var dir: File
        private set
    @Volatile var geoSiteTags: Set<String> = emptySet()
        private set
    @Volatile var geoIpTags: Set<String> = emptySet()
        private set

    /** Receives core status lines (errors, startup) for the live report. */
    var onStatus: (String) -> Unit = {}
    /**
     * While the full tunnel runs, the network interface that leads out directly ("Wi-Fi"): test cores bind their
     * connections to it, or they would test servers through the tunnel itself.
     */
    @Volatile var outboundInterface: String? = null

    /** Called when the main core exits by itself (crash, rejected config after start). */
    var onExit: () -> Unit = {}

    /** xray.exe: next to the app (installed), or NETINO_XRAY / build/xray/windows while developing. */
    private val exe: File by lazy {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "xray.exe" else "xray"
        listOfNotNull(
            System.getenv("NETINO_XRAY")?.let(::File),
            System.getProperty("compose.application.resources.dir")?.let { File(it, name) },
            File("build/xray/windows", name),
        ).firstOrNull { it.isFile } ?: File(name)
    }

    /** xray.exe's full path (Windows Firewall rules name the program). */
    val exePath: String get() = exe.absolutePath

    fun init(context: Context) {
        dir = File(context.filesDir, "xray").apply { mkdirs() }
        killOrphans()
        // Geo files (Iran lists) are downloaded on first start, see Main
        loadGeoTags()
    }

    /** A core left behind by a crash would keep its ports (and the system proxy pointing at it). */
    private fun killOrphans() = runCatching {
        val path = exe.absolutePath
        ProcessHandle.allProcesses()
            .filter { p -> p.info().command().map { it.equals(path, ignoreCase = true) }.orElse(false) }
            .forEach { it.destroyForcibly() }
    }

    fun loadGeoTags() {
        val lines = runCatching { File(dir, GeoFiles.TAGS).readLines() }.getOrDefault(emptyList())
        geoSiteTags = lines.filter { it.startsWith("site:") }.mapTo(mutableSetOf()) { it.removePrefix("site:") }
        geoIpTags = lines.filter { it.startsWith("ip:") }.mapTo(mutableSetOf()) { it.removePrefix("ip:") }
        hasGeoFiles = File(dir, "geoip.dat").exists() && File(dir, "geosite.dat").exists() && geoSiteTags.isNotEmpty()
    }

    val version: String by lazy {
        runCatching {
            val p = ProcessBuilder(exe.path, "version").redirectErrorStream(true).start()
            val first = p.inputStream.bufferedReader().readLine().orEmpty()
            p.waitFor(5, TimeUnit.SECONDS)
            first.split(' ').getOrNull(1) ?: "?"
        }.getOrDefault("?")
    }

    /** Starts the main core; throws (with Xray's own error) if it doesn't come up. [tunFd] is unused on Windows. */
    @Synchronized
    fun start(config: String, @Suppress("UNUSED_PARAMETER") tunFd: Int = 0) {
        stop()
        val c = launch(config, status = true)
        controller = c
        Thread {
            c.process.waitFor()
            if (controller === c) { controller = null; onExit() }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        controller = null
        runCatching { c.stopLoop() }
    }

    val isRunning get() = controller?.alive == true

    /** A second, short-lived core (tests, fragment route). Throws if it can't start. */
    fun startTestCore(config: String): Core = launch(config, status = false)

    fun stopTestCore(c: Core) { runCatching { c.stopLoop() } }

    private fun launch(config0: String, status: Boolean): Core {
        val config = if (status) config0 else outboundInterface?.let { bindTo(config0, it) } ?: config0
        val pb = ProcessBuilder(exe.absolutePath, "run", "-format", "json", "-c", "stdin:")
            .directory(dir)
            .redirectErrorStream(true)
        pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
        val p = pb.start()
        val log = ArrayDeque<String>()
        Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast(line); if (log.size > 30) log.removeFirst() }
                    if (status && line.isNotBlank()) onStatus(line)
                }
            }
        }.apply { isDaemon = true }.start()
        p.outputStream.use { it.write(config.toByteArray(Charsets.UTF_8)) }
        val core = Core(p, log)
        awaitReady(core, inboundPorts(config))
        return core
    }

    /** Waits until every local inbound accepts connections; throws with the core's output if it exits instead. */
    private fun awaitReady(core: Core, ports: List<Int>) {
        val deadline = System.currentTimeMillis() + 10_000
        val pending = ports.toMutableList()
        while (true) {
            if (!core.alive) {
                Thread.sleep(100) // let the reader catch the last lines
                val why = synchronized(core.log) { core.log.lastOrNull { it.contains("failed", true) || it.contains("error", true) } ?: core.log.lastOrNull() }
                throw IllegalStateException(why?.take(300) ?: "xray exited")
            }
            pending.removeAll { port -> runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess }
            if (pending.isEmpty()) {
                if (ports.isEmpty()) Thread.sleep(300)
                if (core.alive) return
                continue
            }
            if (System.currentTimeMillis() > deadline) {
                core.stopLoop()
                throw IllegalStateException("xray didn't open its ports")
            }
            Thread.sleep(50)
        }
    }

    /** Every outbound that dials the network itself (not through another outbound) leaves through [iface]. */
    private fun bindTo(config: String, iface: String): String = runCatching {
        val root = Json.parseToJsonElement(config).jsonObject
        val outbounds = root["outbounds"]?.jsonArray ?: return config
        JsonObject(root + ("outbounds" to kotlinx.serialization.json.JsonArray(outbounds.map { ob ->
            val o = ob.jsonObject
            val protocol = o["protocol"]?.jsonPrimitive?.contentOrNull
            val stream = o["streamSettings"]?.jsonObject ?: JsonObject(emptyMap())
            val sockopt = stream["sockopt"]?.jsonObject ?: JsonObject(emptyMap())
            if (protocol == "blackhole" || protocol == "dns" || "dialerProxy" in sockopt) ob
            else JsonObject(o + ("streamSettings" to JsonObject(stream + ("sockopt" to JsonObject(sockopt + ("interface" to JsonPrimitive(iface)))))))
        }))).toString()
    }.getOrDefault(config)

    private fun inboundPorts(config: String): List<Int> = runCatching {
        Json.parseToJsonElement(config).jsonObject["inbounds"]?.jsonArray.orEmpty().mapNotNull { ib ->
            val o = ib.jsonObject
            val listen = o["listen"]?.jsonPrimitive?.contentOrNull ?: "0.0.0.0"
            // The TUN inbound listens on no port (its "port" is 0)
            o["port"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 && o["protocol"]?.jsonPrimitive?.contentOrNull != "tun" && (listen == "127.0.0.1" || listen == "0.0.0.0") }
        }
    }.getOrDefault(emptyList())

    /** Real end-to-end delay through the running core's probe inbound (ms), or -1. */
    fun measureRunning(url: String = TEST_URL): Long {
        val ep = com.netino.vpn.service.XrayVpnService.probe ?: return -1
        if (!isRunning) return -1
        return SpeedProbe.delay(ep.port, ep.user, ep.pass, url)
    }

    data class Delay(val ms: Long, val error: String?)

    /**
     * Real delay for a server that is NOT running: its outbound in a throw-away core behind a local SOCKS
     * inbound, best of 2 HTTPS requests.
     */
    fun measureOffline(config: String, url: String = TEST_URL): Delay {
        val ep = HevTunnel.newEndpoint()
        val withInbound = try {
            val root = Json.parseToJsonElement(config).jsonObject
            JsonObject(root + mapOf(
                "inbounds" to buildJsonArray {
                    add(buildJsonObject {
                        put("listen", "127.0.0.1"); put("port", ep.port); put("protocol", "socks")
                        putJsonObject("settings") {
                            put("auth", "password")
                            putJsonArray("accounts") { add(buildJsonObject { put("user", ep.user); put("pass", ep.pass) }) }
                            put("udp", false)
                        }
                    })
                },
                "log" to buildJsonObject { put("loglevel", JsonPrimitive("error")) },
            )).toString()
        } catch (e: Exception) {
            return Delay(-1, "config: ${e.message}")
        }
        val core = try {
            startTestCore(withInbound)
        } catch (e: Exception) {
            return Delay(-1, e.message?.lineSequence()?.firstOrNull()?.take(300) ?: e.javaClass.simpleName)
        }
        return try {
            val ms = SpeedProbe.delay(ep.port, ep.user, ep.pass, url)
            if (ms > 0) Delay(ms, null) else Delay(-1, "no response")
        } finally {
            stopTestCore(core)
        }
    }

    const val TEST_URL = "https://www.gstatic.com/generate_204"
}
