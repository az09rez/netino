package com.netino.vpn.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.netino.vpn.core.HevTunnel
import com.netino.vpn.core.XrayConfigBuilder
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.data.SplitMode
import com.netino.vpn.data.TunEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Owns the TUN interface for Xray based servers.
 *  • HEV engine (default): TUN fd → hev-socks5-tunnel → authenticated local SOCKS5 → Xray
 *  • XRAY engine: TUN fd handed directly to Xray's `tun` inbound
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        @Volatile var pending: Server? = null
        @Volatile var engine: TunEngine? = null
            private set
        private var started = CompletableDeferred<Unit>()

        suspend fun awaitStarted() {
            val d = started
            try {
                withTimeout(25_000) { d.await() }
            } finally {
                started = CompletableDeferred()
            }
        }
    }

    // Core start can take a moment: never block the main thread
    private val worker = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { worker.execute { shutdown() }; return START_NOT_STICKY }
            else -> {
                val notification = ConnectionNotifier.build(this, VpnController.state.value, VpnController.traffic.value)
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(ConnectionNotifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else startForeground(ConnectionNotifier.ID, notification)
                val server = pending ?: Repository.selectedServer()
                if (server == null) { worker.execute { shutdown() }; return START_NOT_STICKY }
                worker.execute {
                    runCatching { startTunnel(server) }
                        .onSuccess { started.complete(Unit) }
                        .onFailure { started.completeExceptionally(it); shutdown() }
                }
            }
        }
        return START_STICKY
    }

    private fun startTunnel(server: Server) {
        val s = Repository.settings.value
        val useHev = s.tunEngine == TunEngine.HEV && HevTunnel.isSupported
        val endpoint = if (useHev) HevTunnel.newEndpoint() else null
        val config = XrayConfigBuilder.build(
            server, s,
            if (endpoint != null) XrayConfigBuilder.Inbound.Socks(endpoint.port, endpoint.user, endpoint.pass) else XrayConfigBuilder.Inbound.Tun,
            hasGeoFiles = XrayCore.hasGeoFiles,
        )

        val b = Builder()
            .setSession(server.name)
            .setMtu(1500)
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")   // intercepted by Xray and resolved with DoH inside the tunnel
        // IPv6: route everything into the tunnel so no IPv6 packet can leave the device outside the VPN.
        b.addAddress("fd66:6f:6e::1", 126).addRoute("::", 0)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        if (Build.VERSION.SDK_INT >= 33 && s.bypassLan) {
            listOf("10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16, "169.254.0.0" to 16)
                .forEach { (ip, len) -> b.excludeRoute(IpPrefix(InetAddress.getByName(ip), len)) }
        }

        // ---- per-app split tunnelling ----
        val sp = s.split
        when (sp.appMode) {
            SplitMode.ONLY -> sp.apps.filter { it != packageName }.forEach { runCatching { b.addAllowedApplication(it) } }
            else -> {
                b.addDisallowedApplication(packageName)   // core's own sockets must not loop into the tunnel
                if (sp.appMode == SplitMode.BYPASS) sp.apps.forEach { runCatching { b.addDisallowedApplication(it) } }
            }
        }

        val newTun = b.establish() ?: error("VPN permission revoked")
        // Seamless switch: the new interface is already up, so swap the engines and only then close the old fd.
        val old = tun
        HevTunnel.stop()
        try {
            if (endpoint != null) {
                XrayCore.start(config, 0)                       // SOCKS inbound only
                HevTunnel.start(this, newTun.fd, endpoint)
            } else {
                XrayCore.start(config, newTun.fd)               // Xray reads the TUN itself
            }
        } catch (e: Exception) {
            runCatching { newTun.close() }
            throw e
        }
        engine = if (useHev) TunEngine.HEV else TunEngine.XRAY
        tun = newTun
        runCatching { old?.close() }
    }

    private fun shutdown() {
        HevTunnel.stop()
        XrayCore.stop()
        runCatching { tun?.close() }
        tun = null
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission from system settings
        VpnController.disconnect()
        worker.execute { shutdown() }
    }

    override fun onDestroy() {
        HevTunnel.stop()
        XrayCore.stop()
        runCatching { tun?.close() }
        engine = null
        worker.shutdown()
        super.onDestroy()
    }
}
