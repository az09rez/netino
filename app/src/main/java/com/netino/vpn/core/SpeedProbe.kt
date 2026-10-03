package com.netino.vpn.core

import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Measures real download throughput through the connected tunnel, via the core's local SOCKS5
 * inbound (the app itself is outside the VPN, so it can't simply download "through" it).
 * Used by "connect to fastest": a server with a good ping but no usable speed is skipped.
 */
object SpeedProbe {

    data class Result(val bytes: Long, val ms: Long) {
        val kbps get() = if (ms <= 0) 0 else bytes * 1000 / ms / 1024
        /** Enough data at a usable rate: >= 256 KB at >= 64 KB/s. */
        val ok get() = bytes >= 256 * 1024 && kbps >= 64
    }

    private val TARGETS = listOf(
        "speed.cloudflare.com" to "/__down?bytes=1500000",
        "cachefly.cachefly.net" to "/1mb.test",
    )

    /** Blocking; returns the best of the targets (stops at the first that passes). */
    fun run(port: Int, user: String, pass: String, timeoutMs: Long = 8000): Result {
        var best = Result(0, 0)
        for ((host, path) in TARGETS) {
            val r = runCatching { download(port, user, pass, host, path, timeoutMs) }.getOrDefault(Result(0, 0))
            if (r.kbps > best.kbps || best.bytes == 0L) best = r
            if (r.ok) break
        }
        return best
    }

    private fun download(port: Int, user: String, pass: String, host: String, path: String, timeoutMs: Long): Result {
        val start = System.currentTimeMillis()
        Socket().use { raw ->
            raw.soTimeout = 5000
            raw.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socks5Connect(raw, user, pass, host, 443)
            val tls = SSLSocketFactory.getDefault().let { it as SSLSocketFactory }.createSocket(raw, host, 443, true) as SSLSocket
            tls.use {
                it.startHandshake()
                it.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                it.outputStream.flush()
                val input = it.inputStream
                skipHeaders(input)
                val t0 = System.currentTimeMillis()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val n = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    total += n
                }
                return Result(total, (System.currentTimeMillis() - t0).coerceAtLeast(1))
            }
        }
    }

    private fun socks5Connect(s: Socket, user: String, pass: String, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = DataInputStream(s.getInputStream())
        out.write(byteArrayOf(5, 1, 2)); out.flush()                     // greeting: user/pass auth
        check(inp.readUnsignedByte() == 5 && inp.readUnsignedByte() == 2) { "socks auth method" }
        val u = user.toByteArray(); val p = pass.toByteArray()
        out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
        inp.readUnsignedByte(); check(inp.readUnsignedByte() == 0) { "socks auth" }
        val h = host.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
        check(inp.readUnsignedByte() == 5 && inp.readUnsignedByte() == 0) { "socks connect" }
        inp.readUnsignedByte()
        when (inp.readUnsignedByte()) {                                   // skip bound address
            1 -> inp.skipBytes(4)
            3 -> inp.skipBytes(inp.readUnsignedByte())
            4 -> inp.skipBytes(16)
        }
        inp.skipBytes(2)
    }

    private fun skipHeaders(input: InputStream) {
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return
        }
    }
}
