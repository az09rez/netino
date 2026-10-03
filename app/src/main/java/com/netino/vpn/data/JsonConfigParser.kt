package com.netino.vpn.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON configs as exported by v2rayNG / panels / sing-box:
 *  • a full Xray config (`outbounds`, optional `remarks`) or an array of them (JSON subscriptions)
 *  • a single Xray outbound (`protocol` + `settings`)
 *  • sing-box configs / outbounds (`type` + `server`)
 * Every proxy outbound becomes a server; a share link is generated so copy / share keep working.
 */
object JsonConfigParser {

    fun looksLikeJson(text: String) = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    fun parse(text: String, subscriptionId: String? = null): List<Server> {
        val root = runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull() ?: return emptyList()
        val items = (root as? JsonArray)?.toList() ?: listOf(root)
        return items.flatMap { el -> runCatching { fromElement(el.jsonObject) }.getOrDefault(emptyList()) }
            .map { it.copy(subscriptionId = subscriptionId) }
    }

    private fun fromElement(o: JsonObject): List<Server> {
        val remarks = o.str("remarks") ?: o.str("ps")
        val outbounds = o["outbounds"] as? JsonArray
        val list = when {
            outbounds != null -> outbounds.mapNotNull { (it as? JsonObject)?.let(::outbound) }
            o.containsKey("protocol") -> listOfNotNull(xray(o))
            o.containsKey("type") -> listOfNotNull(singBox(o))
            else -> emptyList()
        }
        // A full config with a remark usually has one proxy: use the remark as its name
        return if (remarks != null && list.size == 1) listOf(list[0].copy(name = remarks)) else list
    }

    private fun outbound(o: JsonObject): Server? = if (o.containsKey("protocol")) xray(o) else singBox(o)

    // ---------------------------------------------------------------- Xray

    private fun xray(o: JsonObject): Server? {
        val protocol = when (o.str("protocol")?.lowercase()) {
            "vless" -> Protocol.VLESS; "vmess" -> Protocol.VMESS; "trojan" -> Protocol.TROJAN
            "shadowsocks" -> Protocol.SHADOWSOCKS; "socks" -> Protocol.SOCKS
            "hysteria", "hysteria2" -> Protocol.HYSTERIA2
            else -> return null   // freedom, blackhole, dns ...
        }
        val st = o.obj("settings") ?: JsonObject(emptyMap())
        // Old layout: settings.vnext[0] / settings.servers[0]; new layout: fields directly in settings
        val node = st.arr("vnext")?.firstObj() ?: st.arr("servers")?.firstObj() ?: st
        val user = node.arr("users")?.firstObj() ?: node
        val address = node.str("address") ?: return null
        val port = node.int("port") ?: return null
        val stream = o.obj("streamSettings") ?: JsonObject(emptyMap())
        val net = stream.str("network") ?: "tcp"
        val security = stream.str("security")?.takeIf { it != "none" } ?: ""
        val tls = stream.obj("tlsSettings")
        val reality = stream.obj("realitySettings")
        val transport = stream.obj("${if (net == "h2") "http" else if (net == "splithttp") "xhttp" else net}Settings")
            ?: stream.obj("wsSettings") ?: JsonObject(emptyMap())
        val tcpHeader = stream.obj("tcpSettings")?.obj("header") ?: stream.obj("rawSettings")?.obj("header")
        val host = transport.str("host") ?: transport.arr("host")?.joinToString(",") { it.jsonPrimitive.content }
            ?: transport.obj("headers")?.str("Host")
            ?: tcpHeader?.obj("request")?.obj("headers")?.arr("Host")?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val path = transport.str("path") ?: tcpHeader?.obj("request")?.arr("path")?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: transport.str("seed")
        val x = XrayOutbound(
            uuid = user.str("id") ?: user.str("password") ?: node.str("password")
                ?: stream.obj("hysteriaSettings")?.str("auth") ?: user.str("pass").orEmpty(),
            alterId = user.int("alterId") ?: 0,
            method = user.str("security") ?: node.str("method").orEmpty(),
            flow = user.str("flow").orEmpty(),
            encryption = user.str("encryption") ?: "none",
            network = net,
            security = security,
            sni = tls?.str("serverName") ?: reality?.str("serverName").orEmpty(),
            alpn = tls?.arr("alpn")?.joinToString(",") { it.jsonPrimitive.content }.orEmpty(),
            fingerprint = tls?.str("fingerprint") ?: reality?.str("fingerprint").orEmpty(),
            publicKey = reality?.str("publicKey") ?: reality?.str("password").orEmpty(),
            shortId = reality?.str("shortId").orEmpty(),
            spiderX = reality?.str("spiderX").orEmpty(),
            host = host.orEmpty(),
            path = path.orEmpty(),
            headerType = tcpHeader?.str("type") ?: transport.obj("header")?.str("type").orEmpty(),
            serviceName = transport.str("serviceName").orEmpty(),
            mode = transport.str("mode") ?: if (transport["multiMode"]?.jsonPrimitive?.booleanOrNull == true) "multi" else "",
            allowInsecure = tls?.bool("allowInsecure") == true || tls?.str("pinnedPeerCertSha256") != null,
            user = user.str("user").orEmpty(),
        )
        return finish(o.str("tag").orEmpty(), protocol, address, port, x)
    }

