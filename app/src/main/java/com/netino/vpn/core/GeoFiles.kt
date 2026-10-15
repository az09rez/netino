package com.netino.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Optional routing lists (not bundled, ~25 MB): geosite.dat / geoip.dat from Chocolate4U/Iran-v2ray-rules, made
 * for Iran (geosite:ir, category-ir, category-ads-all, category-ads-ir, geoip:ir ...). Downloaded on request and
 * refreshed weekly. Each file is checked and its tags recorded before it replaces the old one, so a config
 * never names a tag the file doesn't have (Xray would refuse to start).
 */
object GeoFiles {

    const val TAGS = "geotags.txt"
    private const val BASE = "https://github.com/Chocolate4U/Iran-v2ray-rules"
    private val FILES = listOf("geosite.dat", "geoip.dat")

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    /** Release asset first, then the repository's release branch (another host, in case one is throttled). */
    private fun urls(name: String) = listOf("$BASE/releases/latest/download/$name", "https://raw.githubusercontent.com/Chocolate4U/Iran-v2ray-rules/release/$name")

    /** Downloads, checks and installs both files. [onProgress] 0..1. */
    suspend fun update(onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = XrayCore.dir
        val parts = FILES.map { File(dir, "$it.part") }
        runCatching {
            FILES.forEachIndexed { i, name -> download(name, parts[i]) { p -> onProgress((i + p) / FILES.size) } }
            val site = tags(parts[0])
            val ip = tags(parts[1])
            check("ir" in site || "category-ir" in site) { "geosite.dat has no Iran list" }
            check("ir" in ip) { "geoip.dat has no Iran list" }
            FILES.forEachIndexed { i, name ->
                val target = File(dir, name)
                if (!parts[i].renameTo(target)) { parts[i].copyTo(target, overwrite = true) }
            }
            File(dir, TAGS).writeText((site.map { "site:$it" } + ip.map { "ip:$it" }).joinToString("\n"))
            XrayCore.loadGeoTags()
        }.also { parts.forEach { it.delete() } }
    }

    fun remove() {
        (FILES + TAGS).forEach { File(XrayCore.dir, it).delete() }
        XrayCore.loadGeoTags()
    }

    /** Directly, then through the running VPN (GitHub can be throttled on some networks). */
    private fun download(name: String, out: File, progress: (Float) -> Unit) {
        var last: Throwable? = null
        val clients = listOfNotNull(client, LocalRoutes.tunnel()?.let { LocalRoutes.via(client, it) })
        for (c in clients) for (url in urls(name)) {
            try {
                fetch(c, url, out, progress)
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("download failed")
    }

    private fun fetch(c: OkHttpClient, url: String, out: File, progress: (Float) -> Unit) {
        c.newCall(Request.Builder().url(url).header("User-Agent", "Netino").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                    if (done < 1024) throw IOException("file too small")
                }
            }
        }
    }

    /**
     * The tags in a geosite / geoip file (protobuf: repeated entry = 1 { string country_code = 1; ... }), read as a
     * stream: only each entry's first field is decoded, the rest skipped.
     */
    fun tags(file: File): Set<String> = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
        val out = mutableSetOf<String>()
        while (true) {
            val key = try { varint(input) } catch (e: EOFException) { break }
            if (key != 0x0AL) throw IOException("not a geo file")
            val len = varint(input)
            if (len == 0L) continue
            var left = len
            if (input.readUnsignedByte() == 0x0A) {
                val l = varint(input)
                val b = ByteArray(l.toInt()).also { input.readFully(it) }
                out += String(b).lowercase()
                left -= 1 + varintSize(l) + l
            } else left -= 1
            skip(input, left)
        }
        out
    }

    private fun varint(input: InputStream): Long {
        var r = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            r = r or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return r
            shift += 7
        }
    }

    private fun varintSize(v: Long): Int { var n = 1; var x = v; while (x >= 0x80) { x = x shr 7; n++ }; return n }

    private fun skip(input: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= s
        }
    }
}
