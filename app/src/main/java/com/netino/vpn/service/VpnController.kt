package com.netino.vpn.service

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.netino.vpn.R
import com.netino.vpn.core.HevTunnel
import com.netino.vpn.core.Pinger
import com.netino.vpn.data.TunEngine
import com.netino.vpn.core.WireGuardCore
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface VpnState {
    data object Disconnected : VpnState
    data class Connecting(val server: Server) : VpnState
    data class Connected(val server: Server, val since: Long) : VpnState
    data class Error(val message: String) : VpnState
}

data class Traffic(
    val downBps: Long = 0, val upBps: Long = 0,
    val sessionRx: Long = 0, val sessionTx: Long = 0,
    val history: List<Pair<Long, Long>> = emptyList(),   // last 60 s of (down, up) speeds
)

data class LogEvent(val time: Long, val text: String)

/** Progress of a "fastest server" search: stage 1 = quick latency of all, stage 2 = real delay of the shortlist. */
data class SearchProgress(val stage: Int, val done: Int, val total: Int)

/**
 * Orchestrates both engines, one-tap switching, live traffic, health monitoring and auto-failover.
 * The VPN permission (VpnService.prepare) must already be granted by the UI.
 */
object VpnController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val _log = MutableStateFlow<List<LogEvent>>(emptyList())
    val log: StateFlow<List<LogEvent>> = _log.asStateFlow()

    private val _search = MutableStateFlow<SearchProgress?>(null)
    val search: StateFlow<SearchProgress?> = _search.asStateFlow()

    private var monitorJob: Job? = null
    private var searchJob: Job? = null
    private var activeEngine: Protocol? = null

    fun init(context: Context) {
        app = context.applicationContext
        XrayCore.onStatus = { msg -> logText("Xray: $msg") }
    }

    private fun log(@StringRes res: Int, vararg args: Any) = logText(app.getString(res, *args))

    /** Raw line for the live report (diagnostics: core errors, failed tests). */
    fun logText(text: String) =
        _log.update { (it + LogEvent(System.currentTimeMillis(), text)).takeLast(300) }

    /** Records a failed real-delay test so the reason is visible in the live report. */
    fun reportTestError(r: Pinger.Result) {
        if (r.ms <= 0 && r.error != null) logText("✗ ${r.server.name}: ${r.error}")
    }

    val isActive get() = _state.value is VpnState.Connected || _state.value is VpnState.Connecting

    fun toggle() = if (isActive) disconnect() else connect()

    /** Connect to [server] (or the selected one). If already connected, this is a seamless switch. */
    fun connect(server: Server? = Repository.selectedServer()) {
        if (server == null) { _state.value = VpnState.Error("no_server"); return }
        Repository.select(server.id)
        _state.value = VpnState.Connecting(server)
        scope.launch {
            runCatching { startEngine(server) }
                .onSuccess {
                    _state.value = VpnState.Connected(server, System.currentTimeMillis())
                    log(R.string.log_connected, server.name)
                    startMonitors()
                }
                .onFailure {
                    log(R.string.log_error, it.message ?: it.javaClass.simpleName)
                    stopEngines()
                    _state.value = VpnState.Error(it.message ?: "error")
                }
        }
    }

    fun disconnect() {
        monitorJob?.cancel()
        searchJob?.cancel(); _search.value = null
        scope.launch {
            stopEngines()
            Repository.persistUsage()
            _state.value = VpnState.Disconnected
            _traffic.value = Traffic()
            log(R.string.log_disconnected)
        }
    }

    /**
     * Measures every server from this device (quick latency, then real delay of the shortlist)
     * and connects to the winner. Progress is exposed through [search].
     */
    fun connectFastest(onNone: () -> Unit = {}) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            val best = runSearch(Repository.servers.value)
            if (best != null) connect(best) else onNone()
        }
    }

    fun cancelSearch() { searchJob?.cancel(); _search.value = null }

    private suspend fun runSearch(servers: List<Server>): Server? = try {
        _search.value = SearchProgress(1, 0, servers.size)
        Pinger.findFastest(
            servers, Repository.settings.value,
            onStage = { st, d, t -> _search.value = SearchProgress(st, d, t) },
            onResult = { Repository.setPing(it.server.id, it.ms, it.kind); reportTestError(it) },
        )
    } finally {
        Repository.saveServers()
        _search.value = null
    }

    // ---------------- engines ----------------
    private suspend fun startEngine(server: Server) {
        val s = Repository.settings.value
        if (server.protocol.usesXray) {
            if (activeEngine == Protocol.WIREGUARD) stopEngines()
            // XrayVpnService re-establishes the tun before tearing the old one down => no leak while switching
            XrayVpnService.pending = server
            ContextCompat.startForegroundService(app, Intent(app, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START))
            XrayVpnService.awaitStarted()
            activeEngine = server.protocol
        } else {
            if (activeEngine != null && activeEngine != Protocol.WIREGUARD) stopEngines()
            ContextCompat.startForegroundService(app, Intent(app, ConnectionKeeperService::class.java))
            WireGuardCore.start(app, server.wgConf!!, s)
            activeEngine = Protocol.WIREGUARD
            // UDP gives no error when a server is down, so confirm the handshake for the live report
            val hs = WireGuardCore.awaitHandshake()
            if (hs >= 0) log(R.string.log_wg_handshake, hs) else log(R.string.log_wg_no_handshake)
        }
    }

    private fun stopEngines() {
        if (XrayCore.isRunning || activeEngine?.usesXray == true) {
            app.startService(Intent(app, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
        }
        if (WireGuardCore.isUp) WireGuardCore.stop()
        app.stopService(Intent(app, ConnectionKeeperService::class.java))
        activeEngine = null
    }

    // ---------------- traffic + health ----------------
    private fun startMonitors() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val uid = Process.myUid()
            fun counters(): Pair<Long, Long> = when {
                activeEngine == Protocol.WIREGUARD -> WireGuardCore.stats()
                // hev counts exactly what apps send/receive through the TUN
                XrayVpnService.engine == TunEngine.HEV -> HevTunnel.stats() ?: (0L to 0L)
                else -> TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) to TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            }

            var (lastRx, lastTx) = counters()
            var tick = 0
            var failures = 0
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            while (isActive) {
                delay(1000)
                tick++
                // ---- live traffic (1 s resolution) ----
                val (rx, tx) = counters()
                val dRx = (rx - lastRx).coerceAtLeast(0); val dTx = (tx - lastTx).coerceAtLeast(0)
                lastRx = rx; lastTx = tx
                _traffic.update { t ->
                    t.copy(downBps = dRx, upBps = dTx, sessionRx = t.sessionRx + dRx, sessionTx = t.sessionTx + dTx,
                        history = (t.history + (dRx to dTx)).takeLast(60))
                }
                Repository.addUsage(day.format(Date()), dRx, dTx)
                if (tick % 30 == 0) Repository.persistUsage()
                if (tick % 2 == 0) ConnectionNotifier.update(app, _state.value, _traffic.value)

                // ---- health check + auto failover ----
                val st = Repository.settings.value
                if (tick % st.healthIntervalSec.coerceAtLeast(5) == 0) {
                    val healthy = if (activeEngine == Protocol.WIREGUARD) WireGuardCore.isHealthy()
                    else XrayCore.measureRunning(st.testUrl) > 0
                    failures = if (healthy) 0 else failures + 1
                    if (!healthy) log(R.string.log_health_fail, failures)
                    if (st.autoSwitch && failures >= st.autoSwitchFailures) {
                        failures = 0
                        autoSwitch()
                        return@launch
                    }
                }
            }
        }
    }

    /** Same algorithm as "connect to fastest", excluding the failing server. */
    private suspend fun autoSwitch() {
        val current = (state.value as? VpnState.Connected)?.server
        log(R.string.log_searching)
        val best = runSearch(Repository.servers.value.filter { it.id != current?.id })
        if (best != null) {
            log(R.string.log_auto_switch, best.name)
            connect(best)
        } else {
            log(R.string.log_no_better)
            startMonitors()
        }
    }
}
