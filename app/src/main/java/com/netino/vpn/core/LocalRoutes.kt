package com.netino.vpn.core

import com.netino.vpn.service.XrayVpnService
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Other ways out for the app's own requests (subscriptions, WARP's API) when the direct one is filtered.
 * The app is excluded from its own VPN, so "through the tunnel" goes via the core's local SOCKS inbound.
 */
object LocalRoutes {

    /** The running tunnel's probe inbound, only while the tunnel is really up (a stale endpoint would just time out). */
    fun tunnel(): HevTunnel.Endpoint? = XrayVpnService.probe?.takeIf { XrayVpnService.isRunning && XrayCore.isRunning }

    /** Runs [block] with a throw-away core whose only outbound fragments the TLS ClientHello (the firewall can't read the name). */
    fun <T> withFragmentCore(block: (HevTunnel.Endpoint) -> T?): T? {
        val ep = HevTunnel.newEndpoint()
        val config = """{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":${ep.port},"protocol":"socks",""" +
            """"settings":{"auth":"password","accounts":[{"user":"${ep.user}","pass":"${ep.pass}"}],"udp":false}}],""" +
            """"outbounds":[{"protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"10-30","interval":"10-20"}}}]}"""
        val core = runCatching { XrayCore.startTestCore(config) }.getOrNull() ?: return null
        return try { block(ep) } finally { XrayCore.stopTestCore(core) }
    }

    /**
     * [base] with every connection made through the local SOCKS [ep]. Names are resolved at the far end
     * (a poisoned local DNS answer doesn't matter): the placeholder address only carries the host name along.
     */
    fun via(base: OkHttpClient, ep: HevTunnel.Endpoint): OkHttpClient = base.newBuilder()
        .socketFactory(SocksFactory(ep))
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        })
        .build()

    private class SocksFactory(private val ep: HevTunnel.Endpoint) : SocketFactory() {
        override fun createSocket(): Socket = SocksSocket(ep)
        override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int) = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int) = createSocket(address, port)
    }

    private class SocksSocket(private val ep: HevTunnel.Endpoint) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as InetSocketAddress
            super.connect(InetSocketAddress("127.0.0.1", ep.port), timeout)
            try {
                soTimeout = 15_000
                SpeedProbe.socks5Connect(this, ep.user, ep.pass, target.hostString, target.port)
                soTimeout = 0
            } catch (e: Exception) {
                close()
                throw e as? IOException ?: IOException("SOCKS: ${e.message}", e)
            }
        }
    }
}
