package com.netino.vpn.core

import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.CdnMethod
import com.netino.vpn.data.NetKey
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds which way to Cloudflare the current network's firewall lets through. The SIM operator says
 * little (an MCI SIM can sit behind an Irancell-type firewall and the other way round), so instead of
 * guessing, the best few Cloudflare configs are tested with every method at once, in one batch core:
 * the method that brings the most of them through, fastest, wins and is remembered for this network.
 */
object CdnDetector {

    data class Outcome(val method: CdnMethod, val working: Map<CdnMethod, Int>, val tested: Int)

    private const val RECHECK_MS = 6 * 3_600_000L
    private const val SAMPLE = 4
    private const val TIME_LIMIT_MS = 25_000L

    /** Auto mode and no fresh result for this network. */
    fun due(s: AppSettings, now: Long = System.currentTimeMillis()) =
        s.cdnForced == null && now - (s.cdnCheckedAt[NetKey.current] ?: 0L) > RECHECK_MS

    private val CDN_NETS = setOf("ws", "httpupgrade", "xhttp", "splithttp", "grpc", "h2", "http")
    private val CF_PORTS = setOf(443, 2053, 2083, 2087, 2096, 8443)

    /**
     * Marks the TLS CDN configs of [servers] that sit behind Cloudflare (their address, or its DNS answer,
     * is a Cloudflare IP). Workers and Pages domains count without a lookup.
     */
    suspend fun markCloudflare(servers: List<Server>) = withContext(Dispatchers.IO) {
        val unknown = servers.filter { s ->
            val x = s.xray
            !s.useCleanIp && x != null && x.security == "tls" && x.network in CDN_NETS && s.port in CF_PORTS &&
                s.protocol != Protocol.WIREGUARD && s.protocol != Protocol.HYSTERIA2
        }
        if (unknown.isEmpty()) return@withContext
        val gate = Semaphore(16)
        val found = coroutineScope {
            unknown.map { s ->
                async {
                    gate.withPermit {
                        val host = s.address.lowercase()
                        val cf = host.endsWith(".workers.dev") || host.endsWith(".pages.dev") ||
                            (resolve(host)?.let(CleanIpScanner::inCloudflare) ?: false)
                        if (cf) s.id else null
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        if (found.isNotEmpty()) Repository.markCloudflare(found)
    }

    private fun resolve(host: String): String? =
        if (host.all { it.isDigit() || it == '.' }) host
        else runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()

    /**
     * Tests every method on the best [SAMPLE] Cloudflare configs among [candidates] and stores the winner for
     * the current network. Null when there is nothing to test with (no Cloudflare configs) or nothing worked.
     */
    suspend fun detect(candidates: List<Server>, settings: AppSettings): Outcome? {
        val net = NetKey.current
        val sample = candidates.filter(XrayConfigBuilder::isCdn).sortedBy { it.score ?: Long.MAX_VALUE }.take(SAMPLE)
        if (sample.isEmpty()) return null
        val variants = sample.flatMap { s -> CdnMethod.entries.map { m -> s.copy(id = "${s.id}#${m.name}", cdnOverride = m) } }
        val results = withTimeoutOrNull(TIME_LIMIT_MS) { BatchTester.raw(variants, settings) }.orEmpty()
        Repository.updateSettings { it.copy(cdnCheckedAt = it.cdnCheckedAt + (net to System.currentTimeMillis())) }
        val ok = results.filter { it.ms > 0 }
        if (ok.isEmpty()) return null
        val byMethod = ok.groupBy { it.server.cdnOverride ?: CdnMethod.PLAIN }
        val working = CdnMethod.entries.associateWith { byMethod[it]?.size ?: 0 }
        fun median(m: CdnMethod) = byMethod[m]?.map { it.ms }?.sorted()?.let { it[it.size / 2] } ?: Long.MAX_VALUE
        // Most configs through first, then speed; plain wins ties so nothing is rewritten without need
        val best = CdnMethod.entries.maxWith(
            compareBy<CdnMethod> { working[it] ?: 0 }.thenByDescending { median(it) }.thenBy { if (it == CdnMethod.PLAIN) 1 else 0 }
        )
        Repository.updateSettings { it.copy(cdnByNet = it.cdnByNet + (net to best)) }
        return Outcome(best, working, sample.size)
    }
}
