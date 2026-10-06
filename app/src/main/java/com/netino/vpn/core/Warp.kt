package com.netino.vpn.core

import android.util.Base64
import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.NetKey
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Cloudflare WARP without any config from outside: the app registers its own free WARP account,
 * finds WARP endpoints (IP + port) that answer on the current network, and adds ready servers.
 * WARP in WARP: a second account whose WireGuard runs *inside* the first, so the firewall only
 * ever sees the outer handshake (with noise in front of it) going to an endpoint that works here.
 */
object Warp {

    /** [id] / [token]: the registration, to apply a license or unregister it. [plus]: WARP+ result (account type or error). */
    data class Account(
        val privateKey: String, val addresses: List<String>, val peerPublicKey: String, val reserved: List<Int>?,
        val id: String = "", val token: String = "", val plus: String? = null,
    ) {
        val reg get() = if (id.isNotBlank() && token.isNotBlank()) "$id:$token" else null
    }

    enum class Mode { WARP, WARP_IN_WARP }

    private const val HOST = "api.cloudflareclient.com"
    private val API_VERSIONS = listOf("v0a1922", "v0a2158")
    private const val PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /**
     * Cloudflare serves the API on any of its edge IPs, so a poisoned DNS answer or a blocked IP doesn't
     * stop it: these are the API's own addresses, the scanned clean IPs come first.
     */
    private val API_IPS = listOf("104.16.24.84", "104.16.192.82", XrayConfigBuilder.DEFAULT_CF_V4)

    /** One way to reach the API: through [via] (a local SOCKS) or directly, to [target] (host or IP); [kind] for the report. */
    private data class Route(val via: HevTunnel.Endpoint?, val target: String, val kind: String)

    private fun apiIps(settings: AppSettings) = (listOf(settings.cleanIp, settings.cleanIp6).filter { it.isNotBlank() } + API_IPS).distinct()

    /** The running tunnel's probe inbound, only while the tunnel is really up (a stale endpoint would just time out). */
    private fun tunnelRoute() = XrayVpnService.probe?.takeIf { XrayVpnService.isRunning && XrayCore.isRunning }?.let { Route(it, HOST, "through the tunnel") }

    /**
     * Registers a new free account. On a strictly filtered network (MCI) the API's name is blocked, so it
     * tries in turn: directly; directly to known Cloudflare IPs; with the TLS ClientHello split into
     * pieces by a small Xray core (the firewall can't read the name); and through the running tunnel.
     * [license]: a WARP+ key applied right away, over the same route.
     */
    suspend fun register(settings: AppSettings, license: String? = null): Account = withContext(Dispatchers.IO) {
        val priv = X25519.privateKey()
        val pub = Base64.encodeToString(X25519.publicKey(priv), Base64.NO_WRAP)
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = """{"key":"$pub","install_id":"","fcm_token":"","tos":"$tos","model":"PC","serial_number":"","type":"Android","locale":"en_US"}"""
        val ips = apiIps(settings)
        val t0 = System.currentTimeMillis()
        var last: Throwable? = null
        fun attempt(routes: List<Route>): Account? {
            for (r in routes) for (v in API_VERSIONS) {
                try {
                    val a = parse(request("POST", "/$v/reg", body, r.via, r.target), Base64.encodeToString(priv, Base64.NO_WRAP))
                    NetReport.add("WARP account made ${r.kind} (${r.target}) in ${System.currentTimeMillis() - t0} ms")
                    return if (license == null || a.id.isBlank()) a else a.copy(plus = applyLicense(a, license, v, r))
                } catch (e: java.io.IOException) {
                    last = e; break        // network: this route is blocked, the other API version won't help
                } catch (e: Throwable) {
                    last = e               // HTTP error: try the other API version
                }
            }
            return null
        }
        attempt(listOf(Route(null, HOST, "directly")) + ips.map { Route(null, it, "directly to a Cloudflare IP") })?.let { return@withContext it }
        withFragmentCore { ep -> attempt(listOf(Route(ep, HOST, "with fragmented TLS")) + ips.map { Route(ep, it, "with fragmented TLS to a Cloudflare IP") }) }
            ?.let { return@withContext it }
        tunnelRoute()?.let { r -> attempt(listOf(r)) }?.let { return@withContext it }
        NetReport.add("WARP account failed on every route: ${last?.message}")
        throw IllegalStateException(last?.message ?: "WARP registration failed", last)
    }

    /** Puts a WARP+ key on [a]; returns the account type Cloudflare then reports ("limited" / "unlimited" = WARP+), or the error. */
    private fun applyLicense(a: Account, license: String, version: String, r: Route): String = try {
        val reply = request("PUT", "/$version/reg/${a.id}/account", """{"license":"$license"}""", r.via, r.target, a.token)
        runCatching { Json.parseToJsonElement(reply).jsonObject["account_type"]?.jsonPrimitive?.content }.getOrNull() ?: "accepted"
    } catch (e: Throwable) {
        "error: ${e.message}"
    }

