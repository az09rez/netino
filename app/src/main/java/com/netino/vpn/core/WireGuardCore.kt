package com.netino.vpn.core

import android.content.Context
import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.SplitMode
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.net.Inet4Address
import java.net.InetAddress

/** Official WireGuard userspace backend (wireguard-go) with split tunnelling applied to the config. */
object WireGuardCore {

    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "amnrah"
        override fun onStateChange(newState: Tunnel.State) {}
    }
    var isUp = false
        private set

    fun start(context: Context, confText: String, s: AppSettings) {
        val b = backend ?: GoBackend(context.applicationContext).also { backend = it }
        val conf = Config.parse(applySplit(confText, s, context.packageName).byteInputStream())
        b.setState(tunnel, Tunnel.State.UP, conf)
        isUp = true
    }

    fun stop() {
        runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
        isUp = false
    }

    /** Bytes (rx, tx) reported by wireguard-go. */
    fun stats(): Pair<Long, Long> = runCatching {
        val st = backend!!.getStatistics(tunnel)
        st.totalRx() to st.totalTx()
    }.getOrDefault(0L to 0L)

    /** Last handshake age check — true if any peer completed a handshake in the last 3 minutes. */
    fun isHealthy(): Boolean = runCatching {
        val st = backend!!.getStatistics(tunnel)
        val now = System.currentTimeMillis()
        st.peers().any { st.peer(it)?.let { p -> now - p.latestHandshakeEpochMillis() < 180_000 } == true }
    }.getOrDefault(false)

    // ---------------- split tunnel -> wg-quick text ----------------
    fun applySplit(text: String, s: AppSettings, ownPackage: String): String {
        val sp = s.split
        val lines = text.lines().filterNot {
            val k = it.substringBefore('=').trim().lowercase()
            k == "includedapplications" || k == "excludedapplications" || k == "allowedips"
        }.toMutableList()

        // Applications
        // Netino itself is always outside the tunnel so latency tests measure the real device -> server path
        val appLine = when (sp.appMode) {
            SplitMode.ONLY -> sp.apps.filter { it != ownPackage }.takeIf { it.isNotEmpty() }
                ?.let { "IncludedApplications = ${it.joinToString(", ")}" }
            SplitMode.BYPASS -> "ExcludedApplications = ${(sp.apps + ownPackage).joinToString(", ")}"
            SplitMode.OFF -> "ExcludedApplications = $ownPackage"
        }

        // Routes (WireGuard routes by IP, so domains are resolved once at connect time)
        val ipList = sp.ips.filterNot { it.startsWith("geo") } +
            sp.domains.filterNot { it.contains(':') }.flatMap { resolve(it) }
        val allowed: List<String> = when (sp.routeMode) {
            SplitMode.ONLY -> ipList.ifEmpty { listOf("0.0.0.0/0") }
            else -> {
                val excluded = (if (sp.routeMode == SplitMode.BYPASS) ipList else emptyList()) +
                    (if (s.bypassLan) XrayConfigBuilder.PRIVATE_CIDRS else emptyList())
                // ::/0 is always captured so IPv6 can never leak around the tunnel
                Cidr.subtract("0.0.0.0/0", excluded.filter { !it.contains(':') }) + "::/0"
            }
        }

        val iIdx = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
        if (appLine != null && iIdx >= 0) lines.add(iIdx + 1, appLine)
        if (lines.none { it.trim().lowercase().startsWith("dns") }) lines.add(iIdx + 1, "DNS = 1.1.1.1, 1.0.0.1")
        val pIdx = lines.indexOfFirst { it.trim().equals("[Peer]", true) }
        lines.add(pIdx + 1, "AllowedIPs = ${allowed.joinToString(", ")}")
        return lines.joinToString("\n")
    }

    private fun resolve(domain: String): List<String> {
        val host = domain.trim().removePrefix("*.").removePrefix(".").substringBefore('/')
        return runCatching { InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().map { "${it.hostAddress}/32" } }
            .getOrDefault(emptyList())
    }
}

/** IPv4 CIDR arithmetic for building AllowedIPs = everything minus excluded ranges. */
object Cidr {
    private fun parse(c: String): LongRange {
        val ip = c.substringBefore('/')
        val bits = c.substringAfter('/', "32").toInt()
        val n = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
        val size = 1L shl (32 - bits)
        val start = n and (size - 1).inv() and 0xFFFFFFFFL
        return start..(start + size - 1)
    }

    fun subtract(base: String, excluded: List<String>): List<String> {
        var ranges = listOf(parse(base))
        for (ex in excluded.mapNotNull { runCatching { parse(it) }.getOrNull() }) {
            ranges = ranges.flatMap { r ->
                if (ex.last < r.first || ex.first > r.last) listOf(r)
                else listOfNotNull(
                    if (ex.first > r.first) r.first until ex.first else null,
                    if (ex.last < r.last) (ex.last + 1)..r.last else null,
                )
            }
        }
        return ranges.flatMap { toCidrs(it) }
    }

    private fun toCidrs(r: LongRange): List<String> {
        val out = mutableListOf<String>()
        var start = r.first
        while (start <= r.last) {
            var size = 32
            while (size > 0) {
                val mask = 1L shl (33 - size)
                if (start % mask != 0L || start + mask - 1 > r.last) break
                size--
            }
            out += "${(start shr 24) and 255}.${(start shr 16) and 255}.${(start shr 8) and 255}.${start and 255}/$size"
            start += 1L shl (32 - size)
        }
        return out
    }
}
