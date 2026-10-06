package com.netino.vpn.service

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import androidx.annotation.StringRes
import com.netino.vpn.R
import com.netino.vpn.core.HevTunnel
import com.netino.vpn.core.Pinger
import com.netino.vpn.data.TunEngine
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.core.BatchTester
import com.netino.vpn.core.CdnDetector
import com.netino.vpn.core.Warp
import com.netino.vpn.data.PingKind
import com.netino.vpn.core.SpeedProbe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /** [pool] > 1: auto mode, the balancer picks among that many servers ([server] = best at connect time). */
    data class Connected(val server: Server, val since: Long, val pool: Int = 1) : VpnState
    data class Error(val message: String) : VpnState
}

data class Traffic(
    val downBps: Long = 0, val upBps: Long = 0,
    val sessionRx: Long = 0, val sessionTx: Long = 0,
    val history: List<Pair<Long, Long>> = emptyList(),   // last 60 s of (down, up) speeds
)

data class LogEvent(val time: Long, val text: String)

/**
 * Progress of a "fastest server" search: stage 1 = real delay of all candidates ([done] of [total]),
 * stage 2 = download speed of candidate [done] of [total] ([name]), stage 3 = connecting with [total] servers.
 */
data class SearchProgress(val stage: Int, val done: Int, val total: Int, val name: String? = null)

/**
 * Orchestrates both engines, one-tap switching, live traffic, health monitoring and auto-failover.
 * The VPN permission (VpnService.prepare) must already be granted by the UI.
 */
object VpnController {

    // An exception in a background job is logged instead of crashing the whole app
    private val errors = CoroutineExceptionHandler { _, e -> logText("Error: ${e.javaClass.simpleName}: ${e.message}") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
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
    private var connectJob: Job? = null
    @Volatile private var engineUp = false
    /** Servers of the current connection, for reconnecting after a network change. */
    @Volatile private var lastPool: List<Server> = emptyList()
    private var networkJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        XrayCore.onStatus = { msg -> logText("Xray: $msg") }
        // Home-screen widget follows the connection state
        scope.launch { _state.collect { runCatching { NetinoWidget.update(app) } } }
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
        cancelSearch()
        connectJob = scope.launch { connectPool(listOfNotNull(server)) }
    }

    /**
     * Starts the tunnel with one server (plain) or several (auto mode: Xray's balancer keeps
     * traffic on the best of them and moves away from a dying one without reconnecting).
     * Every protocol, WireGuard included, runs in the same Xray engine. Returns true once connected.
     */
    private suspend fun connectPool(pool: List<Server>): Boolean {
        val first = pool.firstOrNull() ?: run { _state.value = VpnState.Error("no_server"); return false }
        Repository.select(first.id)
        _state.value = VpnState.Connecting(first)
        return try {
            XrayVpnService.pending = pool
            XrayVpnService.start(app)
            XrayVpnService.awaitStarted()
            engineUp = true
            lastPool = pool
            _state.value = VpnState.Connected(first, System.currentTimeMillis(), pool.size)
            if (pool.size > 1) log(R.string.log_connected_auto, pool.size, first.name) else log(R.string.log_connected, first.name)
            startMonitors()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(R.string.log_error, e.message ?: e.javaClass.simpleName)
            stopEngines()
            _state.value = VpnState.Error(e.message ?: "error")
            false
        }
    }

    /** The system started the tunnel itself (always-on VPN): show it as connected and monitor it. */
    fun onSystemStart(server: Server) {
        if (_state.value is VpnState.Connected) return
        engineUp = true
        _state.value = VpnState.Connected(server, System.currentTimeMillis())
        log(R.string.log_connected, server.name)
        startMonitors()
    }

    /**
     * Wi-Fi <-> mobile data (or another operator): the tunnel's sockets belong to the old network, so
     * reconnect with the same servers (in auto mode the balancer then re-measures them on the new one).
     */
    fun onNetworkChanged(from: String, to: String) {
        if (_state.value !is VpnState.Connected || !Repository.settings.value.reconnectOnNetworkChange) return
        val pool = lastPool.ifEmpty { return }
        networkJob?.cancel()
        networkJob = scope.launch {
            delay(2000)   // let the new network settle (DHCP, DNS)
            if (_state.value !is VpnState.Connected) return@launch
            log(R.string.log_network_changed, from, to)
            cancelSearch()
            connectPool(pool)
        }
    }

    fun disconnect() {
        networkJob?.cancel()
        monitorJob?.cancel()
        cancelSearch()
        connectJob?.cancel()
        scope.launch {
            stopEngines()
            Repository.persistUsage()
            _state.value = VpnState.Disconnected
            _traffic.value = Traffic()
            log(R.string.log_disconnected)
        }
    }

