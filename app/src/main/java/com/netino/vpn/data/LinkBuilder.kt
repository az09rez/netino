package com.netino.vpn.data

import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** Standard share link for a server that was imported from JSON (so copy / share / QR keep working). */
object LinkBuilder {

    fun build(s: Server): String {
        val x = s.xray ?: return ""
        val name = enc(s.name)
        val host = if (s.address.contains(':')) "[${s.address}]" else s.address
        return when (s.protocol) {
            Protocol.VMESS -> "vmess://" + Base64.encodeToString(buildJsonObject {
                put("v", "2"); put("ps", s.name); put("add", s.address); put("port", s.port.toString()); put("id", x.uuid)
                put("aid", x.alterId.toString()); put("scy", x.method.ifBlank { "auto" }); put("net", x.network)
                put("type", x.headerType.ifBlank { "none" }); put("host", x.host)
                put("path", if (x.network == "grpc") x.serviceName else x.path)
                put("tls", x.security); put("sni", x.sni); put("alpn", x.alpn); put("fp", x.fingerprint)
            }.toString().toByteArray(), Base64.NO_WRAP)
            Protocol.SHADOWSOCKS ->
                "ss://" + Base64.encodeToString("${x.method}:${x.uuid}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) +
                    "@$host:${s.port}#$name"
            Protocol.SOCKS -> {
                val auth = if (x.user.isNotBlank()) Base64.encodeToString("${x.user}:${x.uuid}".toByteArray(), Base64.NO_WRAP) + "@" else ""
                "socks://$auth$host:${s.port}#$name"
            }
            Protocol.WIREGUARD -> ""
            else -> {
                val scheme = when (s.protocol) { Protocol.TROJAN -> "trojan"; Protocol.HYSTERIA2 -> "hysteria2"; else -> "vless" }
                val q = linkedMapOf(
                    "type" to x.network, "security" to x.security.ifBlank { "none" }, "encryption" to x.encryption.takeIf { s.protocol == Protocol.VLESS },
                    "flow" to x.flow, "sni" to x.sni, "alpn" to x.alpn, "fp" to x.fingerprint, "pbk" to x.publicKey, "sid" to x.shortId,
                    "spx" to x.spiderX, "host" to x.host, "path" to x.path, "headerType" to x.headerType, "serviceName" to x.serviceName,
                    "mode" to x.mode, "allowInsecure" to (if (x.allowInsecure) "1" else null), "obfs" to x.obfs, "obfs-password" to x.obfsPassword,
                ).filterValues { !it.isNullOrBlank() }.entries.joinToString("&") { "${it.key}=${enc(it.value!!)}" }
                "$scheme://${enc(x.uuid)}@$host:${s.port}?$q#$name"
            }
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
