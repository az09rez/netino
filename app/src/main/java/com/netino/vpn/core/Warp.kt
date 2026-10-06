package com.netino.vpn.core

import android.util.Base64
import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.Protocol
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

    data class Account(val privateKey: String, val addresses: List<String>, val peerPublicKey: String, val reserved: List<Int>?)

    enum class Mode { WARP, WARP_IN_WARP }

    private const val HOST = "api.cloudflareclient.com"
    private val API_VERSIONS = listOf("v0a1922", "v0a2158")
    private const val PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /** Registers a new free account. Tries directly, then through the running tunnel (the API is filtered on some networks). */
    suspend fun register(): Account = withContext(Dispatchers.IO) {
        val priv = X25519.privateKey()
        val pub = Base64.encodeToString(X25519.publicKey(priv), Base64.NO_WRAP)
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = """{"key":"$pub","install_id":"","fcm_token":"","tos":"$tos","model":"PC","serial_number":"","type":"Android","locale":"en_US"}"""
        val routes: List<HevTunnel.Endpoint?> = listOf(null) + listOfNotNull(XrayVpnService.probe)
        var last: Throwable? = null
        for (via in routes) for (v in API_VERSIONS) {
            val r = runCatching { post("/$v/reg", body, via) }
            r.onSuccess { return@withContext parse(it, Base64.encodeToString(priv, Base64.NO_WRAP)) }
            last = r.exceptionOrNull()
        }
        throw IllegalStateException(last?.message ?: "WARP registration failed", last)
    }

    private fun parse(text: String, privateKey: String): Account {
        val cfg = Json.parseToJsonElement(text).jsonObject["config"]?.jsonObject ?: error("no config in reply")
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

    private fun server(name: String, wgConf: String, endpoint: String, outer: String? = null): Server {
        val host = if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')
        return Server(name = name, protocol = Protocol.WIREGUARD, address = host, port = endpoint.substringAfterLast(':').toInt(),
            wgConf = wgConf.trim(), wgOuter = outer?.trim())
    }

    /** Each WireGuard outbound is a whole userspace network stack: test a modest number at once. */
    private const val SCAN = 24

    /**
     * Full flow: account(s) -> endpoint scan on this network -> ready servers (best first, up to [keep]).
     * [onStage]: 1 = registering, 2 = scanning (done/total), 3 = checking WARP in WARP.
     */
    suspend fun create(
        mode: Mode, ipv6: Boolean, settings: AppSettings, keep: Int = 2,
        onStage: (stage: Int, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<Server> {
        onStage(1, 0, 0)
        val outer = register()
        val inner = if (mode == Mode.WARP_IN_WARP) register() else null
        val candidates = endpoints(ipv6, SCAN).mapIndexed { i, ep -> server("scan-$i", conf(outer, ep), ep) }
        onStage(2, 0, candidates.size)
        val ok = BatchTester.raw(candidates, settings).filter { it.ms > 0 }.sortedBy { it.ms }
        onStage(2, candidates.size, candidates.size)
        val best = ok.take(keep).map { it.server }
        if (best.isEmpty()) return emptyList()
        if (inner == null) return best.map { s ->
            val ep = endpointOf(s)
            server("WARP • $ep", s.wgConf!!, ep)
        }
        // Inner hop: any WARP endpoint works, it is reached through the outer tunnel
        onStage(3, 0, best.size)
        val chained = best.map { s ->
            val ep = endpointOf(s)
            server("WARP in WARP • $ep", conf(inner, "162.159.192.1:2408", mtu = 1200), ep, outer = s.wgConf)
        }
        val tested = BatchTester.raw(chained, settings).filter { it.ms > 0 }.map { it.server }
        onStage(3, best.size, best.size)
        // If the chain doesn't come up here, plain WARP on the same endpoints is still worth having
        return tested.ifEmpty { best.map { s -> endpointOf(s).let { server("WARP • $it", s.wgConf!!, it) } } }
    }

    private fun endpointOf(s: Server) = if (s.address.contains(':')) "[${s.address}]:${s.port}" else "${s.address}:${s.port}"

    // ---------------- HTTPS without the system proxy stack ----------------

    private fun post(path: String, body: String, via: HevTunnel.Endpoint?): String {
        Socket().use { raw ->
            raw.soTimeout = 15_000
            if (via == null) raw.connect(InetSocketAddress(HOST, 443), 10_000)
            else {
                raw.connect(InetSocketAddress("127.0.0.1", via.port), 3000)
                SpeedProbe.socks5Connect(raw, via.user, via.pass, HOST, 443)
            }
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true).use { tls ->
                tls as SSLSocket
                tls.startHandshake()
                val bytes = body.toByteArray()
                val req = "POST $path HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: okhttp/3.12.1\r\nCF-Client-Version: a-6.30-3596\r\n" +
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
        if (code !in 200..299) error("HTTP $code")
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
