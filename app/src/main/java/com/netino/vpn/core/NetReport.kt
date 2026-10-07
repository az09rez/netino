package com.netino.vpn.core

import com.netino.vpn.data.NetKey
import com.netino.vpn.data.Repository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened on each network (Wi-Fi, each operator): firewall detection, WARP registration, endpoint
 * scans, WARP in WARP and its MTU, the tunnel's own check. Kept per network, so one copied report compares
 * MCI, Irancell and Wi-Fi side by side. Holds no keys or config links.
 */
object NetReport {

    private const val KEY = "netreport"
    private const val PER_NET = 40
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** Adds a line for the current network. */
    fun add(text: String) = synchronized(lock) {
        val all = load().toMutableMap()
        val net = NetKey.current
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        all[net] = ((all[net] ?: emptyList()) + "$stamp  $text").takeLast(PER_NET)
        runCatching { Repository.writeExtra(KEY, json.encodeToString<Map<String, List<String>>>(all)) }
    }

    fun clear() = synchronized(lock) { runCatching { Repository.writeExtra(KEY, "{}") } }

    private fun load(): Map<String, List<String>> =
        runCatching { Repository.readExtra(KEY)?.let { json.decodeFromString<Map<String, List<String>>>(it) } }.getOrNull().orEmpty()

    /**
     * The whole report: [header] (device, connection), then the current network's state and events,
     * then every other network's events.
     */
    fun text(header: List<String>): String = buildString {
        val s = Repository.settings.value
        val net = NetKey.current
        val all = load()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        header.forEach { appendLine(it) }
        appendLine()
        appendLine("== Network: $net (current)")
        appendLine("Firewall method: " + (s.cdnForced?.let { "${it.name} (forced)" }
            ?: s.cdnByNet[net]?.let { m -> "${m.name}, checked ${s.cdnCheckedAt[net]?.let { fmt.format(Date(it)) } ?: "-"}" } ?: "not detected yet"))
        appendLine("Remembered WARP endpoints: " + (s.warpEndpointsByNet[net]?.joinToString().takeUnless { it.isNullOrEmpty() } ?: "none"))
        val warp = Repository.servers.value.filter { it.subscriptionId == Warp.SUB }
        appendLine("Saved WARP servers: ${warp.size} (WARP in WARP ${warp.count { it.wgOuter != null }}), accounts ${warp.map(Warp::accountKey).distinct().size}")
        appendLine("WARP+ license: " + if (s.warpLicense.isBlank()) "none" else "set")
        all[net].orEmpty().forEach { appendLine("  $it") }
        for ((n, lines) in all) if (n != net && lines.isNotEmpty()) {
            appendLine()
            appendLine("== Network: $n")
            appendLine("Firewall method: " + (s.cdnByNet[n]?.name ?: "not detected"))
            appendLine("Remembered WARP endpoints: " + (s.warpEndpointsByNet[n]?.joinToString().takeUnless { it.isNullOrEmpty() } ?: "none"))
            lines.forEach { appendLine("  $it") }
        }
    }.trimEnd()
}
