package com.netino.vpn.core

import android.content.Context
import hev.htproxy.TProxyService
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * hev-socks5-tunnel (same TUN engine v2rayNG uses by default): reads packets from the VpnService fd
 * and forwards TCP/UDP to Xray's local SOCKS5 inbound. Runs on its own native thread.
 */
object HevTunnel {

    /** The prebuilt library targets Android 10+; also false if the native lib fails to load. */
    val isSupported: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= 29 && runCatching { TProxyService.TProxyIsRunning() }.isSuccess
    }

    const val TUN_IPV4 = "10.10.14.2"
    const val TUN_IPV6 = "fd66:6f:6e::2"

    /** Per-session SOCKS endpoint shared by Xray (inbound) and hev (client). */
    data class Endpoint(val port: Int, val user: String, val pass: String)

    fun newEndpoint(): Endpoint {
        val port = ServerSocket(0).use { it.localPort }
        return Endpoint(port, token(), token())
    }

    private fun token(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = SecureRandom()
        return (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun start(context: Context, fd: Int, ep: Endpoint, mtu: Int = 1500) {
        val yaml = """
            |tunnel:
            |  mtu: $mtu
            |  ipv4: $TUN_IPV4
            |  ipv6: '$TUN_IPV6'
            |socks5:
            |  port: ${ep.port}
            |  address: 127.0.0.1
            |  udp: 'udp'
            |  username: '${ep.user}'
            |  password: '${ep.pass}'
            |misc:
            |  tcp-read-write-timeout: 300000
            |  udp-read-write-timeout: 60000
            |  log-level: warn
            |""".trimMargin()
        // Config lives in no-backup storage and is overwritten every session (credentials are random)
        val file = File(context.noBackupFilesDir, "hev-socks5-tunnel.yaml").apply { writeText(yaml) }
        check(TProxyService.TProxyStartService(file.absolutePath, fd)) { "hev-socks5-tunnel failed to start" }
    }

    fun stop() {
        if (!isSupported) return
        runCatching { if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() }
    }

    /** (download, upload) bytes seen on the TUN: [tx_packets, tx_bytes, rx_packets, rx_bytes]; tx = written to apps. */
    fun stats(): Pair<Long, Long>? = if (!isSupported) null else runCatching {
        val s = TProxyService.TProxyGetStats() ?: return null
        s[1] to s[3]
    }.getOrNull()
}
