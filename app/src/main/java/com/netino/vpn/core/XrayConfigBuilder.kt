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

    /**
     * @param certPin SHA-256 of the server certificate for configs with allowInsecure (see [TlsPin])
     * @param probe extra local SOCKS inbound used to test real throughput while connected
     */
    fun build(
        server: Server, s: AppSettings, inbound: Inbound, hasGeoFiles: Boolean = false,
        certPin: String? = null, probe: Inbound.Socks? = null,
    ): String = buildTunnel(listOf(server), s, inbound, hasGeoFiles, certPin?.let { mapOf(server.id to it) }.orEmpty(), probe)

    /** Tag of the balancer used in auto mode. */
    private const val AUTO = "auto"

    /**
     * Full config for the tunnel. With one server it is a plain proxy. With several ([pool] size > 1) it is
     * **auto mode**: every server is an outbound, Xray's burstObservatory measures them in the background
     * and the leastLoad balancer sends each new connection to the best one (lowest stable delay), so a
     * dying server is avoided within seconds without reconnecting the VPN.
     */
    fun buildTunnel(
        pool: List<Server>, s: AppSettings, inbound: Inbound, hasGeoFiles: Boolean = false,
        pins: Map<String, String> = emptyMap(), probe: Inbound.Socks? = null,
    ): String {
        require(pool.isNotEmpty())
        val auto = pool.size > 1
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
                if (probe != null && inbound != Inbound.None) addJsonObject {
                    put("tag", "probe")
                    put("listen", "127.0.0.1")
                    put("port", probe.port)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("auth", "password")
                        putJsonArray("accounts") { addJsonObject { put("user", probe.user); put("pass", probe.pass) } }
                        put("udp", false)
                        put("userLevel", 8)
                    }
                }
            }
            putJsonArray("outbounds") {
                if (auto) pool.forEachIndexed { i, sv -> add(proxyOutbound(sv, "proxy-$i", s, pins[sv.id])) }
                else add(proxyOutbound(pool[0], "proxy", s, pins[pool[0].id]))
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
                put("rules", rules(s, hasGeoFiles, auto))
                if (auto) putJsonArray("balancers") {
                    addJsonObject {
                        put("tag", AUTO)
                        putJsonArray("selector") { add("proxy-") }
                        putJsonObject("strategy") {
                            put("type", "leastLoad")
                            putJsonObject("settings") {
                                put("expected", 2)          // keep the best 2 in rotation
                                put("maxRTT", "3s")
                                put("tolerance", 0.15)      // ignore servers that fail >15 % of probes
                                putJsonArray("baselines") { add("300ms"); add("800ms"); add("1500ms") }
                            }
                        }
                        put("fallbackTag", "proxy-0")
                    }
                }
            }
            if (auto) putJsonObject("burstObservatory") {
                putJsonArray("subjectSelector") { add("proxy-") }
                putJsonObject("pingConfig") {
                    put("destination", s.testUrl)
                    put("interval", "1m")
                    put("sampling", 3)
                    put("timeout", "5s")
                }
            }
        }
        return root.toString()
    }

    /**
     * One core, many servers: server i is reachable through the authenticated local SOCKS inbound [ports] i.
     * Used to test a whole list in parallel instead of starting a core per server.
     */
    fun buildBatch(servers: List<Server>, ports: List<Inbound.Socks>, s: AppSettings, pins: Map<String, String>): String = buildJsonObject {
        putJsonObject("log") { put("loglevel", "none"); put("access", "none") }
        putJsonArray("inbounds") {
            ports.forEachIndexed { i, ep ->
                addJsonObject {
                    put("tag", "t$i"); put("listen", "127.0.0.1"); put("port", ep.port); put("protocol", "socks")
                    putJsonObject("settings") {
                        put("auth", "password")
                        putJsonArray("accounts") { addJsonObject { put("user", ep.user); put("pass", ep.pass) } }
                        put("udp", false)
                    }
                }
            }
        }
        putJsonArray("outbounds") { servers.forEachIndexed { i, sv -> add(proxyOutbound(sv, "o$i", s.copy(mux = false), pins[sv.id])) } }
        putJsonObject("routing") {
            putJsonArray("rules") { servers.indices.forEach { i -> addJsonObject { putJsonArray("inboundTag") { add("t$i") }; put("outboundTag", "o$i") } } }
        }
    }.toString()

    /** Rule target: the single proxy, or the balancer in auto mode. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.toProxy(auto: Boolean) =
        if (auto) put("balancerTag", AUTO) else put("outboundTag", "proxy")

    private fun rules(s: AppSettings, geo: Boolean, auto: Boolean) = buildJsonArray {
        // 0. Speed probe always measures the proxy itself, whatever the split-tunnel rules say
        addJsonObject { putJsonArray("inboundTag") { add("probe") }; toProxy(auto) }
        // 1. DNS from apps -> Xray DNS module; DNS module's own queries -> proxy (DoH inside the tunnel)
        addJsonObject { putJsonArray("inboundTag") { add(IN_TAG) }; put("port", "53"); put("outboundTag", "dns-out") }
        addJsonObject { putJsonArray("inboundTag") { add("dns-module") }; toProxy(auto) }
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
                if (domains.isNotEmpty()) addJsonObject { put("domain", JsonArray(domains.map(::JsonPrimitive))); toProxy(auto) }
                if (ips.isNotEmpty()) addJsonObject { put("ip", JsonArray(ips.map(::JsonPrimitive))); toProxy(auto) }
                addJsonObject { put("network", "tcp,udp"); put("outboundTag", "direct") }
            }
            SplitMode.OFF -> Unit
        }
        // Everything else: the proxy (first outbound by default; the balancer must be named explicitly)
        if (auto && sp.routeMode != SplitMode.ONLY) addJsonObject { put("network", "tcp,udp"); toProxy(true) }
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
    private fun proxyOutbound(server: Server, tag: String, s: AppSettings, certPin: String?): JsonObject = buildJsonObject {
        put("tag", tag)
        val x = server.xray ?: XrayOutbound()
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
            Protocol.WIREGUARD -> {
                // Xray's userspace WireGuard (gVisor); no kernel TUN on Android
                val c = WireGuardCore.parse(server.wgConf.orEmpty()) ?: error("invalid WireGuard config")
                put("protocol", "wireguard")
                putJsonObject("settings") {
                    put("secretKey", c.privateKey)
                    putJsonArray("address") { c.addresses.forEach { add(it) } }
                    putJsonArray("peers") {
                        c.peers.forEach { p ->
                            addJsonObject {
                                put("publicKey", p.publicKey)
                                p.presharedKey?.let { put("preSharedKey", it) }
                                put("endpoint", p.endpoint)
                                // keeps the NAT mapping of mobile carriers open
                                put("keepAlive", p.keepalive ?: 25)
                                putJsonArray("allowedIPs") { add("0.0.0.0/0"); add("::/0") }
                            }
                        }
                    }
                    // Oversized packets are silently dropped on many mobile networks: default 1280
                    put("mtu", if (s.wgMtu > 0) s.wgMtu else c.mtu ?: 1280)
                    put("noKernelTun", true)
                }
                return@buildJsonObject
            }
        }
        put("streamSettings", stream(server, x, certPin))
        if (s.mux && x.flow.isBlank() && server.protocol != Protocol.HYSTERIA2) {
            putJsonObject("mux") { put("enabled", true); put("concurrency", 8) }
        }
    }

    private fun stream(server: Server, x0: XrayOutbound, certPin: String?): JsonObject = buildJsonObject {
        // CDN configs often set only one of host / sni; the CDN needs the domain in both the HTTP Host
        // header and the TLS SNI, otherwise the server's IP ends up there and the request is rejected.
        val x = x0.copy(host = x0.host.ifBlank { x0.sni.takeIf { !isIp(it) }.orEmpty() })
        if (server.protocol == Protocol.HYSTERIA2) {
            put("network", "hysteria")
            putJsonObject("hysteriaSettings") { put("version", 2); put("auth", x.uuid) }
            put("security", "tls")
            putJsonObject("tlsSettings") { tls(server, x, certPin, defaultAlpn = "h3") }
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
            "tls" -> { put("security", "tls"); putJsonObject("tlsSettings") { tls(server, x, certPin) } }
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

    private fun isIp(s: String) = s.isNotEmpty() && (s.contains(':') || s.all { it.isDigit() || it == '.' })

    private fun kotlinx.serialization.json.JsonObjectBuilder.tls(server: Server, x: XrayOutbound, certPin: String?, defaultAlpn: String = "") {
        put("serverName", x.sni.ifBlank { x.host.split(',').first().trim().ifBlank { server.address } })
        // Xray 26 rejects the whole config if "allowInsecure" is present; pinning replaces it
        if (certPin != null) put("pinnedPeerCertSha256", certPin)
        // Mimic a real browser TLS ClientHello (uTLS) – harder to fingerprint / block.
        put("fingerprint", x.fingerprint.ifBlank { "chrome" })
        val alpn = x.alpn.ifBlank { defaultAlpn }
        if (alpn.isNotBlank()) putJsonArray("alpn") { alpn.split(',').forEach { add(it.trim()) } }
    }
}
