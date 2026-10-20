package com.netino.vpn.core

import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/**
 * "National internet" (the international links cut, only Iranian sites reachable):
 *  - [check] tells a national-only network from a normal one and from no network at all
 *  - [bridges]: the saved servers that sit inside Iran (Iranian IP ranges, Arvan / Derak / ParsPack / IranServer).
 *    Behind a national shutdown these "bridge" servers, which keep their own link abroad, are usually the only
 *    configs that still work, so they are tried first
 *  - [pack]: every saved server as plain share links, to pass on by Bale / Eitaa / Bluetooth when subscriptions
 *    (and Netino's own list on GitHub) can't be reached
 */
object NationalNet {

    enum class State { NORMAL, NATIONAL, OFFLINE }

    private val DOMESTIC = listOf("https://www.aparat.com/", "https://www.digikala.com/", "https://divar.ir/")
    private val INTERNATIONAL = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204", "https://www.google.com/")

    /**
     * Directly, outside the VPN (the app is excluded from its own tunnel): international sites answering = normal;
     * only Iranian ones = national internet; neither = offline.
     */
    suspend fun check(): State = withContext(Dispatchers.IO) {
        coroutineScope {
            val intl = async { INTERNATIONAL.any(::reachable) }
            val dom = async { DOMESTIC.any(::reachable) }
            when {
                intl.await() -> State.NORMAL
                dom.await() -> State.NATIONAL
                else -> State.OFFLINE
            }
        }
    }

    private fun reachable(url: String): Boolean = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = false; requestMethod = "HEAD"
            try { responseCode in 100..499 } finally { disconnect() }
        }
    }.getOrDefault(false)

    // ---------------- servers inside Iran ----------------

    /** Iranian IP ranges from the geo file (IR + domestic hosting / CDNs), as (network, mask) pairs; loaded once. */
    @Volatile private var ranges: List<Pair<Int, Int>>? = null
    private val CODES = setOf("ir", "arvancloud", "derakcloud", "parspack", "iranserver")

    /** Whether the Iranian ranges are available (they come with the optional geo files). */
    fun canClassify() = XrayCore.hasGeoFiles && "ir" in XrayCore.geoIpTags

    fun reloadRanges() { ranges = null }

    private fun ranges(): List<Pair<Int, Int>> = ranges ?: runCatching { readRanges(File(XrayCore.dir, "geoip.dat")) }
        .getOrDefault(emptyList()).also { ranges = it }

    /** IPv4 CIDRs of [CODES] in geoip.dat (protobuf: entry = 1 { country_code = 1; cidr = 2 { ip = 1; prefix = 2 } }). */
    private fun readRanges(file: File): List<Pair<Int, Int>> = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
        val out = mutableListOf<Pair<Int, Int>>()
        while (true) {
            if (try { varint(input) } catch (e: EOFException) { break } != 0x0AL) break
            val entry = ByteArray(varint(input).toInt()).also { input.readFully(it) }
            var i = 0
            var code: String? = null
            val cidrs = mutableListOf<Pair<Int, Int>>()
            while (i < entry.size) {
                val (key, a) = varint(entry, i); i = a
                if ((key and 7L) != 2L) { i = varint(entry, i).second; continue }
                val (len, b) = varint(entry, i); i = b
                val v = entry.copyOfRange(i, i + len.toInt()); i += len.toInt()
                when (key shr 3) {
                    1L -> code = String(v).lowercase()
                    2L -> cidr(v)?.let { cidrs += it }
                }
            }
            if (code in CODES) out += cidrs
        }
        out
    }

    /** One CIDR message: IPv4 only (the servers in subscriptions are reached over IPv4 almost always). */
    private fun cidr(v: ByteArray): Pair<Int, Int>? {
        var i = 0
        var ip: ByteArray? = null
        var prefix = 0
        while (i < v.size) {
            val (key, a) = varint(v, i); i = a
            when {
                (key and 7L) == 2L -> { val (len, b) = varint(v, i); i = b; ip = v.copyOfRange(i, i + len.toInt()); i += len.toInt() }
                else -> { val (value, b) = varint(v, i); i = b; if ((key shr 3) == 2L) prefix = value.toInt() }
            }
        }
        val addr = ip?.takeIf { it.size == 4 } ?: return null
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (toInt(addr) and mask) to mask
    }

    private fun toInt(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun isIranian(ip: InetAddress): Boolean {
        if (ip !is Inet4Address) return false
        val v = toInt(ip.address)
        return ranges().any { (net, mask) -> (v and mask) == net }
    }

    /** Resolved addresses, so a list is resolved once per session (DNS is the slow part). */
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * The saved servers inside Iran. Domains are resolved with the network's own DNS (inside Iran it answers even
     * during a shutdown). WARP isn't a bridge: it is reached abroad.
     */
    suspend fun bridges(servers: List<Server> = Repository.servers.value): List<Server> = withContext(Dispatchers.IO) {
        if (!canClassify()) return@withContext emptyList()
        val gate = Semaphore(16)
        coroutineScope {
            servers.filter { it.subscriptionId != Warp.SUB }
                .map { s ->
                    async {
                        gate.withPermit {
                            val iranian = resolved.getOrPut(s.address) {
                                runCatching { InetAddress.getAllByName(s.address).any(::isIranian) }.getOrDefault(false)
                            }
                            if (iranian) s else null
                        }
                    }
                }.awaitAll().filterNotNull()
        }
    }

    /**
     * Saved servers as share links, one per line: a text file any client imports. WireGuard configs (whole wg-quick
     * files) and WARP accounts are left out: a file holds one of those, and WARP is made fresh by each phone.
     */
    fun pack(servers: List<Server> = Repository.servers.value): String =
        servers.filter { it.subscriptionId != Warp.SUB && it.link.isNotBlank() }.map { it.link }.distinct().joinToString("\n")

    private fun varint(input: DataInputStream): Long {
        var r = 0L; var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            r = r or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return r
            shift += 7
        }
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var r = 0L; var shift = 0; var i = start
        while (true) {
            val x = b[i++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if (x < 0x80) return r to i
            shift += 7
        }
    }
}
