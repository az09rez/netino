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
    /** Last real-delay results, newest last (ms, or -1 for a failed test). */
    val history: List<Int> = emptyList(),
    /** The same, per network ("wifi", "cell:<operator>"): the best server on Irancell isn't the best on Wi-Fi. */
    val historyByNet: Map<String, List<Int>> = emptyMap(),
    /** Only connects with TLS fragmentation (learned by the auto fragment test). */
    val fragment: Boolean = false,
    /** Behind Cloudflare: connect to the scanned clean IP instead of [address] (SNI / Host keep the domain). */
    val useCleanIp: Boolean = false,
    /** Test-only: forces a Cloudflare method for this copy (firewall detection). Never stored. */
    @kotlinx.serialization.Transient val cdnOverride: CdnMethod? = null,
) {
    /**
     * Stability score from [history], lower is better: median delay + spread + a penalty for failed
     * tests. A server that answered fast once but fails half the time ranks below a steady one.
     * null when the server has never been really tested.
     */
    val score: Long? get() = scoreOf(historyByNet[NetKey.current]?.takeIf { it.isNotEmpty() } ?: history)

    private fun scoreOf(history: List<Int>): Long? {
        if (history.isEmpty()) return null
        val ok = history.filter { it > 0 }.sorted()
        if (ok.isEmpty()) return Long.MAX_VALUE / 2
        val median = ok[ok.size / 2]
        val spread = (ok.last() - ok.first()) / 2
        val failRatio = (history.size - ok.size).toDouble() / history.size
        return (median + spread + failRatio * 3000).toLong()
    }

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

/** TLS ClientHello fragmentation against SNI filtering: AUTO retries a failing server with it and remembers. */
enum class FragmentMode { OFF, AUTO, ALWAYS }

/**
 * How a Cloudflare CDN / Worker config reaches Cloudflare through Iran's firewalls:
 *  - PLAIN: as the config says (plus the clean IP, if one is set)
 *  - ECH: Encrypted Client Hello, so the real SNI is hidden (works on MCI-type firewalls)
 *  - IPV6: a Cloudflare IPv6 address (MCI-type); IPV6_FF adds the F&F ClientHello mask for filtered domains
 *  - FF: "F&F": empty TLS record before the ClientHello + Python-like cipher suites (Irancell-type)
 */
enum class CdnMethod { PLAIN, ECH, IPV6, IPV6_FF, FF }

/** Which kind of firewall a CDN method points to (SIM operator and firewall can differ). */
fun CdnMethod.firewall(): String? = when (this) {
    CdnMethod.ECH, CdnMethod.IPV6, CdnMethod.IPV6_FF -> "mci"
    CdnMethod.FF -> "irancell"
    CdnMethod.PLAIN -> null
}

/** Current network identity ("wifi", "cell:<operator>", "other"), kept by NetworkMonitor. */
object NetKey {
    @Volatile var current: String = "other"
}

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
    val fragmentMode: FragmentMode = FragmentMode.AUTO,
    /** Random UDP packets before WireGuard's handshake, so DPI doesn't recognise it. */
    val wgNoise: Boolean = true,
    /** Cloudflare IP picked by the clean IP scanner (empty = none). */
    val cleanIp: String = "",
    /** Reconnect when the phone moves between Wi-Fi and mobile data. */
    val reconnectOnNetworkChange: Boolean = true,
    /** Ask for fingerprint / screen lock when the app is opened. */
    val appLock: Boolean = false,
    /** Connect automatically while one of these apps is in the foreground (needs usage access). */
    val autoConnectApps: Set<String> = emptySet(),
    /** null = detect automatically per network; otherwise always this method. */
    val cdnForced: CdnMethod? = null,
    /** Detected method per network ([NetKey]) and when it was detected (epoch ms). */
    val cdnByNet: Map<String, CdnMethod> = emptyMap(),
    val cdnCheckedAt: Map<String, Long> = emptyMap(),
    /** Cloudflare IPv6 picked by the clean IP scanner (empty = default). */
    val cleanIp6: String = "",
) {
    /** CDN method on the current network. */
    val cdnMethod: CdnMethod get() = cdnForced ?: cdnByNet[NetKey.current] ?: CdnMethod.PLAIN
}

@Serializable
data class DailyUsage(val day: String, val rx: Long, val tx: Long)