    /**
     * "Connect to fastest" within the chosen scope (all / a subscription / a group):
     *  1. real delay of every candidate, batch-tested in one core (seconds, not minutes)
     *  2. ranking by stability score (median delay + spread + failures over the last tests)
     *  3. download speed of the best few, measured BEFORE connecting; no-speed servers are left out
     *  4. connect in auto mode with the best [AUTO_POOL] servers
     */
    fun connectFastest(onNone: () -> Unit = {}) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            if (!searchAndConnect(Repository.fastestCandidates())) onNone()
        }
    }

    fun cancelSearch() { searchJob?.cancel(); _search.value = null }

    /** How a WARP connect ended; null = connected as asked. */
    sealed interface WarpFailure {
        data object NoEndpoint : WarpFailure
        data class Account(val message: String) : WarpFailure
        data object Connect : WarpFailure
        /** Connected, but with plain WARP: WARP in WARP didn't come up on this network. */
        data object PlainOnly : WarpFailure
    }

    /**
     * "Super-fast connect": WARP needs no config from anywhere.
     *  - Saved WARP servers: connects to them *at once* (auto mode, WARP in WARP first), then checks them
     *    in the background; dead ones are deleted, and only if none answers new ones are made and used.
     *  - None saved: makes an account, scans endpoints on this network and connects to what it found.
     * [fresh]: always make new ones (the WARP sheet's button). [connect] false: only find and save.
     */
    fun connectWarp(
        mode: Warp.Mode = Warp.Mode.WARP_IN_WARP, ipv6: Boolean? = null, fresh: Boolean = false, connect: Boolean = true,
        onDone: (WarpFailure?) -> Unit = {},
    ) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            val r = try { warp(mode, ipv6, fresh, connect) } finally { _search.value = null }
            onDone(r)
        }
    }

    private fun warpOrder(l: List<Server>) = l.sortedWith(compareBy<Server>({ it.wgOuter == null }, { it.score ?: Long.MAX_VALUE }))

    private suspend fun warp(mode: Warp.Mode, ipv6: Boolean?, fresh: Boolean, connect: Boolean): WarpFailure? {
        val saved = warpOrder(Repository.servers.value.filter { it.subscriptionId == Warp.SUB })
        if (saved.isNotEmpty() && !fresh) {
            // No waiting: up within a second or two; the balancer moves traffic off a dead one by itself
            if (connect && !connectLive(saved)) return WarpFailure.Connect
            _search.value = null   // connected: the waiting popup closes, the check runs quietly
            val live = checkWarp(saved)
            if (live.isNotEmpty()) {
                // Reconnect only if every server we connected with turned out dead
                if (connect && live.none { l -> saved.take(AUTO_POOL).any { it.id == l.id } }) connectLive(warpOrder(live))
                return null
            }
            log(R.string.log_warp_all_dead)
        } else if (saved.isNotEmpty()) {
            _search.value = SearchProgress(STAGE_WARP_CHECK, 0, saved.size)
            checkWarp(saved)
        }
        val found = try {
            Warp.create(mode, ipv6, Repository.settings.value) { st, d, t -> _search.value = SearchProgress(STAGE_WARP_CHECK + st, d, t) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(R.string.log_warp_failed, e.message ?: e.javaClass.simpleName)
            return WarpFailure.Account(e.message ?: e.javaClass.simpleName)
        }
        if (found.isEmpty()) return WarpFailure.NoEndpoint
        Repository.addServers(found)
        log(R.string.log_warp_added, found.size)
        val plainOnly = mode == Warp.Mode.WARP_IN_WARP && found.none { it.wgOuter != null }
        if (plainOnly) log(R.string.log_warp_plain_only)
        if (connect && !connectLive(found)) return WarpFailure.Connect
        return if (plainOnly) WarpFailure.PlainOnly else null
    }

    /** Tests WARP servers, records the delays and deletes the ones that didn't answer; returns the live ones, fastest first. */
    suspend fun checkWarp(saved: List<Server>): List<Server> {
        val results = BatchTester.raw(saved, Repository.settings.value)
        results.forEach { Repository.setPing(it.server.id, it.ms, PingKind.REAL) }
        val dead = results.filter { it.ms <= 0 }.map { it.server.id }
        Repository.deleteServers(dead)
        if (dead.isNotEmpty()) log(R.string.log_warp_removed, dead.size)
        return results.filter { it.ms > 0 }.sortedBy { it.ms }.map { it.server }
    }

    private suspend fun connectLive(live: List<Server>): Boolean {
        val pool = live.take(AUTO_POOL)
        _search.value = SearchProgress(3, pool.size, pool.size)
        return connectPool(pool)
    }

    /** Stages of [connectWarp]: 20 checking saved, 21 account, 22 scanning endpoints, 23 WARP in WARP. */
    const val STAGE_WARP_CHECK = 20

    private const val AUTO_POOL = 6
    private const val SEARCH_LIMIT_MS = 75_000L
    private const val SPEED_CHECKS = 4

    private suspend fun searchAndConnect(candidates: List<Server>): Boolean = try {
        // Stage 0: which way to Cloudflare this network's firewall allows (once per network, refreshed every few hours)
        if (CdnDetector.due(Repository.settings.value)) {
            _search.value = SearchProgress(0, 0, 0)
            CdnDetector.markCloudflare(candidates)
            val fresh = Repository.servers.value.associateBy { it.id }
            CdnDetector.detect(candidates.mapNotNull { fresh[it.id] }, Repository.settings.value)?.let { r ->
                log(R.string.log_cdn_detected, r.method.name, r.working.filterValues { it > 0 }.entries.joinToString { "${it.key.name} ${it.value}/${r.tested}" })
            }
        }
        val settings = Repository.settings.value
        _search.value = SearchProgress(1, 0, candidates.size)
        // Hard limit: whatever finished by then is used, so a stuck test can never block the search
        val ok = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val passed = try {
            withTimeoutOrNull(SEARCH_LIMIT_MS) {
                Pinger.rank(
                    Repository.servers.value.associateBy { it.id }.let { m -> candidates.map { m[it.id] ?: it } }, settings,
                    onStage = { st, d, t -> _search.value = SearchProgress(st, d, t) },
                    onResult = {
                        Repository.setPing(it.server.id, it.ms, it.kind); reportTestError(it)
                        if (it.ms > 0) ok[it.server.id] = it.ms
                    },
                )
            } ?: candidates.filter { ok.containsKey(it.id) }.sortedBy { ok[it.id] }.also { log(R.string.log_search_timeout, it.size) }
        } finally {
            Repository.saveServers()
        }
        if (passed.isEmpty()) false
        else {
            // Rank by stability (history now includes this run), not by one lucky sample
            val byId = Repository.servers.value.associateBy { it.id }
            val ranked = passed.mapNotNull { byId[it.id] }.sortedBy { it.score ?: Long.MAX_VALUE }
            val top = ranked.take(SPEED_CHECKS)
            val speeds = BatchTester.speeds(top, settings) { i, s -> _search.value = SearchProgress(2, i + 1, top.size, s.name) }
            top.forEach { s -> speeds[s.id]?.let { log(R.string.log_speed, s.name, it.kbps) } }
            // If the speed test itself couldn't run, don't punish the servers for it
            val fast = if (speeds.isEmpty()) top else top.filter { speeds[it.id]?.ok == true }
            (top - fast.toSet()).forEach { log(R.string.log_no_speed, it.name) }
            val pool = (fast + ranked.drop(SPEED_CHECKS)).take(AUTO_POOL).ifEmpty {
                log(R.string.log_speed_fallback, ranked.first().name)
                ranked.take(1)
            }
            _search.value = SearchProgress(3, pool.size, pool.size)
            connectPool(pool)
        }
    } finally {
        _search.value = null
    }

    private fun stopEngines() {
        if (XrayVpnService.isRunning || XrayCore.isRunning || engineUp) XrayVpnService.stop()
        engineUp = false
    }

    // ---------------- traffic + health ----------------
    private fun startMonitors() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val uid = Process.myUid()
            fun counters(): Pair<Long, Long> = when {
                // hev counts exactly what apps send/receive through the TUN
                XrayVpnService.engine == TunEngine.HEV -> HevTunnel.stats() ?: (0L to 0L)
                else -> TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) to TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            }

            var (lastRx, lastTx) = counters()
            var tick = 0
            val failures = AtomicInteger()
            var healthJob: Job? = null
            var stalled = 0
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
                if (tick % 5 == 0) runCatching { NetinoWidget.update(app) }

                // ---- stall detection: apps keep sending but nothing comes back -> check right away ----
                stalled = if (dTx > 2048 && dRx == 0L) stalled + 1 else 0
                val stallCheck = stalled >= 6
                if (stallCheck) { stalled = 0; log(R.string.log_stall) }

                // ---- health check (through the tunnel's own probe inbound, so it follows the balancer) ----
                val st = Repository.settings.value
                if ((stallCheck || tick % st.healthIntervalSec.coerceAtLeast(5) == 0) && healthJob?.isActive != true) {
                    healthJob = launch(Dispatchers.IO) {
                        val ep = XrayVpnService.probe
                        val healthy = if (ep != null) SpeedProbe.delay(ep.port, ep.user, ep.pass, st.testUrl, tries = 1) > 0
                        else XrayCore.measureRunning(st.testUrl) > 0
                        val n = if (healthy) { failures.set(0); 0 } else failures.incrementAndGet()
                        if (!healthy) log(R.string.log_health_fail, n)
                        if (st.autoSwitch && n >= st.autoSwitchFailures && searchJob?.isActive != true) {
                            failures.set(0)
                            // Separate job: connecting restarts the monitors, which would cancel this one
                            searchJob = scope.launch { autoSwitch() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Every server in use stopped answering (in auto mode the balancer already skips single failures):
     * re-test the scope and reconnect. The failing server is left out when there was only one.
     */
    private suspend fun autoSwitch() {
        val current = (state.value as? VpnState.Connected)?.takeIf { it.pool <= 1 }?.server
        log(R.string.log_searching)
        val pool = Repository.fastestCandidates().filter { it.id != current?.id }
            .ifEmpty { Repository.servers.value.filter { it.id != current?.id } }
        if (searchAndConnect(pool)) {
            (state.value as? VpnState.Connected)?.server?.let { log(R.string.log_auto_switch, it.name) }
        } else {
            log(R.string.log_no_better)
            startMonitors()
        }
    }
}
