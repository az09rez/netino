package com.netino.vpn.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class Protocol(val label: String) {
    VMESS("VMess"), VLESS("VLESS"), TROJAN("Trojan"), SHADOWSOCKS("Shadowsocks"),
    HYSTERIA2("Hysteria2"), SOCKS("SOCKS"), WIREGUARD("WireGuard");

    val usesXray get() = this != WIREGUARD
}

/**
 * One server. For Xray protocols the original share link is kept in [link] and parsed into
 * the [xray] block; for WireGuard the full wg-quick text lives in [wgConf].
 */
@Serializable
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val link: String = "",
    val xray: XrayOutbound? = null,
    val wgConf: String? = null,
    val subscriptionId: String? = null,
    val lastPingMs: Long = -1,     // -1 = unknown, -2 = failed
    val pingKind: PingKind = PingKind.NONE,
) {
    /**
     * Traffic to the server isn't protected by verified TLS: plaintext VLESS / Trojan / SOCKS / VMess-"none",
     * or TLS whose certificate isn't checked (allowInsecure). Such servers still connect but are labelled.
     */
    val isInsecure: Boolean get() {
        val x = xray ?: return false
        if (x.security == "tls" && x.allowInsecure) return true
        if (x.security.isNotBlank()) return false
        return when (protocol) {
            Protocol.VLESS, Protocol.TROJAN, Protocol.SOCKS -> true
            Protocol.VMESS -> x.method == "none" || x.method == "zero"
            else -> false
        }
    }
}

/** User-defined collection of servers (from any source). Servers stay in their subscription too. */
@Serializable
data class ServerGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverIds: List<String> = emptyList(),
)

/** How [Server.lastPingMs] was measured, shown next to the number. */
enum class PingKind { NONE, TCP, ICMP, REAL }

/** Normalised share-link fields, enough to build an Xray outbound. */
@Serializable
data class XrayOutbound(
    val uuid: String = "",            // vmess/vless id, trojan/hy2 password, ss password
    val alterId: Int = 0,
    val method: String = "",          // ss cipher or vmess security
    val flow: String = "",            // e.g. xtls-rprx-vision
    val encryption: String = "none",
    val network: String = "tcp",      // tcp, ws, grpc, h2, httpupgrade, xhttp, kcp, quic
    val security: String = "",        // "", tls, reality
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",       // reality pbk
    val shortId: String = "",         // reality sid
    val spiderX: String = "",
    val host: String = "",
    val path: String = "",
    val headerType: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val allowInsecure: Boolean = false,
    val obfs: String = "",            // hysteria2 obfs
    val obfsPassword: String = "",
    val user: String = "",            // socks
)

@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdated: Long = 0,
    /** Auto-update period in hours (0 = off). */
    val updateHours: Int = 12,
    /** From the panel's `subscription-userinfo` header (bytes / epoch seconds, 0 = unknown). */
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    /** Netino's own always-on list: cannot be deleted. */
    val builtIn: Boolean = false,
) {
    val hasUsage get() = total > 0 || expire > 0
    val used get() = upload + download
    fun isDue(now: Long = System.currentTimeMillis()) = updateHours > 0 && now - lastUpdated >= updateHours * 3_600_000L - 60_000L
}

enum class SplitMode { OFF, BYPASS, ONLY }   // BYPASS: listed items go direct; ONLY: only listed go through VPN
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ServerSort { DEFAULT, PING, NAME }

/** HEV = hev-socks5-tunnel + local SOCKS (v2rayNG default, most compatible); XRAY = Xray's built-in TUN. */
enum class TunEngine { HEV, XRAY }

@Serializable
data class SplitTunnelSettings(
    val appMode: SplitMode = SplitMode.OFF,
    val apps: Set<String> = emptySet(),
    val routeMode: SplitMode = SplitMode.OFF,
    val domains: List<String> = emptyList(),   // example.com, *.ir, geosite:category-ir
    val ips: List<String> = emptyList(),       // 1.2.3.4, 10.0.0.0/8, geoip:ir
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoSwitch: Boolean = true,
    val autoSwitchFailures: Int = 3,           // consecutive failed health checks before switching
    val healthIntervalSec: Int = 15,
    val dns: String = "https://1.1.1.1/dns-query",   // DNS-over-HTTPS through the tunnel
    val blockIpv6Leak: Boolean = true,
    val blockAds: Boolean = false,
    val bypassLan: Boolean = true,
    val sniffing: Boolean = true,
    val mux: Boolean = false,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val tunEngine: TunEngine = TunEngine.HEV,
    val hideInRecents: Boolean = false,
    val split: SplitTunnelSettings = SplitTunnelSettings(),
    val selectedServerId: String? = null,
    val serverSort: ServerSort = ServerSort.PING,
    /** WireGuard MTU: 0 = auto (the config's own value, otherwise 1280 which suits most mobile networks). */
    val wgMtu: Int = 0,
    /** Collapsed sections on the Servers tab ("manual", subscription ids, "g:<group id>"). */
    val collapsed: Set<String> = emptySet(),
    /** Where "connect to fastest" looks: "all", "manual", "sub:<id>" or "group:<id>". */
    val fastestScope: String = "all",
)

@Serializable
data class DailyUsage(val day: String, val rx: Long, val tx: Long)
