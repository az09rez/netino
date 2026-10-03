package com.netino.vpn.core

import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Server
import com.netino.vpn.data.SplitMode
import com.netino.vpn.data.XrayOutbound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a full Xray JSON config:
 *   tun inbound (fd supplied by VpnService) -> routing (split tunnel, LAN, ads, DNS) -> proxy / direct / block.
 * DNS is answered by Xray's DNS module using DNS-over-HTTPS *through the proxy*, so the ISP never sees queries.
 */
object XrayConfigBuilder {

    val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
    )

    /** Where traffic enters the core. */
    sealed interface Inbound {
        /** Delay tests: libv2ray strips inbounds/DNS/routing and only keeps the outbound. */
        data object None : Inbound
        /** Xray's own TUN inbound reading the VpnService fd (xray.tun.fd). */
        data object Tun : Inbound
        /** Local SOCKS5 for hev-socks5-tunnel; random per-session credentials so no other app can use it. */
        data class Socks(val port: Int, val user: String, val pass: String) : Inbound
    }

    const val IN_TAG = "in"

    fun build(server: Server, s: AppSettings, inbound: Inbound, hasGeoFiles: Boolean = false): String {
        val x = requireNotNull(server.xray) { "not an Xray server" }
        val root = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning"); put("access", "none") }  // no access log = no browsing history
            putJsonObject("policy") {
                putJsonObject("levels") { putJsonObject("8") { put("handshake", 4); put("connIdle", 300); put("uplinkOnly", 1); put("downlinkOnly", 1) } }
                putJsonObject("system") { put("statsOutboundUplink", true); put("statsOutboundDownlink", true) }
            }
            putJsonObject("stats") {}
            putJsonArray("inbounds") {
                if (inbound != Inbound.None) addJsonObject {
                    put("tag", IN_TAG)
                    when (inbound) {
                        is Inbound.Socks -> {
                            put("listen", "127.0.0.1")
                            put("port", inbound.port)
                            put("protocol", "socks")
                            putJsonObject("settings") {
                                put("auth", "password")
                                putJsonArray("accounts") { addJsonObject { put("user", inbound.user); put("pass", inbound.pass) } }
                                put("udp", true)
                                put("userLevel", 8)
                            }
                        }
                        else -> {
                            put("protocol", "tun")
                            putJsonObject("settings") { put("name", "xray0"); put("MTU", 1500); put("userLevel", 8) }
                        }
                    }
                    putJsonObject("sniffing") {
                        put("enabled", s.sniffing)
                        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                        put("routeOnly", true)
                    }
                }
            }
            putJsonArray("outbounds") {
                add(proxyOutbound(server, x, s))
                addJsonObject { put("tag", "direct"); put("protocol", "freedom"); putJsonObject("settings") { put("domainStrategy", "UseIP") } }
                addJsonObject { put("tag", "block"); put("protocol", "blackhole") }
                addJsonObject { put("tag", "dns-out"); put("protocol", "dns") }
            }
            putJsonObject("dns") {
                put("tag", "dns-module")
                put("queryStrategy", if (s.blockIpv6Leak) "UseIPv4" else "UseIP")
                putJsonArray("servers") { add(s.dns); add("1.1.1.1") }
            }
            putJsonObject("routing") {
                // AsIs: domains go to the server unresolved (like NPV / v2rayNG defaults) — no dependency on DNS
                // for every connection. IP rules for sniffed domains are only needed when the user split-tunnels by IP.
                val ipSplit = s.split.routeMode != SplitMode.OFF && s.split.ips.isNotEmpty()
                put("domainStrategy", if (ipSplit) "IPIfNonMatch" else "AsIs")
                put("rules", rules(s, hasGeoFiles))
            }
        }
        return root.toString()
    }

    private fun rules(s: AppSettings, geo: Boolean) = buildJsonArray {
        // 1. DNS from apps -> Xray DNS module; DNS module's own queries -> proxy (DoH inside the tunnel)
        addJsonObject { putJsonArray("inboundTag") { add(IN_TAG) }; put("port", "53"); put("outboundTag", "dns-out") }
        addJsonObject { putJsonArray("inboundTag") { add("dns-module") }; put("outboundTag", "proxy") }
        // 2. Ads / trackers
        if (s.blockAds && geo) addJsonObject { putJsonArray("domain") { add("geosite:category-ads-all") }; put("outboundTag", "block") }
        // 3. LAN
        if (s.bypassLan) addJsonObject { putJsonArray("ip") { PRIVATE_CIDRS.forEach { add(it) } }; put("outboundTag", "direct") }
        // 4. User split tunnel (domains / IPs)
        val sp = s.split
        val domains = sp.domains.mapNotNull { normalizeDomain(it, geo) }
        val ips = sp.ips.mapNotNull { normalizeIp(it, geo) }
        when (sp.routeMode) {
            SplitMode.BYPASS -> {
                if (domains.isNotEmpty()) addJsonObject { put("domain", JsonArray(domains.map(::JsonPrimitive))); put("outboundTag", "direct") }
                if (ips.isNotEmpty()) addJsonObject { put("ip", JsonArray(ips.map(::JsonPrimitive))); put("outboundTag", "direct") }
            }
            SplitMode.ONLY -> {
                if (domains.isNotEmpty()) addJsonObject { put("domain", JsonArray(domains.map(::JsonPrimitive))); put("outboundTag", "proxy") }
                if (ips.isNotEmpty()) addJsonObject { put("ip", JsonArray(ips.map(::JsonPrimitive))); put("outboundTag", "proxy") }
                addJsonObject { put("network", "tcp,udp"); put("outboundTag", "direct") }
            }
            SplitMode.OFF -> Unit
        }
        // Default (first outbound) is proxy.
    }

    /** example.com -> domain:example.com, *.ir / .ir -> regexp, prefixed forms pass through. */
    fun normalizeDomain(raw: String, geo: Boolean): String? {
        val d = raw.trim().lowercase().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (d.isEmpty()) return null
        return when {
            d.startsWith("geosite:") -> if (geo) d else null
            d.startsWith("domain:") || d.startsWith("full:") || d.startsWith("regexp:") || d.startsWith("keyword:") -> d
            d.startsWith("*.") -> "domain:" + d.removePrefix("*.")
            d.startsWith(".") -> "domain:" + d.removePrefix(".")
            else -> "domain:$d"
        }
    }

    fun normalizeIp(raw: String, geo: Boolean): String? {
        val ip = raw.trim()
        if (ip.isEmpty()) return null
        if (ip.startsWith("geoip:")) return if (geo) ip.lowercase() else null
        return if (Regex("""^[0-9a-fA-F:.]+(/\d{1,3})?$""").matches(ip)) ip else null
    }

    // ---------------- outbound ----------------
    private fun proxyOutbound(server: Server, x: XrayOutbound, s: AppSettings): JsonObject = buildJsonObject {
        put("tag", "proxy")
        when (server.protocol) {
            Protocol.VMESS -> {
                put("protocol", "vmess")
                putJsonObject("settings") { putJsonArray("vnext") { addJsonObject {
                    put("address", server.address); put("port", server.port)
                    putJsonArray("users") { addJsonObject { put("id", x.uuid); put("alterId", x.alterId); put("security", x.method.ifBlank { "auto" }); put("level", 8) } }
                } } }
            }
            Protocol.VLESS -> {
                put("protocol", "vless")
                putJsonObject("settings") { putJsonArray("vnext") { addJsonObject {
                    put("address", server.address); put("port", server.port)
                    putJsonArray("users") { addJsonObject {
                        put("id", x.uuid); put("encryption", x.encryption.ifBlank { "none" }); put("level", 8)
                        if (x.flow.isNotBlank()) put("flow", x.flow)
                    } }
                } } }
            }
            Protocol.TROJAN -> {
                put("protocol", "trojan")
                putJsonObject("settings") { putJsonArray("servers") { addJsonObject {
                    put("address", server.address); put("port", server.port); put("password", x.uuid); put("level", 8)
                    if (x.flow.isNotBlank()) put("flow", x.flow)
                } } }
            }
            Protocol.SHADOWSOCKS -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") { putJsonArray("servers") { addJsonObject {
                    put("address", server.address); put("port", server.port); put("method", x.method); put("password", x.uuid); put("level", 8)
                } } }
            }
            Protocol.SOCKS -> {
                put("protocol", "socks")
                putJsonObject("settings") { putJsonArray("servers") { addJsonObject {
                    put("address", server.address); put("port", server.port)
                    if (x.user.isNotBlank()) putJsonArray("users") { addJsonObject { put("user", x.user); put("pass", x.uuid) } }
                } } }
            }
            Protocol.HYSTERIA2 -> {
                // Requires an Xray core with the hysteria outbound (Xray-core 25.x+ builds of libv2ray).
                put("protocol", "hysteria")
                putJsonObject("settings") { put("version", 2); put("address", server.address); put("port", server.port) }
            }
            Protocol.WIREGUARD -> error("WireGuard is handled by the WireGuard backend")
        }
        put("streamSettings", stream(server, x))
        if (s.mux && x.flow.isBlank() && server.protocol != Protocol.HYSTERIA2) {
            putJsonObject("mux") { put("enabled", true); put("concurrency", 8) }
        }
    }

    private fun stream(server: Server, x: XrayOutbound): JsonObject = buildJsonObject {
        if (server.protocol == Protocol.HYSTERIA2) {
            put("network", "hysteria")
            putJsonObject("hysteriaSettings") { put("version", 2); put("auth", x.uuid) }
            put("security", "tls")
            putJsonObject("tlsSettings") { tls(server, x, defaultAlpn = "h3") }
            return@buildJsonObject
        }
        val net = x.network.ifBlank { "tcp" }.let { if (it == "http") "h2" else it }
        put("network", net)
        when (net) {
            "ws" -> putJsonObject("wsSettings") { put("path", x.path.ifBlank { "/" }); if (x.host.isNotBlank()) put("host", x.host) }
            "grpc" -> putJsonObject("grpcSettings") { put("serviceName", x.serviceName.ifBlank { x.path }); put("multiMode", x.mode == "multi") }
            "httpupgrade" -> putJsonObject("httpupgradeSettings") { put("path", x.path.ifBlank { "/" }); if (x.host.isNotBlank()) put("host", x.host) }
            "xhttp", "splithttp" -> putJsonObject("xhttpSettings") {
                put("path", x.path.ifBlank { "/" }); if (x.host.isNotBlank()) put("host", x.host); put("mode", x.mode.ifBlank { "auto" })
            }
            "h2" -> putJsonObject("httpSettings") { put("path", x.path.ifBlank { "/" }); if (x.host.isNotBlank()) putJsonArray("host") { x.host.split(',').forEach { add(it.trim()) } } }
            "kcp" -> putJsonObject("kcpSettings") { putJsonObject("header") { put("type", x.headerType.ifBlank { "none" }) }; if (x.path.isNotBlank()) put("seed", x.path) }
            "tcp", "raw" -> if (x.headerType == "http") putJsonObject("tcpSettings") { putJsonObject("header") {
                put("type", "http")
                putJsonObject("request") {
                    putJsonArray("path") { add(x.path.ifBlank { "/" }) }
                    putJsonObject("headers") { putJsonArray("Host") { add(x.host.ifBlank { server.address }) } }
                }
            } }
        }
        when (x.security) {
            "tls" -> { put("security", "tls"); putJsonObject("tlsSettings") { tls(server, x) } }
            "reality" -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", x.sni)
                    put("fingerprint", x.fingerprint.ifBlank { "chrome" })
                    put("publicKey", x.publicKey)
                    put("shortId", x.shortId)
                    put("spiderX", x.spiderX)
                }
            }
            else -> put("security", "none")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.tls(server: Server, x: XrayOutbound, defaultAlpn: String = "") {
        put("serverName", x.sni.ifBlank { x.host.ifBlank { server.address } })
        put("allowInsecure", x.allowInsecure)
        // Mimic a real browser TLS ClientHello (uTLS) – harder to fingerprint / block.
        put("fingerprint", x.fingerprint.ifBlank { "chrome" })
        val alpn = x.alpn.ifBlank { defaultAlpn }
        if (alpn.isNotBlank()) putJsonArray("alpn") { alpn.split(',').forEach { add(it.trim()) } }
    }
}