    /**
     * Deletes registrations ("id:token") that no server uses any more, so they stop counting against a
     * WARP+ license's device limit. Best effort: directly, then fragmented TLS, then through the tunnel.
     */
    suspend fun unregister(regs: Collection<String>, settings: AppSettings) = withContext(Dispatchers.IO) {
        if (regs.isEmpty()) return@withContext
        val ips = apiIps(settings)
        val v = API_VERSIONS.last()
        fun tryAll(left: List<String>, routes: List<Route>): List<String> = left.filter { reg ->
            val id = reg.substringBefore(':'); val token = reg.substringAfter(':')
            routes.none { r ->
                try { request("DELETE", "/$v/reg/$id", null, r.via, r.target, token); true }
                catch (e: java.io.IOException) { false }
                catch (e: Throwable) { true }          // HTTP error (already gone, ...): nothing more to do
            }
        }
        var left = tryAll(regs.toList(), listOf(Route(null, HOST, "")) + ips.map { Route(null, it, "") })
        if (left.isNotEmpty()) left = withFragmentCore { ep -> tryAll(left, listOf(Route(ep, HOST, ""))) } ?: left
        if (left.isNotEmpty()) tunnelRoute()?.let { r -> left = tryAll(left, listOf(r)) }
        NetReport.add("WARP: ${regs.size - left.size} of ${regs.size} unused accounts unregistered")
    }

    /** The account a WARP server belongs to: its first hop's key (WARP in WARP and plain servers of one search share it). */
    fun accountKey(s: Server): String? = (s.wgOuter ?: s.wgConf)?.let { WireGuardCore.parse(it)?.privateKey }

    /** Runs [block] with a throw-away core whose only outbound fragments the TLS ClientHello. */
    private fun <T> withFragmentCore(block: (HevTunnel.Endpoint) -> T?): T? {
        val ep = HevTunnel.newEndpoint()
        val config = """{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":${ep.port},"protocol":"socks",""" +
            """"settings":{"auth":"password","accounts":[{"user":"${ep.user}","pass":"${ep.pass}"}],"udp":false}}],""" +
            """"outbounds":[{"protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"10-30","interval":"10-20"}}}]}"""
        val core = runCatching { XrayCore.startTestCore(config) }.getOrNull() ?: return null
        return try { block(ep) } finally { XrayCore.stopTestCore(core) }
    }

