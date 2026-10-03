package com.netino.vpn.core

import android.content.Context
import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.SplitMode
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.delay
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
    private var startedAt = 0L

    /** MTU used when neither the user nor the config sets one: safe on mobile / PPPoE / CDN paths in Iran. */
    private const val DEFAULT_MTU = 1280
    private const val KEEPALIVE = 25

    fun start(context: Context, confText: String, s: AppSettings) {
        val b = backend ?: GoBackend(context.applicationContext).also { backend = it }
        val conf = Config.parse(prepare(confText, s, context.packageName).byteInputStream())
        startedAt = System.currentTimeMillis()
        b.setState(tunnel, Tunnel.State.UP, conf)
        isUp = true
    }

    /**
     * Waits for the first handshake after [start]. Returns how long it took, or -1 if none arrived
     * (server unreachable / UDP blocked / wrong keys).
     */
    suspend fun awaitHandshake(timeoutMs: Long = 8000): Long {
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val last = runCatching {
                val st = backend!!.getStatistics(tunnel)
                st.peers().maxOfOrNull { st.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
            }.getOrDefault(0L)
            if (last >= startedAt) return last - startedAt
            delay(200)
        }
        return -1
    }

    // ---------------- config checks ----------------

    enum class Check { OK, AMNEZIA, INVALID }

    // wg-quick shell hooks / Linux-only keys the userspace backend rejects
    private val WG_QUICK_ONLY = setOf("preup", "postup", "predown", "postdown", "saveconfig", "table", "fwmark")
    // AmneziaWG obfuscation keys
    private val AMNEZIA_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime")
    private val AMNEZIA_NEUTRAL = mapOf("jc" to "0", "jmin" to "0", "jmax" to "0", "s1" to "0", "s2" to "0", "h1" to "1", "h2" to "2", "h3" to "3", "h4" to "4")

    private fun key(line: String) = line.substringBefore('=').trim().lowercase()

    /**
     * Drops keys plain WireGuard can't use. AmneziaWG keys are only dropped when they hold the neutral
     * values (then the server speaks plain WireGuard); real obfuscation can't be spoken by wireguard-go.
     */
    fun sanitize(text: String): String = text.lines().filterNot { line ->
        val k = key(line)
        k in WG_QUICK_ONLY || (k in AMNEZIA_KEYS && line.contains('='))
    }.joinToString("\n")

    fun check(text: String): Check {
        val obfuscated = text.lines().any { line ->
            val k = key(line)
            line.contains('=') && k in AMNEZIA_KEYS && AMNEZIA_NEUTRAL[k] != line.substringAfter('=').trim()
        }
        if (obfuscated) return Check.AMNEZIA
        return if (runCatching { Config.parse(sanitize(text).byteInputStream()) }.isSuccess) Check.OK else Check.INVALID
    }

    /** Final text handed to the backend: sanitized, split tunnel applied, MTU and keepalive tuned. */
    fun prepare(text: String, s: AppSettings, ownPackage: String): String {
        val lines = applySplit(sanitize(text), s, ownPackage).lines().toMutableList()
        // MTU: user choice > config value > 1280. Oversized packets are silently dropped on many
        // mobile networks, which looks like "connected but nothing loads".
        val mtuIdx = lines.indexOfFirst { key(it) == "mtu" }
        val mtu = when {
            s.wgMtu > 0 -> s.wgMtu
            mtuIdx >= 0 -> lines[mtuIdx].substringAfter('=').trim().toIntOrNull() ?: DEFAULT_MTU
            else -> DEFAULT_MTU
        }
        if (mtuIdx >= 0) lines.removeAt(mtuIdx)
        val iIdx = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
        lines.add(iIdx + 1, "MTU = $mtu")
        // Keepalive keeps the NAT mapping of mobile carriers open, so the tunnel doesn't silently die when idle
        // and the health check sees a fresh handshake.
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().equals("[Peer]", true)) {
                val end = (i + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
                if ((i + 1 until end).none { key(lines[it]) == "persistentkeepalive" }) lines.add(i + 1, "PersistentKeepalive = $KEEPALIVE")
            }
            i++
        }
        return lines.joinToString("\n")
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
