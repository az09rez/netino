package com.netino.vpn.core

import android.util.Base64
import com.netino.vpn.data.Server
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * WireGuard configs (wg-quick text). Since 2.0.4 they run through Xray's own WireGuard outbound,
 * in the same tunnel as every other server: loading wireguard-go next to the Xray core put two Go
 * runtimes in one process, which crashed the app the moment WireGuard started.
 */
object WireGuardCore {

    data class Peer(
        val publicKey: String, val presharedKey: String?, val endpoint: String,
        val keepalive: Int?, val allowedIps: List<String>,
    )

    /** [reserved]: the 3 "reserved" bytes Cloudflare WARP uses to identify a client (from WARP configs). */
    data class Conf(val privateKey: String, val addresses: List<String>, val mtu: Int?, val peers: List<Peer>, val reserved: List<Int>? = null)

    enum class Check { OK, AMNEZIA, INVALID }

    // wg-quick shell hooks / Linux-only keys that don't matter for a userspace client
    private val WG_QUICK_ONLY = setOf("preup", "postup", "predown", "postdown", "saveconfig", "table", "fwmark")
    // AmneziaWG obfuscation keys; only the neutral values mean the server speaks plain WireGuard
    private val AMNEZIA_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime")
    private val AMNEZIA_NEUTRAL = mapOf("jc" to "0", "jmin" to "0", "jmax" to "0", "s1" to "0", "s2" to "0", "h1" to "1", "h2" to "2", "h3" to "3", "h4" to "4")

    private fun key(line: String) = line.substringBefore('=').trim().lowercase()

    /** Drops keys plain WireGuard doesn't use (wg-quick hooks, neutral AmneziaWG values). */
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
        return if (parse(text) != null) Check.OK else Check.INVALID
    }

    private fun isKey(s: String) = runCatching { Base64.decode(s, Base64.DEFAULT).size == 32 }.getOrDefault(false)

    /**
     * Private keys [s] connects with (WARP in WARP: the inner and the outer hop); empty for other protocols.
     * Cloudflare keeps one live session per key: two connections with the same key cut each other off.
     */
    fun keys(s: Server): Set<String> =
        listOfNotNull(s.wgConf, s.wgOuter).mapNotNullTo(mutableSetOf()) { parse(it)?.privateKey }

    /** Parses wg-quick text; null if a required field is missing or a key is malformed. */
    fun parse(text: String): Conf? {
        var section = ""
        var privateKey: String? = null
        val addresses = mutableListOf<String>()
        var mtu: Int? = null
        var reserved: List<Int>? = null
        val peers = mutableListOf<MutableMap<String, String>>()
        for (raw in sanitize(text).lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                section = line.lowercase()
                if (section == "[peer]") peers += mutableMapOf()
                continue
            }
            if (!line.contains('=')) continue
            val k = key(line)
            val v = line.substringAfter('=').trim()
            when (section) {
                "[interface]" -> when (k) {
                    "privatekey" -> privateKey = v
                    "address" -> addresses += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "mtu" -> mtu = v.toIntOrNull()
                    // "Reserved = 12, 34, 56" (v2rayNG / Hiddify WARP exports) or base64 of the 3 bytes
                    "reserved" -> reserved = parseReserved(v)
                }
                "[peer]" -> peers.last()[k] = v
            }
        }
        val pk = privateKey?.takeIf(::isKey) ?: return null
        val parsedPeers = peers.mapNotNull { p ->
            val pub = p["publickey"]?.takeIf(::isKey) ?: return@mapNotNull null
            val ep = p["endpoint"] ?: return@mapNotNull null
            Peer(
                publicKey = pub,
                presharedKey = p["presharedkey"]?.takeIf(::isKey),
                endpoint = ep,
                keepalive = p["persistentkeepalive"]?.toIntOrNull(),
                allowedIps = p["allowedips"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }
        if (parsedPeers.isEmpty() || addresses.isEmpty()) return null
        return Conf(pk, addresses, mtu, parsedPeers, reserved ?: peers.firstNotNullOfOrNull { it["reserved"] }?.let(::parseReserved))
    }

    fun parseReserved(v: String): List<Int>? {
        val nums = v.split(',').map { it.trim() }.mapNotNull { it.toIntOrNull() }
        if (nums.size == 3 && nums.all { it in 0..255 }) return nums
        return runCatching { Base64.decode(v.trim(), Base64.DEFAULT).map { it.toInt() and 0xFF } }.getOrNull()?.takeIf { it.size == 3 }
    }

    private val resolved = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * "host:port" with the host resolved by the phone's normal DNS. Xray would resolve it with its own
     * DNS module, whose queries go *through the proxy*: for a WireGuard endpoint that is circular
     * (the tunnel needs the address to come up), so configs with a host name never connected.
     * IP endpoints are returned unchanged; on failure the original is kept. Blocking (network).
     */
    fun resolveEndpoint(endpoint: String): String {
        val bracketed = endpoint.startsWith("[")
        val host = if (bracketed) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':')
        if (host.isEmpty() || bracketed || host.all { it.isDigit() || it == '.' }) return endpoint
        resolved[host]?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000L }?.let { return "${it.first}:$port" }
        return runCatching {
            val addrs = InetAddress.getAllByName(host)
            val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
            val text = if (ip is Inet6Address) "[${ip.hostAddress}]" else ip.hostAddress!!
            resolved[host] = text to System.currentTimeMillis()
            "$text:$port"
        }.getOrDefault(endpoint)
    }
}
