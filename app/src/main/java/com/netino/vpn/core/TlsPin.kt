package com.netino.vpn.core

import com.netino.vpn.data.Server
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * "allowInsecure" (accept any certificate) was removed from Xray 26 – configs that use it are rejected
 * outright. Its replacement is pinning: `pinnedPeerCertSha256` accepts exactly the certificate whose
 * SHA-256 matches. For such configs Netino reads the certificate the server presents (one TLS handshake,
 * no data sent) and pins it, so they connect like before. They are shown as "not secure" in the list.
 */
object TlsPin {

    private class Entry(val hash: String, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 30 * 60_000L

    /** Lower-case hex SHA-256 of the server's leaf certificate, or null if it can't be read. */
    fun forServer(s: Server): String? {
        val x = s.xray ?: return null
        if (x.security != "tls" || !x.allowInsecure) return null
        val sni = x.sni.ifBlank { x.host.ifBlank { s.address } }
        val key = "${s.address}|${s.port}|$sni"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.hash }
        val hash = fetch(s.address, s.port, sni, x.alpn) ?: return null
        cache[key] = Entry(hash, System.currentTimeMillis())
        return hash
    }

    private fun fetch(host: String, port: Int, sni: String, alpn: String): String? = runCatching {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        val raw = java.net.Socket().apply { soTimeout = 6000; connect(InetSocketAddress(host, port), 6000) }
        (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { tls ->
            tls.sslParameters = tls.sslParameters.apply {
                if (!isIpLiteral(sni)) serverNames = listOf(SNIHostName(sni))
                val protocols = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "h3" }
                if (protocols.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 29) applicationProtocols = protocols.toTypedArray()
            }
            tls.startHandshake()
            val leaf = tls.session.peerCertificates.first()
            MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private fun isIpLiteral(s: String) = s.contains(':') || s.all { it.isDigit() || it == '.' }
}