    private fun parse(text: String, privateKey: String): Account {
        val root = Json.parseToJsonElement(text).jsonObject
        val cfg = root["config"]?.jsonObject ?: error("no config in reply")
        val addr = cfg["interface"]!!.jsonObject["addresses"]!!.jsonObject
        val v4 = addr["v4"]?.jsonPrimitive?.content
        val v6 = addr["v6"]?.jsonPrimitive?.content
        val peer = cfg["peers"]?.jsonArray?.firstOrNull()?.jsonObject
        val reserved = cfg["client_id"]?.jsonPrimitive?.content?.let { WireGuardCore.parseReserved(it) }
        return Account(
            privateKey = privateKey,
            addresses = listOfNotNull(v4?.let { "$it/32" }, v6?.let { "$it/128" }),
            peerPublicKey = peer?.get("public_key")?.jsonPrimitive?.content ?: PEER_KEY,
            reserved = reserved,
            id = root["id"]?.jsonPrimitive?.content.orEmpty(),
            token = root["token"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /** wg-quick text for [a] connecting to [endpoint] ("ip:port" or "[v6]:port"). */
    fun conf(a: Account, endpoint: String, mtu: Int = 1280) = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${a.privateKey}")
        appendLine("Address = ${a.addresses.joinToString(", ")}")
        appendLine("MTU = $mtu")
        a.reserved?.let { appendLine("Reserved = ${it.joinToString(", ")}") }
        appendLine("[Peer]")
        appendLine("PublicKey = ${a.peerPublicKey}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = $endpoint")
        appendLine("PersistentKeepalive = 25")
    }

    // ---------------- endpoints ----------------

    private val V4_PREFIXES = listOf("162.159.192.", "162.159.193.", "162.159.195.", "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.")
    private val V6_PREFIXES = listOf("2606:4700:d0::", "2606:4700:d1::")
    private val PORTS = listOf(2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943,
        945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1843, 2371, 2506, 3138, 3476, 3581, 3854,
        4177, 4198, 4233, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319, 8742, 8854, 8886)

    fun endpoints(ipv6: Boolean, count: Int): List<String> = List(count) {
        val port = if (it < 4) listOf(2408, 500, 1701, 4500)[it] else PORTS.random()
        if (ipv6) {
            val g = List(4) { Random.nextInt(0, 0xffff).toString(16) }.joinToString(":")
            "[${V6_PREFIXES.random()}$g]:$port"
        } else "${V4_PREFIXES.random()}${Random.nextInt(1, 255)}:$port"
    }.distinct()

    private fun server(name: String, wgConf: String, endpoint: String, outer: String? = null, regs: List<String> = emptyList()): Server {
        val host = if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')
        return Server(name = name, protocol = Protocol.WIREGUARD, address = host, port = endpoint.substringAfterLast(':').toInt(),
            wgConf = wgConf.trim(), wgOuter = outer?.trim(), warpRegs = regs)
    }

    /** Each WireGuard outbound is a whole userspace network stack: test a modest number at once. */
    private const val SCAN = 24
    private const val SCAN_PARALLEL = 3

    /** Subscription id of the WARP section on the Servers tab (not a real subscription: nothing to download). */
    const val SUB = "warp"

    /**
     * Full flow: account(s) -> endpoint scan on this network -> ready servers (fastest first, up to [keep]),
     * each carrying the delay it was just measured with. [ipv6] null: IPv4 first, IPv6 if nothing answered.
     * [onStage]: 1 = registering, 2 = scanning (done/total), 3 = checking WARP in WARP.
     */
    suspend fun create(
        mode: Mode, ipv6: Boolean?, settings: AppSettings, keep: Int = 3,
        onStage: (stage: Int, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<Server> {
        onStage(1, 0, 0)
        // WARP+ goes on the account traffic leaves through: the inner one in WARP in WARP
        val license = settings.warpLicense.filter { it.isLetterOrDigit() || it == '-' }.takeIf { it.isNotBlank() }
        val outer = register(settings, license.takeIf { mode == Mode.WARP })
        // The second account only if the first could be made (same route); WARP alone still works without it
        val inner = if (mode == Mode.WARP_IN_WARP) runCatching { register(settings, license) }.getOrNull() else null
        if (license != null) NetReport.add("WARP+ license: " + ((if (mode == Mode.WARP) outer.plus else inner?.plus) ?: "not applied"))
        if (mode == Mode.WARP_IN_WARP && inner == null) NetReport.add("WARP in WARP: the second account couldn't be made")
        val net = NetKey.current
        val remembered = settings.warpEndpointsByNet[net].orEmpty()
        for (v6 in ipv6?.let { listOf(it) } ?: listOf(false, true)) {
            // Endpoints that answered on this network last time are tried first
            val known = remembered.filter { it.startsWith("[") == v6 }
            val candidates = (known + endpoints(v6, SCAN)).distinct().mapIndexed { i, ep -> server("scan-$i", conf(outer, ep), ep) }
            onStage(2, 0, candidates.size)
            val t0 = System.currentTimeMillis()
            // All candidates share the account's new key: 3 at a time, and stop once enough answered
            val first = BatchTester.raw(candidates, settings, enough = keep, scan = SCAN_PARALLEL)
            var found = first.filter { it.ms > 0 }
            var retest = ""
            if (found.size in 1 until keep) {
                // Some answer, so WARP gets through here: re-test the failed ones one at a time. What that finds
                // is what testing 3 at a time with one key missed (or plain chance), and it's kept too.
                val again = BatchTester.raw(first.filter { it.ms <= 0 }.map { it.server }, settings, enough = keep - found.size, scan = 1)
                    .filter { it.ms > 0 }
                retest = ", one-at-a-time re-test of the failed ones found ${again.size} more"
                found = found + again
            }
            val ok = found.sortedBy { it.ms }.take(keep)
            onStage(2, candidates.size, candidates.size)
            NetReport.add("WARP scan ${if (v6) "IPv6" else "IPv4"}: ${ok.size} answered of ${first.size} tested " +
                "($SCAN_PARALLEL at a time$retest), remembered ${known.size} of which ${ok.count { endpointOf(it.server) in known }} answered, " +
                "${(System.currentTimeMillis() - t0) / 1000} s" + ok.joinToString("") { "\n      ${endpointOf(it.server)} ${it.ms} ms" })
            if (ok.isEmpty()) continue
            Repository.updateSettings { it.copy(warpEndpointsByNet = it.warpEndpointsByNet + (net to ok.map { r -> endpointOf(r.server) })) }
            val plain = ok.map { r ->
                endpointOf(r.server).let { ep -> server("WARP • $ep", r.server.wgConf!!, ep, regs = listOfNotNull(outer.reg)).measured(r.ms) }
            }
            if (inner == null) return plain
            // Inner hop: any WARP endpoint works, it is reached through the outer tunnel
            onStage(3, 0, ok.size)
            val regs = listOfNotNull(outer.reg, inner.reg)
            val chained = ok.map { r ->
                val ep = endpointOf(r.server)
                server("WARP in WARP • $ep", conf(inner, INNER_EP, mtu = INNER_MTUS.first()), ep, outer = r.server.wgConf, regs = regs)
            }
            val tested = BatchTester.raw(chained, settings).filter { it.ms > 0 }.sortedBy { it.ms }
            if (tested.isEmpty()) {
                // If the chain doesn't come up here, plain WARP on the same endpoints is still worth having
                NetReport.add("WARP in WARP: none of ${ok.size} endpoints answered, plain WARP kept")
                onStage(3, ok.size, ok.size)
                return plain
            }
            val mtu = if (settings.wgMtu > 0) INNER_MTUS.first() else pickInnerMtu(tested.first().server, inner, settings)
            NetReport.add("WARP in WARP: ${tested.size} of ${ok.size} answered, inner MTU $mtu")
            onStage(3, ok.size, ok.size)
            return tested.map { r ->
                (if (mtu == INNER_MTUS.first()) r.server else r.server.copy(wgConf = conf(inner, INNER_EP, mtu).trim())).measured(r.ms)
            }
        }
        return emptyList()
    }

    private const val INNER_EP = "162.159.192.1:2408"

    /** Inner-hop MTUs to try, largest first (the outer hop is 1280; each WireGuard layer costs up to 80 bytes). */
    private val INNER_MTUS = listOf(1200, 1120, 1000)

    /**
     * A tiny delay test passes even when big packets get lost (pages then load half-way), so a real download
     * decides: the largest inner MTU that downloads at a usable rate. If the speed test can't run at all, 1200.
     */
    private suspend fun pickInnerMtu(chain: Server, inner: Account, settings: AppSettings): Int {
        for (m in INNER_MTUS) {
            val v = chain.copy(id = "mtu-$m", wgConf = conf(inner, INNER_EP, m).trim())
            val r = BatchTester.speeds(listOf(v), settings)[v.id] ?: return INNER_MTUS.first()
            NetReport.add("WARP in WARP MTU $m: ${r.kbps} KB/s" + if (r.ok) "" else " (not usable)")
            if (r.ok) return m
        }
        return INNER_MTUS.first()
    }

    private fun Server.measured(ms: Long) =
        copy(subscriptionId = SUB, lastPingMs = ms, pingKind = com.netino.vpn.data.PingKind.REAL, history = listOf(ms.toInt()))

    private fun endpointOf(s: Server) = if (s.address.contains(':')) "[${s.address}]:${s.port}" else "${s.address}:${s.port}"

    // ---------------- HTTPS without the system proxy stack ----------------

    /** HTTPS request to the API: TLS name / Host stay [HOST], the TCP connection goes to [target]. [token]: the account's bearer token. */
    private fun request(method: String, path: String, body: String?, via: HevTunnel.Endpoint?, target: String, token: String? = null): String {
        Socket().use { raw ->
            raw.soTimeout = 12_000
            if (via == null) raw.connect(InetSocketAddress(target, 443), 6_000)
            else {
                raw.connect(InetSocketAddress("127.0.0.1", via.port), 3000)
                SpeedProbe.socks5Connect(raw, via.user, via.pass, target, 443)
            }
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true).use { tls ->
                tls as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(HOST)) }
                tls.startHandshake()
                val bytes = body?.toByteArray() ?: ByteArray(0)
                val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
                val req = "$method $path HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: okhttp/3.12.1\r\nCF-Client-Version: a-6.30-3596\r\n$auth" +
                    "Content-Type: application/json; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                tls.outputStream.write(req.toByteArray() + bytes)
                tls.outputStream.flush()
                return readResponse(tls.inputStream)
            }
        }
    }

    private fun readResponse(input: InputStream): String {
        val all = input.readBytes()
        val split = indexOf(all, "\r\n\r\n".toByteArray()).takeIf { it >= 0 } ?: error("bad reply")
        val head = String(all, 0, split)
        val status = head.lineSequence().first()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        var body = all.copyOfRange(split + 4, all.size)
        if (head.lowercase().contains("transfer-encoding: chunked")) body = dechunk(body)
        // Cloudflare's own message (e.g. "Too many connected devices") says more than the code
        if (code !in 200..299) error("HTTP $code " + String(body).replace(Regex("\\s+"), " ").take(160))
        return String(body)
    }

    private fun dechunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            val end = indexOf(b, "\r\n".toByteArray(), i).takeIf { it >= 0 } ?: break
            val size = String(b, i, end - i).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(b, end + 2, minOf(size, b.size - end - 2))
            i = end + 2 + size + 2
        }
        return out.toByteArray()
    }

    private fun indexOf(a: ByteArray, p: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..a.size - p.size) {
            for (j in p.indices) if (a[i + j] != p[j]) continue@outer
            return i
        }
        return -1
    }
}
