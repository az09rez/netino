package com.netino.vpn.core

import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Finds Cloudflare IPs that are reachable *on the current network*: many Cloudflare ranges are
 * filtered or throttled in Iran, and which ones work differs per operator. Each candidate gets a TCP
 * connect + TLS handshake + one HTTPS request (/cdn-cgi/trace) – the same path a CDN config uses.
 * The app runs outside the VPN, so the scan measures the real network even while connected.
 */
object CleanIpScanner {

    data class Hit(val ip: String, val ms: Long)

    /** Cloudflare's published IPv4 ranges. */
    val RANGES = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    private const val SNI = "speed.cloudflare.com"

    private fun parse(cidr: String): Pair<Long, Int> {
        val (ip, bits) = cidr.split('/')
        return ip.split('.').fold(0L) { a, p -> (a shl 8) or p.toLong() } to bits.toInt()
    }

    private fun toIp(n: Long) = "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"

    fun inCloudflare(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val n = parts.fold(0L) { a, p -> (a shl 8) or p.toLong() }
        return RANGES.any { r -> val (base, bits) = parse(r); val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL; (n and mask) == (base and mask) }
    }

    /** Random candidates spread over all ranges (bigger ranges get more samples, every range at least a few). */
    private fun candidates(count: Int): List<String> {
        val sizes = RANGES.map { 1L shl (32 - parse(it).second) }
        val total = sizes.sum().toDouble()
        return RANGES.flatMapIndexed { i, r ->
            val (base, bits) = parse(r)
            val n = maxOf(4, (count * sizes[i] / total).toInt())
            List(n) { toIp(base + 1 + Random.nextLong((1L shl (32 - bits)) - 2)) }
        }.distinct().shuffled()
    }

    /** One probe: -1 if the IP doesn't answer like Cloudflare within the timeout. */
    fun probe(ip: String, timeoutMs: Int = 2500): Long = runCatching {
        val t0 = System.nanoTime()
        Socket().use { raw ->
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(ip, 443), timeoutMs)
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, SNI, 443, true).use { s ->
                s as SSLSocket
                s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(SNI)) }
                s.startHandshake()
                s.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $SNI\r\nConnection: close\r\n\r\n".toByteArray())
                s.outputStream.flush()
                val body = s.inputStream.bufferedReader().readText()
                if (!body.contains("h=$SNI")) return -1
            }
        }
        ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
    }.getOrDefault(-1)

    /** Scans [count] random Cloudflare IPs and returns the best [keep], fastest first. */
    suspend fun scan(count: Int = 300, keep: Int = 10, onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> }): List<Hit> =
        withContext(Dispatchers.IO) {
            val list = candidates(count)
            val done = AtomicInteger()
            val found = AtomicInteger()
            val gate = Semaphore(32)
            coroutineScope {
                list.map { ip ->
                    async {
                        gate.withPermit {
                            val ms = probe(ip)
                            if (ms > 0) found.incrementAndGet()
                            onProgress(done.incrementAndGet(), list.size, found.get())
                            if (ms > 0) Hit(ip, ms) else null
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
                // Re-check the best few so one lucky sample doesn't win
                .sortedBy { it.ms }.take(keep * 2)
                .map { h -> val again = probe(h.ip); if (again > 0) Hit(h.ip, (h.ms + again) / 2) else null }
                .filterNotNull().sortedBy { it.ms }.take(keep)
        }

    private val CDN_NETWORKS = setOf("ws", "grpc", "httpupgrade", "xhttp", "splithttp", "h2", "http")
    private val CF_PORTS = setOf(443, 2053, 2083, 2087, 2096, 8443, 80, 8080, 8880, 2052, 2082, 2086, 2095)

    /**
     * Servers that sit behind Cloudflare and can use a clean IP: a CDN transport on a Cloudflare port
     * whose address (or its DNS answer) is a Cloudflare IP. Blocking (DNS).
     */
    fun cloudflareServers(servers: List<Server>): Set<String> = servers.filter { s ->
        val x = s.xray ?: return@filter false
        if (s.protocol == Protocol.WIREGUARD || s.protocol == Protocol.HYSTERIA2) return@filter false
        if (x.network !in CDN_NETWORKS || s.port !in CF_PORTS) return@filter false
        val ip = if (s.address.all { it.isDigit() || it == '.' }) s.address
        else runCatching { InetAddress.getAllByName(s.address).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
        ip != null && inCloudflare(ip)
    }.map { it.id }.toSet()
}