    // ---------------------------------------------------------------- sing-box

    private fun singBox(o: JsonObject): Server? {
        val type = o.str("type")?.lowercase() ?: return null
        if (type == "wireguard") return singBoxWireGuard(o)
        val protocol = when (type) {
            "vless" -> Protocol.VLESS; "vmess" -> Protocol.VMESS; "trojan" -> Protocol.TROJAN
            "shadowsocks" -> Protocol.SHADOWSOCKS; "socks" -> Protocol.SOCKS; "hysteria2" -> Protocol.HYSTERIA2
            else -> return null
        }
        val address = o.str("server") ?: return null
        val port = o.int("server_port") ?: return null
        val tls = o.obj("tls")?.takeIf { it.bool("enabled") == true }
        val reality = tls?.obj("reality")?.takeIf { it.bool("enabled") == true }
        val tr = o.obj("transport")
        val trType = tr?.str("type")
        val x = XrayOutbound(
            uuid = o.str("uuid") ?: o.str("password").orEmpty(),
            alterId = o.int("alter_id") ?: 0,
            method = o.str("security") ?: o.str("method").orEmpty(),
            flow = o.str("flow").orEmpty(),
            network = when (trType) { null -> "tcp"; "http" -> "h2"; else -> trType },
            security = when { reality != null -> "reality"; tls != null -> "tls"; else -> "" },
            sni = tls?.str("server_name").orEmpty(),
            alpn = tls?.arr("alpn")?.joinToString(",") { it.jsonPrimitive.content }.orEmpty(),
            fingerprint = tls?.obj("utls")?.str("fingerprint").orEmpty(),
            publicKey = reality?.str("public_key").orEmpty(),
            shortId = reality?.str("short_id").orEmpty(),
            host = tr?.obj("headers")?.str("Host") ?: tr?.str("host") ?: tr?.arr("host")?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty(),
            path = tr?.str("path").orEmpty(),
            serviceName = tr?.str("service_name").orEmpty(),
            allowInsecure = tls?.bool("insecure") == true,
            obfs = o.obj("obfs")?.str("type").orEmpty(),
            obfsPassword = o.obj("obfs")?.str("password").orEmpty(),
            user = o.str("username").orEmpty(),
        )
        return finish(o.str("tag").orEmpty(), protocol, address, port, x)
    }

    private fun singBoxWireGuard(o: JsonObject): Server? {
        val address = o.str("server") ?: return null
        val port = o.int("server_port") ?: return null
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${o.str("private_key") ?: return null}")
            appendLine("Address = ${o.arr("local_address")?.joinToString(", ") { it.jsonPrimitive.content } ?: "172.16.0.2/32"}")
            appendLine("DNS = 1.1.1.1, 1.0.0.1")
            o.int("mtu")?.let { appendLine("MTU = $it") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${o.str("peer_public_key") ?: return null}")
            o.str("pre_shared_key")?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${if (address.contains(':')) "[$address]" else address}:$port")
        }
        return LinkParser.parseWireGuardConf(conf, o.str("tag").orEmpty())
    }

    // ---------------------------------------------------------------- helpers

    private fun finish(tag: String, protocol: Protocol, address: String, port: Int, x: XrayOutbound): Server? {
        if (port !in 1..65535 || address.isBlank()) return null
        if (protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN) && x.uuid.isBlank()) return null
        val name = tag.takeIf { it.isNotBlank() && it != "proxy" } ?: "$address:$port"
        val s = Server(name = name, protocol = protocol, address = address, port = port, xray = x)
        return s.copy(link = LinkBuilder.build(s))
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
    private fun JsonArray.firstObj(): JsonObject? = firstOrNull() as? JsonObject
}
