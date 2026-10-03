package com.netino.vpn.core

import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Latency measurement in the style of NPV Tunnel / v2rayNG:
 *
 *  • Quick ping (TCP): DNS is resolved once *outside* the timer, then the TCP handshake to
 *    server:port is timed 3 times and the median is reported – only network RTT, no DNS noise.
 *  • ICMP ping: for UDP-only protocols (WireGuard) the system `ping` binary is used (no root needed).
 *  • Real delay: a full HTTP request to a 204 endpoint *through the actual protocol*
 *    (proxy handshake + TLS/Reality + request), run in a throw-away Xray instance. Best of 2.
 *    This proves the config really works, not just that the port is open.
 *
 * All measurements run from the app's own sockets, which are excluded from the tunnel,
 * so they reflect the real path device → server even while connected.
 */
object Pinger {

    const val FAILED = -2L
    private const val TIMEOUT_MS = 3000

    data class Result(val server: Server, val ms: Long, val kind: PingKind, val error: String? = null)

    // ---------------- primitives ----------------

    suspend fun tcp(host: String, port: Int, samples: Int = 3): Long = withContext(Dispatchers.IO) {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext FAILED
        val times = (1..samples).mapNotNull {
            runCatching {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    val t = System.nanoTime()
                    s.connect(InetSocketAddress(addr, port), TIMEOUT_MS)
                    (System.nanoTime() - t) / 1_000_000
                }
            }.getOrNull()
        }
        if (times.isEmpty()) FAILED else times.sorted()[times.size / 2].coerceAtLeast(1)
    }

    private val rttRegex = Regex("""=\s*[\d.]+/([\d.]+)/""")   // rtt min/avg/max/mdev = 1.1/2.2/3.3/0.4 ms

    suspend fun icmp(host: String): Long = withContext(Dispatchers.IO) {
        val viaBinary = runCatching {
            val p = ProcessBuilder("/system/bin/ping", "-c", "3", "-i", "0.2", "-W", "2", "-q", host)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            rttRegex.find(out)?.groupValues?.get(1)?.toDouble()?.toLong()?.coerceAtLeast(1)
        }.getOrNull()
        viaBinary ?: runCatching {
            val a = InetAddress.getByName(host)
            val t = System.nanoTime()
            if (a.isReachable(TIMEOUT_MS)) (System.nanoTime() - t) / 1_000_000 else FAILED
        }.getOrDefault(FAILED)
    }

    /** Full request through the protocol (libv2ray already retries twice and keeps the best). */
    suspend fun real(server: Server, settings: AppSettings): XrayCore.Delay = withContext(Dispatchers.IO) {
        val config = try {
            XrayConfigBuilder.build(server, settings, XrayConfigBuilder.Inbound.None)
        } catch (e: Exception) {
            return@withContext XrayCore.Delay(-1, "config: ${e.message}")
        }
        val d = XrayCore.measureOffline(config, settings.testUrl)
        // One fallback URL (some networks block gstatic): same idea as v2rayNG's second test URL
        if (d.ms > 0) d else XrayCore.measureOffline(config, FALLBACK_URL).let { if (it.ms > 0) it else d }
    }

    private const val FALLBACK_URL = "https://cp.cloudflare.com/generate_204"

    /** Quick measurement suited to the protocol. */
    suspend fun quick(server: Server): Result =
        if (server.protocol == Protocol.WIREGUARD) Result(server, icmp(server.address), PingKind.ICMP)
        else Result(server, tcp(server.address, server.port), PingKind.TCP)

    /** Real measurement (WireGuard can't be tested offline, so it falls back to ICMP). */
    suspend fun realOrIcmp(server: Server, settings: AppSettings): Result =
        if (server.protocol == Protocol.WIREGUARD) Result(server, icmp(server.address), PingKind.ICMP)
        else real(server, settings).let { Result(server, if (it.ms > 0) it.ms else FAILED, PingKind.REAL, it.error) }

    // ---------------- batch ----------------

    suspend fun measureAll(
        servers: List<Server>, settings: AppSettings, real: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (Result) -> Unit,
    ): List<Result> = coroutineScope {
        val gate = Semaphore(if (real) 8 else 24)
        val done = AtomicInteger()
        servers.map { s ->
            async {
                gate.withPermit {
                    val r = if (real) realOrIcmp(s, settings) else quick(s)
                    onResult(r)
                    onProgress(done.incrementAndGet(), servers.size)
                    r
                }
            }
        }.awaitAll()
    }

    /**
     * Fastest-server selection decided **only by real delay** from this device:
     *  1. quick TCP/ICMP check of every server — only used to skip servers whose port is closed
     *     (for CDN fronted configs the TCP time is the CDN edge, so it is never used for ranking)
     *  2. real end-to-end delay through the protocol for every reachable server (up to [maxReal])
     *  3. lowest real delay wins; if no server passes the real test, nothing is chosen.
     * WireGuard has no offline HTTP test; its ICMP RTT is scaled by ~3 (TCP + TLS + request
     * round trips) so it compares fairly with Xray real delays.
     */
    suspend fun findFastest(
        servers: List<Server>, settings: AppSettings, maxReal: Int = 40,
        onStage: (stage: Int, done: Int, total: Int) -> Unit = { _, _, _ -> },
        onResult: (Result) -> Unit = {},
    ): Server? {
        if (servers.isEmpty()) return null
        val quickResults = measureAll(servers, settings, real = false, onProgress = { d, t -> onStage(1, d, t) }, onResult = {})
        val reachable = quickResults.filter { it.ms > 0 }.sortedBy { it.ms }.map { it.server }
        // If TCP is filtered on this network, still try the real test (it may pass through the CDN)
        val candidates = (reachable.ifEmpty { servers }).take(maxReal)
        onStage(2, 0, candidates.size)
        val real = measureAll(candidates, settings, real = true, onProgress = { d, t -> onStage(2, d, t) }, onResult = onResult)
        return real.filter { it.ms > 0 }
            .minByOrNull { if (it.kind == PingKind.ICMP) it.ms * 3 else it.ms }
            ?.server
    }
}
