package com.netino.vpn.data

import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Clash / Clash Meta (Mihomo) subscriptions: the YAML `proxies:` list. Each proxy becomes the standard share
 * link (vless://, vmess://, trojan://, ss://, hysteria2://, or a WireGuard config) and goes through
 * [LinkParser], so it is handled exactly like a pasted link. Proxy groups and rules are ignored.
 */
object ClashParser {

    /** A Clash config has a top-level `proxies:` key. */
    fun looksLikeClash(text: String) = Regex("""(?m)^proxies\s*:""").containsMatchIn(text)

    fun parse(text: String, subscriptionId: String? = null): List<Server> {
        val proxies = (MiniYaml.parse(text) as? Map<*, *>)?.get("proxies") as? List<*> ?: return emptyList()
        return proxies.mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            runCatching { toServer(m, subscriptionId) }.getOrNull()
        }
    }

    private fun toServer(m: Map<*, *>, sub: String?): Server? {
        fun s(k: String) = m[k]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        fun b(k: String) = m[k]?.toString() == "true"
        fun map(k: String) = m[k] as? Map<*, *>
        val type = s("type")?.lowercase() ?: return null
        val name = s("name") ?: s("server") ?: return null
        val server = s("server") ?: return null
        val port = s("port")?.toIntOrNull() ?: return null

        if (type == "wireguard") return wireGuard(m, name, server, port, sub)

        // Transport and TLS, shared by vless / vmess / trojan
        val network = s("network") ?: "tcp"
        val ws = map("ws-opts"); val grpc = map("grpc-opts"); val h2 = map("h2-opts"); val hu = map("httpupgrade-opts")
        val xh = map("xhttp-opts")
        val headers = (ws ?: hu ?: xh)?.get("headers") as? Map<*, *>
        val host = headers?.get("Host")?.toString() ?: headers?.get("host")?.toString()
            ?: (h2?.get("host") as? List<*>)?.joinToString(",") ?: (xh ?: hu)?.get("host")?.toString()
        val path = (ws ?: hu ?: xh ?: h2)?.get("path")?.toString()
        val serviceName = grpc?.get("grpc-service-name")?.toString()
        val reality = map("reality-opts")
        val tls = b("tls") || type == "trojan" || reality != null
        val sni = s("servername") ?: s("sni")
        val alpn = (m["alpn"] as? List<*>)?.joinToString(",")
        val fp = s("client-fingerprint")
        val q = linkedMapOf<String, String?>()
        q["type"] = when (network) { "http" -> "tcp"; else -> network }
        if (network == "http") q["headerType"] = "http"
        q["security"] = if (reality != null) "reality" else if (tls) "tls" else "none"
        q["sni"] = sni; q["alpn"] = alpn; q["fp"] = fp
        q["host"] = host; q["path"] = path; q["serviceName"] = serviceName
        q["mode"] = xh?.get("mode")?.toString()
        reality?.let { q["pbk"] = it["public-key"]?.toString(); q["sid"] = it["short-id"]?.toString() }
        if (b("skip-cert-verify")) q["allowInsecure"] = "1"

        val link = when (type) {
            "vless" -> {
                q["encryption"] = "none"
                q["flow"] = s("flow")
                std("vless", s("uuid") ?: return null, server, port, q, name)
            }
            "trojan" -> std("trojan", s("password") ?: return null, server, port, q, name)
            "vmess" -> vmess(m, name, server, port, network, host, path ?: serviceName, tls, sni, alpn, fp) ?: return null
            "ss", "shadowsocks" -> {
                // Plugins (obfs, v2ray-plugin) aren't supported by Xray's Shadowsocks: skip those
                if (s("plugin") != null) return null
                val creds = Base64.encodeToString("${s("cipher") ?: return null}:${s("password") ?: return null}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                "ss://$creds@${hostPort(server, port)}#${enc(name)}"
            }
            "hysteria2", "hy2" -> {
                val hq = linkedMapOf<String, String?>("sni" to sni, "alpn" to alpn, "obfs" to s("obfs"), "obfs-password" to s("obfs-password"))
                if (b("skip-cert-verify")) hq["insecure"] = "1"
                std("hysteria2", s("password") ?: s("auth") ?: return null, server, port, hq, name)
            }
            else -> return null   // ssr, tuic, snell, ... aren't Xray protocols
        }
        return LinkParser.parse(link, sub)
    }

    private fun vmess(
        m: Map<*, *>, name: String, server: String, port: Int, network: String, host: String?, path: String?,
        tls: Boolean, sni: String?, alpn: String?, fp: String?,
    ): String? {
        val json = buildJsonObject {
            put("v", "2"); put("ps", name); put("add", server); put("port", port.toString())
            put("id", m["uuid"]?.toString() ?: return null)
            put("aid", m["alterId"]?.toString() ?: "0")
            put("scy", m["cipher"]?.toString() ?: "auto")
            put("net", if (network == "http") "tcp" else network)
            put("type", if (network == "http") "http" else "none")
            put("host", host.orEmpty()); put("path", path.orEmpty())
            put("tls", if (tls) "tls" else ""); put("sni", sni.orEmpty()); put("alpn", alpn.orEmpty()); put("fp", fp.orEmpty())
        }
        return "vmess://" + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }

    private fun wireGuard(m: Map<*, *>, name: String, server: String, port: Int, sub: String?): Server? {
        val key = m["private-key"]?.toString() ?: return null
        val peer = m["public-key"]?.toString() ?: (m["peers"] as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("public-key")?.toString() } ?: return null
        val addresses = listOfNotNull(m["ip"]?.toString()?.let { if ('/' in it) it else "$it/32" },
            m["ipv6"]?.toString()?.let { if ('/' in it) it else "$it/128" })
        val reserved = (m["reserved"] as? List<*>)?.joinToString(", ") ?: m["reserved"]?.toString()
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $key")
            appendLine("Address = ${addresses.ifEmpty { listOf("172.16.0.2/32") }.joinToString(", ")}")
            m["mtu"]?.toString()?.let { appendLine("MTU = $it") }
            reserved?.let { appendLine("Reserved = $it") }
            appendLine("[Peer]")
            appendLine("PublicKey = $peer")
            m["pre-shared-key"]?.toString()?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${hostPort(server, port)}")
            appendLine("PersistentKeepalive = 25")
        }
        return LinkParser.parseWireGuardConf(conf, name, sub)
    }

    private fun std(scheme: String, user: String, server: String, port: Int, q: Map<String, String?>, name: String): String {
        val query = q.filterValues { !it.isNullOrBlank() }.entries.joinToString("&") { "${it.key}=${enc(it.value!!)}" }
        return "$scheme://${enc(user)}@${hostPort(server, port)}?$query#${enc(name)}"
    }

    private fun hostPort(host: String, port: Int) = if (':' in host) "[$host]:$port" else "$host:$port"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * The part of YAML that Clash configs use: block maps and lists by indentation, flow maps / lists
 * ({a: 1, b: [x, y]}), plain, 'single' and "double" quoted scalars, and comments. Anchors, multi-line
 * scalars and tags aren't needed for proxies and are not supported.
 */
internal object MiniYaml {

    fun parse(text: String): Any? {
        val lines = text.lines().mapNotNull { raw ->
            val l = stripComment(raw).trimEnd()
            if (l.isBlank() || l.trim() == "---") null else Line(l.length - l.trimStart().length, l.trim())
        }
        return if (lines.isEmpty()) null else Parser(lines).block(lines[0].indent)
    }

    private class Line(val indent: Int, val text: String)

    private fun Line.isItem() = text == "-" || text.startsWith("- ")

    private class Parser(val lines: List<Line>) {
        var i = 0

        /** The block (map or list) whose lines start at [indent]. */
        fun block(indent: Int): Any? {
            if (i >= lines.size) return null
            return if (lines[i].isItem()) list(indent) else map(indent)
        }

        private fun list(indent: Int): List<Any?> {
            val out = mutableListOf<Any?>()
            while (i < lines.size && lines[i].indent == indent && lines[i].isItem()) {
                val l = lines[i]
                val rest = l.text.removePrefix("-").trimStart()
                i++
                when {
                    rest.isEmpty() -> out += nested(indent)
                    !rest.startsWith("{") && !rest.startsWith("[") && keyEnd(rest) >= 0 -> {
                        // "- key: value" starts a map; its other keys line up with "key"
                        val childIndent = l.indent + (l.text.length - rest.length)
                        val m = linkedMapOf<String, Any?>()
                        entry(rest, m, childIndent)
                        while (i < lines.size && lines[i].indent == childIndent && !lines[i].isItem()) { val t = lines[i].text; i++; entry(t, m, childIndent) }
                        out += m
                    }
                    else -> out += scalarOrFlow(rest)
                }
            }
            return out
        }

        private fun map(indent: Int): Map<String, Any?> {
            val m = linkedMapOf<String, Any?>()
            while (i < lines.size && lines[i].indent == indent && !lines[i].isItem()) { val t = lines[i].text; i++; entry(t, m, indent) }
            // Skip anything deeper we didn't understand, so the caller's level continues correctly
            while (i < lines.size && lines[i].indent > indent) i++
            return m
        }

        /** "key: value"; an empty value means a nested block on the following lines (deeper, or a list at the same indent). */
        private fun entry(t: String, m: MutableMap<String, Any?>, indent: Int) {
            val end = keyEnd(t)
            if (end < 0) return
            val key = unquote(t.substring(0, end))
            val value = t.substring(end + 1).trim()
            m[key] = if (value.isEmpty()) nested(indent) else scalarOrFlow(value)
        }

        private fun nested(indent: Int): Any? {
            if (i >= lines.size) return null
            val next = lines[i]
            return if (next.indent > indent || (next.indent == indent && next.isItem())) block(next.indent) else null
        }
    }

    /** Index of the colon ending "key:" (followed by a space or the end of the line), -1 for a plain scalar. */
    private fun keyEnd(s: String): Int {
        var q: Char? = null
        for ((idx, c) in s.withIndex()) {
            if (q != null) { if (c == q) q = null; continue }
            when {
                (c == '"' || c == '\'') && idx == 0 -> q = c
                c == ':' && (idx == s.length - 1 || s[idx + 1] == ' ') -> return idx
            }
        }
        return -1
    }

    private fun scalarOrFlow(v: String): Any? = if (v.startsWith("{") || v.startsWith("[")) Flow(v).value() else scalar(v)

    private fun scalar(v: String): Any? {
        val t = v.trim()
        if (t.length >= 2 && (t[0] == '"' && t.last() == '"' || t[0] == '\'' && t.last() == '\'')) return unquote(t)
        return if (t == "~" || t == "null" || t.isEmpty()) null else t
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return when {
            t.length >= 2 && t[0] == '"' && t.last() == '"' -> t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            t.length >= 2 && t[0] == '\'' && t.last() == '\'' -> t.substring(1, t.length - 1).replace("''", "'")
            else -> t
        }
    }

    private fun stripComment(raw: String): String {
        var q: Char? = null
        for ((idx, c) in raw.withIndex()) {
            if (q != null) { if (c == q) q = null; continue }
            if (c == '"' || c == '\'') q = c
            else if (c == '#' && (idx == 0 || raw[idx - 1] == ' ' || raw[idx - 1] == '\t')) return raw.substring(0, idx)
        }
        return raw
    }

    /** Flow collections: {k: v, ...} and [a, b, ...], nested, with quoted scalars. */
    private class Flow(val s: String) {
        var p = 0

        fun value(): Any? {
            ws()
            if (p >= s.length) return null
            return when (s[p]) {
                '{' -> {
                    p++
                    val m = linkedMapOf<String, Any?>()
                    ws()
                    while (p < s.length && s[p] != '}') {
                        val k = unquote(token(key = true))
                        ws()
                        if (p < s.length && s[p] == ':') { p++; m[k] = value() } else m[k] = null
                        ws(); if (p < s.length && s[p] == ',') p++; ws()
                    }
                    p++
                    m
                }
                '[' -> {
                    p++
                    val l = mutableListOf<Any?>()
                    ws()
                    while (p < s.length && s[p] != ']') { l += value(); ws(); if (p < s.length && s[p] == ',') p++; ws() }
                    p++
                    l
                }
                else -> scalar(token(key = false))
            }
        }

        private fun ws() { while (p < s.length && s[p] == ' ') p++ }

        /** A scalar up to , } ] (and, for a key, up to a colon followed by a space: "a:b" stays one value). */
        private fun token(key: Boolean): String {
            ws()
            val start = p
            if (p < s.length && (s[p] == '"' || s[p] == '\'')) {
                val q = s[p]; p++
                while (p < s.length && s[p] != q) { if (s[p] == '\\' && q == '"') p++; p++ }
                p++
                return s.substring(start, minOf(p, s.length))
            }
            while (p < s.length) {
                val c = s[p]
                if (c == ',' || c == '}' || c == ']') break
                if (key && c == ':' && (p + 1 >= s.length || s[p + 1] == ' ' || s[p + 1] == '{' || s[p + 1] == '[')) break
                p++
            }
            return s.substring(start, p).trim()
        }
    }
}
