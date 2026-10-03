package com.netino.vpn.data

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * Parses share links (vmess / vless / trojan / ss / hysteria2 / socks / wireguard),
 * raw WireGuard .conf text and subscription bodies (plain or base64).
 */
object LinkParser {

    /** Parse anything the user pasted or a subscription returned. Unknown lines are skipped. */
    fun parseMany(input: String, subscriptionId: String? = null): List<Server> {
        val text = input.trim()
        if (text.contains("[Interface]", ignoreCase = true)) {
            return listOfNotNull(parseWireGuardConf(text, subscriptionId = subscriptionId))
        }
        val body = if (!text.contains("://")) decodeBase64(text) ?: text else text
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { parse(it, subscriptionId) }.getOrNull() }
            .toList()
    }

    fun parse(link: String, subscriptionId: String? = null): Server? {
        val scheme = link.substringBefore("://").lowercase()
        val s = when (scheme) {
            "vmess" -> parseVmess(link)
            "vless" -> parseStd(link, Protocol.VLESS)
            "trojan" -> parseStd(link, Protocol.TROJAN)
            "hysteria2", "hy2" -> parseStd(link, Protocol.HYSTERIA2)
            "ss" -> parseShadowsocks(link)
            "socks", "socks5" -> parseSocks(link)
            "wireguard", "wg" -> parseWireGuardLink(link)
            else -> null
        }
        return s?.copy(subscriptionId = subscriptionId)
    }

    // ---------- vmess://base64(json) ----------
    private fun parseVmess(link: String): Server? {
        val json = decodeBase64(link.removePrefix("vmess://")) ?: return null
        val o: JsonObject = Json.parseToJsonElement(json).jsonObject
        fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
        val tls = f("tls")
        return Server(
            name = f("ps").ifBlank { f("add") },
            protocol = Protocol.VMESS,
            address = f("add"),
            port = f("port").toIntOrNull() ?: return null,
            link = link,
            xray = XrayOutbound(
                uuid = f("id"),
                alterId = f("aid").toIntOrNull() ?: 0,
                method = f("scy").ifBlank { "auto" },
                network = f("net").ifBlank { "tcp" },
                headerType = f("type"),
                host = f("host"),
                path = f("path"),
                serviceName = f("path"),
                security = if (tls == "none") "" else tls,
                sni = f("sni"),
                alpn = f("alpn"),
                fingerprint = f("fp"),
            ),
        )
    }

    // ---------- scheme://user@host:port?query#name (vless, trojan, hysteria2) ----------
    private fun parseStd(link: String, protocol: Protocol): Server? {
        val p = SplitLink.of(link) ?: return null
        val q = p.query
        val security = q["security"] ?: if (protocol == Protocol.TROJAN || protocol == Protocol.HYSTERIA2) "tls" else ""
        return Server(
            name = p.name.ifBlank { p.host },
            protocol = protocol,
            address = p.host,
            port = p.port,
            link = link,
            xray = XrayOutbound(
                uuid = p.user,
                encryption = q["encryption"] ?: "none",
                flow = q["flow"].orEmpty(),
                network = q["type"] ?: "tcp",
                security = if (security == "none") "" else security,
                sni = q["sni"] ?: q["peer"].orEmpty(),
                alpn = q["alpn"].orEmpty(),
                fingerprint = q["fp"].orEmpty(),
                publicKey = q["pbk"].orEmpty(),
                shortId = q["sid"].orEmpty(),
                spiderX = q["spx"].orEmpty(),
                host = q["host"].orEmpty(),
                path = q["path"].orEmpty(),
                headerType = q["headerType"].orEmpty(),
                serviceName = q["serviceName"].orEmpty(),
                mode = q["mode"].orEmpty(),
                allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
                obfs = q["obfs"].orEmpty(),
                obfsPassword = q["obfs-password"].orEmpty(),
            ),
        )
    }

    // ---------- ss:// (SIP002 and legacy) ----------
    private fun parseShadowsocks(link: String): Server? {
        val raw = link.removePrefix("ss://")
        val name = raw.substringAfter('#', "").urlDecode()
        var main = raw.substringBefore('#').substringBefore('?')
        if (!main.contains('@')) main = decodeBase64(main) ?: return null   // legacy: whole thing encoded
        val userInfo = main.substringBeforeLast('@')
        val hostPort = main.substringAfterLast('@')
        val creds = if (userInfo.contains(':')) userInfo.urlDecode() else decodeBase64(userInfo) ?: return null
        val (host, port) = splitHostPort(hostPort) ?: return null
        return Server(
            name = name.ifBlank { host },
            protocol = Protocol.SHADOWSOCKS,
            address = host, port = port, link = link,
            xray = XrayOutbound(method = creds.substringBefore(':'), uuid = creds.substringAfter(':')),
        )
    }

    private fun parseSocks(link: String): Server? {
        val p = SplitLink.of(link) ?: return null
        val creds = if (p.user.isEmpty()) "" else decodeBase64(p.user) ?: p.user
        return Server(
            name = p.name.ifBlank { p.host }, protocol = Protocol.SOCKS,
            address = p.host, port = p.port, link = link,
            xray = XrayOutbound(user = creds.substringBefore(':', ""), uuid = creds.substringAfter(':', "")),
        )
    }

    // ---------- WireGuard ----------
    /** wireguard://<privateKey>@host:port?publickey=..&address=..&mtu=..&presharedkey=..#name */
    private fun parseWireGuardLink(link: String): Server? {
        val p = SplitLink.of(link) ?: return null
        val q = p.query
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${p.user}")
            appendLine("Address = ${q["address"] ?: "172.16.0.2/32"}")
            appendLine("DNS = ${q["dns"] ?: "1.1.1.1, 1.0.0.1"}")
            q["mtu"]?.let { appendLine("MTU = $it") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${q["publickey"] ?: q["publicKey"] ?: return null}")
            q["presharedkey"]?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${if (p.host.contains(':')) "[${p.host}]" else p.host}:${p.port}")
            appendLine("PersistentKeepalive = 25")
        }
        return parseWireGuardConf(conf, p.name)
    }

    fun parseWireGuardConf(conf: String, name: String = "", subscriptionId: String? = null): Server? {
        val endpoint = Regex("""(?im)^\s*Endpoint\s*=\s*(\S+)""").find(conf)?.groupValues?.get(1) ?: return null
        val (host, port) = splitHostPort(endpoint) ?: return null
        return Server(
            name = name.ifBlank { "WireGuard $host" },
            protocol = Protocol.WIREGUARD,
            address = host, port = port,
            wgConf = conf.trim(),
            subscriptionId = subscriptionId,
        )
    }

    // ---------- helpers ----------
    private class SplitLink(val user: String, val host: String, val port: Int, val query: Map<String, String>, val name: String) {
        companion object {
            fun of(link: String): SplitLink? {
                val rest = link.substringAfter("://")
                val name = rest.substringAfter('#', "").urlDecode()
                val noFrag = rest.substringBefore('#')
                val query = noFrag.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to it.substringAfter('=').urlDecode() }
                val authority = noFrag.substringBefore('?').trimEnd('/')
                val user = if (authority.contains('@')) authority.substringBeforeLast('@').urlDecode() else ""
                val (host, port) = splitHostPort(authority.substringAfterLast('@')) ?: return null
                return SplitLink(user, host, port, query, name)
            }
        }
    }

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val host: String
        val port: String
        if (s.startsWith("[")) {
            host = s.substringAfter('[').substringBefore(']')
            port = s.substringAfter("]:", "")
        } else {
            host = s.substringBeforeLast(':')
            port = s.substringAfterLast(':', "")
        }
        val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to p
    }

    fun decodeBase64(s: String): String? {
        val clean = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            runCatching { return String(Base64.decode(padded, flags), Charsets.UTF_8) }
        }
        return null
    }

    // Keep literal '+' (common in base64 / passwords) instead of turning it into a space
    private fun String.urlDecode(): String =
        runCatching { URLDecoder.decode(replace("+", "%2B"), "UTF-8") }.getOrDefault(this)
}
