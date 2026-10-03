package com.netino.vpn.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.netino.vpn.core.HevTunnel
import com.netino.vpn.core.TlsPin
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
        @Volatile var pending: Server? = null
        @Volatile var engine: TunEngine? = null
            private set
        /** Local SOCKS inbound reserved for [com.netino.vpn.core.SpeedProbe] while connected. */
        @Volatile var probe: HevTunnel.Endpoint? = null
            private set
        @Volatile private var instance: XrayVpnService? = null
        @Volatile private var stopRequested = false
        private var started = CompletableDeferred<Unit>()

        /**
         * The intent carries the VpnService action so it matches the service's intent filter
         * (Android 15+/16 "safer intents" reject explicit intents that don't match a declared filter).
         */
        fun start(context: Context) {
            stopRequested = false
            ContextCompat.startForegroundService(context, Intent(context, XrayVpnService::class.java).setAction(VpnService.SERVICE_INTERFACE))
        }

        /** Stops without sending an intent (starting a service from the background can throw). */
        fun stop() {
            val svc = instance
            if (svc != null) svc.requestStop() else stopRequested = true
        }

        val isRunning get() = instance != null

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

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    fun requestStop() = runCatching { worker.execute { shutdown() } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() obliges us to call startForeground() first, even when stopping right away
        val notification = ConnectionNotifier.build(this, VpnController.state.value, VpnController.traffic.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ConnectionNotifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ConnectionNotifier.ID, notification)
        }
        if (stopRequested) {
            stopRequested = false
            requestStop()
            return START_NOT_STICKY
        }
        // pending == null: started by the system (always-on VPN), not by the app
        val requested = pending
        pending = null
        val server = requested ?: Repository.selectedServer()
        if (server == null) { requestStop(); return START_NOT_STICKY }
        worker.execute {
            runCatching { startTunnel(server) }
                .onSuccess {
                    started.complete(Unit)
                    if (requested == null) VpnController.onSystemStart(server)
                }
                .onFailure { started.completeExceptionally(it); shutdown() }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(server: Server) {
        val s = Repository.settings.value
        val useHev = s.tunEngine == TunEngine.HEV && HevTunnel.isSupported
        val endpoint = if (useHev) HevTunnel.newEndpoint() else null
        val probeEp = HevTunnel.newEndpoint()
        val config = XrayConfigBuilder.build(
            server, s,
            if (endpoint != null) XrayConfigBuilder.Inbound.Socks(endpoint.port, endpoint.user, endpoint.pass) else XrayConfigBuilder.Inbound.Tun,
            hasGeoFiles = XrayCore.hasGeoFiles,
            certPin = TlsPin.forServer(server),
            probe = XrayConfigBuilder.Inbound.Socks(probeEp.port, probeEp.user, probeEp.pass),
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
        probe = probeEp
        tun = newTun
        runCatching { old?.close() }
    }

    private fun shutdown() {
        HevTunnel.stop()
        XrayCore.stop()
        runCatching { tun?.close() }
        tun = null
        engine = null
        probe = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission from system settings
        VpnController.disconnect()
        requestStop()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        probe = null
        HevTunnel.stop()
        XrayCore.stop()
        runCatching { tun?.close() }
        engine = null
        worker.shutdown()
        super.onDestroy()
    }
}
