package com.netino.vpn.service

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.annotation.StringRes
import com.netino.vpn.R
import com.netino.vpn.core.HevTunnel
import com.netino.vpn.core.Pinger
import com.netino.vpn.data.TunEngine
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.NetKey
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.core.BatchTester
import com.netino.vpn.core.CdnDetector
import com.netino.vpn.core.Warp
import com.netino.vpn.core.NetReport
import com.netino.vpn.core.WireGuardCore
import com.netino.vpn.data.PingKind
import com.netino.vpn.core.SpeedProbe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import com.netino.vpn.core.TlsPin
import com.netino.vpn.core.NationalNet
import com.netino.vpn.core.NetinoApi
import com.netino.vpn.core.XrayConfigBuilder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
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
    /** When the last automatic re-test ran, and how many in a row found nothing better (each doubles the wait). */
    @Volatile private var lastAutoSwitch = 0L
    @Volatile private var autoSwitchMisses = 0
    private const val AUTO_SWITCH_GAP_MS = 5 * 60_000L
    private const val AUTO_SWITCH_CANDIDATES = 40

    /** Bumped by every connect: a cancelled one only cleans up if no newer connect has started since. */
    @Volatile private var connectGen = 0
    @Volatile private var engineUp = false
    /** Servers of the current connection, for reconnecting after a network change. */
    @Volatile private var lastPool: List<Server> = emptyList()
    private var networkJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        ScreenState.start(app)
        XrayCore.onStatus = { msg -> logText("Xray: $msg") }
        // A WARP account no server uses any more is unregistered (frees a WARP+ device slot too)
        Repository.onServersRemoved = { removed, left ->
            // An account the running tunnel uses stays registered: unregistering it would cut the connection
            val used = (left + removed.filter(BatchTester::inUse)).flatMapTo(mutableSetOf()) { it.warpRegs }
            val gone = removed.flatMap { it.warpRegs }.distinct().filter { it !in used }
            if (gone.isNotEmpty()) scope.launch { Warp.unregister(gone, Repository.settings.value) }
        }
        // Netino's service: its address, and yesterday's anonymous results of the free list (if the user agreed)
        scope.launch { delay(20_000); NetinoApi.discover(); reportResults() }
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
    private suspend fun connectPool(requested: List<Server>): Boolean {
        val pool = onePerKey(requested)
        val first = pool.firstOrNull() ?: run { _state.value = VpnState.Error("no_server"); return false }
        Repository.select(first.id)
        _state.value = VpnState.Connecting(first)
        val gen = ++connectGen
        return try {
            // Certificates of allowInsecure configs (pinned in the config): all at once and capped. Read one after
            // another inside the service, a few blocked servers took longer than the start timeout (MCI).
            withTimeoutOrNull(8_000) { coroutineScope { pool.map { async(Dispatchers.IO) { TlsPin.forServer(it) } }.awaitAll() } }
            XrayVpnService.pending = pool
            XrayVpnService.start(app)
            XrayVpnService.awaitStarted()
            engineUp = true
            lastPool = pool
            _state.value = VpnState.Connected(first, System.currentTimeMillis(), pool.size)
            if (pool.size > 1) log(R.string.log_connected_auto, pool.size, first.name) else log(R.string.log_connected, first.name)
            NetReport.add("Connected: ${first.name}" + if (pool.size > 1) " (auto, ${pool.size} servers)" else "")
            startMonitors()
            true
        } catch (e: TimeoutCancellationException) {
            // The service didn't come up in time: a failure like any other, not a silent cancellation
            // (that left the screen on "Connecting…" for good, with nothing in the report)
            log(R.string.log_error, "VPN start timed out")
            NetReport.add("Connect failed (${first.name}): the VPN service didn't start within 25 s")
            stopEngines()
            _state.value = VpnState.Error("timeout")
            false
        } catch (e: CancellationException) {
            // Stopped by the user (or replaced by a newer connect, which then owns the state): never leave "Connecting…"
            if (gen == connectGen && _state.value is VpnState.Connecting) {
                stopEngines()
                _state.value = VpnState.Disconnected
            }
            throw e
        } catch (e: Throwable) {
            log(R.string.log_error, e.message ?: e.javaClass.simpleName)
            NetReport.add("Connect failed (${first.name}): ${e.message ?: e.javaClass.simpleName}")
            stopEngines()
            _state.value = VpnState.Error(e.message ?: "error")
            false
        }
    }

    /**
     * Keeps the first server of each WireGuard key: Cloudflare allows one live session per key, so two servers
     * of one WARP account (WARP in WARP: either hop's key) in the balancer would cut each other off.
     */
    private fun onePerKey(servers: List<Server>): List<Server> {
        val used = mutableSetOf<String>()
        return servers.filter { s -> WireGuardCore.keys(s).let { k -> k.none { it in used }.also { if (it) used += k } } }
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

    /** Re-applies settings that live in the tunnel's config (routing, sharing, WARP exit) by reconnecting. */
    fun applyNow() {
        if (_state.value !is VpnState.Connected || searchJob?.isActive == true) return
        val pool = lastPool.ifEmpty { return }
        connectJob?.cancel()
        connectJob = scope.launch { connectPool(pool) }
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

    fun cancelSearch() { searchJob?.cancel(); _search.value = null; _emergency.value = 0 }

    private val _emergency = MutableStateFlow(0)
    /** The running rescue's current step (1..[emergencySteps]), 0 = not running. */
    val emergency: StateFlow<Int> = _emergency.asStateFlow()
    private val _national = MutableStateFlow(false)
    /** The running rescue is the national-internet one (its steps differ). */
    val national: StateFlow<Boolean> = _national.asStateFlow()
    fun emergencySteps() = if (_national.value) NATIONAL_STEPS else EMERGENCY_STEPS
    const val EMERGENCY_STEPS = 4
    const val NATIONAL_STEPS = 5

    /**
     * "Nothing connects": every way in turn, each checked through the tunnel itself before it counts:
     *  1. quick connect after a fresh firewall check   2. WARP in WARP (saved, else new)
     *  3. a new plain WARP over IPv6                    4. a fresh copy of the free list, every server tested
     * Each step goes into the network report. [onDone]: whether something got through.
     */
    fun connectEmergency(onDone: (Boolean) -> Unit = {}) = rescue(national = false, onDone)

    /**
     * National internet (only Iranian sites reachable): servers inside Iran first, since behind a shutdown those
     * "bridges" with their own link abroad are usually all that works; then everything else, harder each time:
     *  1. servers inside Iran   2. every server after a fresh firewall check   3. every server with TLS fragmentation
     *  4. WARP in WARP           5. a new WARP over IPv6
     */
    fun connectNational(onDone: (Boolean) -> Unit = {}) = rescue(national = true, onDone)

    private fun rescue(national: Boolean, onDone: (Boolean) -> Unit) {
        if (searchJob?.isActive == true) return
        _national.value = national
        searchJob = scope.launch {
            val ok = try { if (national) nationalSteps() else emergency() } finally { _emergency.value = 0; _search.value = null }
            onDone(ok)
        }
    }

    private suspend fun works(name: String, step: Int, connected: Boolean): Boolean {
        val ok = connected && tunnelAnswers()
        NetReport.add("$name step $step: " + if (ok) "connected, tunnel answers" else "didn't get through")
        return ok
    }

    /** Don't leave a tunnel that doesn't carry anything. */
    private fun dropDeadTunnel() {
        if (_state.value is VpnState.Connected) {
            monitorJob?.cancel()
            stopEngines()
            _state.value = VpnState.Disconnected
            _traffic.value = Traffic()
        }
    }

    private suspend fun emergency(): Boolean {
        NetReport.add("Emergency mode started")
        _emergency.value = 1
        Repository.updateSettings { it.copy(cdnCheckedAt = it.cdnCheckedAt - NetKey.current) }   // check the firewall again now
        if (works("Emergency", 1, searchAndConnect(Repository.fastestCandidates()))) return true
        _emergency.value = 2
        if (works("Emergency", 2, warp(Warp.Mode.WARP_IN_WARP, null, fresh = false, connect = true).let { it == null || it == WarpFailure.PlainOnly })) return true
        _emergency.value = 3
        if (works("Emergency", 3, warp(Warp.Mode.WARP, ipv6 = true, fresh = true, connect = true) == null)) return true
        _emergency.value = 4
        Repository.refreshSubscription(Repository.BUILTIN_SUB_ID)
        if (works("Emergency", 4, searchAndConnect(Repository.servers.value))) return true
        dropDeadTunnel()
        return false
    }

    private suspend fun nationalSteps(): Boolean {
        val all = Repository.servers.value.filter { it.subscriptionId != Warp.SUB }.sortedBy { it.score ?: Long.MAX_VALUE }
        NetReport.add("National internet mode started (${NationalNet.check().name.lowercase()} network, ${all.size} servers)")
        _emergency.value = 1
        val bridges = NationalNet.bridges(all)
        NetReport.add("National: ${bridges.size} servers inside Iran" + if (!NationalNet.canClassify()) " (Iran lists not downloaded: can't tell)" else "")
        if (bridges.isNotEmpty() && works("National", 1, searchAndConnect(bridges))) return true
        _emergency.value = 2
        Repository.updateSettings { it.copy(cdnCheckedAt = it.cdnCheckedAt - NetKey.current) }
        if (works("National", 2, searchAndConnect(all))) return true
        _emergency.value = 3
        // Fragmentation for every TLS config; kept if it is what got through, otherwise the user's choice comes back
        val before = Repository.settings.value.fragmentMode
        Repository.updateSettings { it.copy(fragmentMode = com.netino.vpn.data.FragmentMode.ALWAYS) }
        if (works("National", 3, searchAndConnect(all))) return true
        Repository.updateSettings { it.copy(fragmentMode = before) }
        _emergency.value = 4
        if (works("National", 4, warp(Warp.Mode.WARP_IN_WARP, null, fresh = false, connect = true).let { it == null || it == WarpFailure.PlainOnly })) return true
        _emergency.value = 5
        if (works("National", 5, warp(Warp.Mode.WARP, ipv6 = true, fresh = true, connect = true) == null)) return true
        dropDeadTunnel()
        return false
    }

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
        mode: Warp.Mode? = null, ipv6: Boolean? = null, fresh: Boolean = false, connect: Boolean = true,
        onDone: (WarpFailure?) -> Unit = {},
    ) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            val r = try {
                // No mode asked (home button, widget): the battery saver tries plain WARP first, WARP in WARP if that fails
                val saver = Repository.settings.value.batterySaver
                val first = mode ?: if (saver) Warp.Mode.WARP else Warp.Mode.WARP_IN_WARP
                val r1 = warp(first, ipv6, fresh, connect)
                // (an account that couldn't be made won't be made for WARP in WARP either)
                if (mode == null && saver && (r1 == WarpFailure.NoEndpoint || r1 == WarpFailure.Connect)) warp(Warp.Mode.WARP_IN_WARP, ipv6, fresh, connect) else r1
            } finally { _search.value = null }
            onDone(r)
        }
    }

    /** WARP in WARP first; with the battery saver plain WARP first (one WireGuard layer instead of two: less CPU and heat). */
    private fun warpOrder(l: List<Server>) = l.sortedWith(compareBy<Server>(
        { (it.wgOuter == null) == !Repository.settings.value.batterySaver }, { it.score ?: Long.MAX_VALUE }))

    private suspend fun warp(mode: Warp.Mode, ipv6: Boolean?, fresh: Boolean, connect: Boolean): WarpFailure? {
        val saved = warpOrder(Repository.servers.value.filter { it.subscriptionId == Warp.SUB })
        if (saved.isNotEmpty() && !fresh) {
            // No waiting: up within a second or two; the balancer moves traffic off a dead one by itself
            if (connect && !connectLive(saved)) return WarpFailure.Connect
            _search.value = null   // connected: the waiting popup closes, the check runs quietly
            // Servers sharing a key with the tunnel can't be tested beside it: the tunnel's own probe checks those
            val live = checkWarp(saved)
            if (!connect) {
                if (live.isNotEmpty()) return null
            } else {
                val up = tunnelAnswers()
                NetReport.add("WARP instant connect with saved servers: tunnel " + if (up) "answers" else "doesn't answer")
                if (up) return null
                // The tunnel doesn't answer: move to the servers of other accounts that do
                val others = live.filterNot(BatchTester::inUse)
                if (others.isNotEmpty()) { connectLive(warpOrder(others)); return null }
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
            NetReport.add("WARP failed: ${e.message ?: e.javaClass.simpleName}")
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

    /**
     * Tests WARP servers, records the delays and deletes the ones that didn't answer; returns the live ones, fastest first.
     * Servers with a key the tunnel uses are not tested (that would cut the tunnel off): they are kept and come last.
     */
    suspend fun checkWarp(saved: List<Server>): List<Server> {
        val results = BatchTester.raw(saved, Repository.settings.value)
        results.forEach { Repository.setPing(it.server.id, it.ms, PingKind.REAL) }
        val dead = results.filter { it.ms <= 0 }.map { it.server.id }
        Repository.deleteServers(dead)
        if (dead.isNotEmpty()) log(R.string.log_warp_removed, dead.size)
        val tested = results.mapTo(mutableSetOf()) { it.server.id }
        return results.filter { it.ms > 0 }.sortedBy { it.ms }.map { it.server } + saved.filter { it.id !in tested && BatchTester.inUse(it) }
    }

    /** Whether traffic gets through the running tunnel (its own probe inbound, so it follows the balancer). */
    private suspend fun tunnelAnswers(): Boolean = tunnelDelay() > 0

    /** Real delay through the running tunnel, -1 if it doesn't answer or isn't up. */
    suspend fun tunnelDelay(): Long = withContext(Dispatchers.IO) {
        val ep = XrayVpnService.probe ?: return@withContext -1L
        SpeedProbe.delay(ep.port, ep.user, ep.pass, Repository.settings.value.testUrl)
    }

    private suspend fun connectLive(live: List<Server>): Boolean {
        val pool = onePerKey(live).take(AUTO_POOL)
        _search.value = SearchProgress(3, pool.size, pool.size)
        return connectPool(pool)
    }

    /** Stages of [connectWarp]: 20 checking saved, 21 account, 22 scanning endpoints, 23 WARP in WARP. */
    const val STAGE_WARP_CHECK = 20

    private const val AUTO_POOL = 6
    private const val SEARCH_LIMIT_MS = 75_000L
    private const val SPEED_CHECKS = 4

    /** Sends the collected free-list results (at most once a day; the app is outside its own tunnel, so directly). */
    private suspend fun reportResults() = NetinoApi.reportResults(if (NetKey.current.startsWith("cell")) "cell" else "wifi")

    private suspend fun searchAndConnect(candidates: List<Server>): Boolean = try {
        // Stage 0: which way to Cloudflare this network's firewall allows (once per network, refreshed every few hours)
        if (CdnDetector.due(Repository.settings.value)) {
            _search.value = SearchProgress(0, 0, 0)
            CdnDetector.markCloudflare(candidates)
            val fresh = Repository.servers.value.associateBy { it.id }
            val r = CdnDetector.detect(candidates.mapNotNull { fresh[it.id] }, Repository.settings.value)
            if (r != null) {
                log(R.string.log_cdn_detected, r.method.name, r.working.filterValues { it > 0 }.entries.joinToString { "${it.key.name} ${it.value}/${r.tested}" })
                NetReport.add("Firewall check: ${r.method.name} chosen; " +
                    r.working.entries.joinToString { "${it.key.name} ${it.value}/${r.tested}" + (r.medianMs[it.key]?.let { ms -> " ${ms} ms" } ?: "") })
            } else NetReport.add(if (candidates.mapNotNull { fresh[it.id] }.none(XrayConfigBuilder::isCdn)) "Firewall check: no Cloudflare (CDN) config to test with"
                else "Firewall check: none of the Cloudflare configs got through with any method")
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
            val pool = onePerKey(fast + ranked.drop(SPEED_CHECKS)).take(AUTO_POOL).ifEmpty {
                log(R.string.log_speed_fallback, ranked.first().name)
                ranked.take(1)
            }
            _search.value = SearchProgress(3, pool.size, pool.size)
            connectPool(pool)
        }
    } finally {
        _search.value = null
        scope.launch { reportResults() }
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
            val failures = AtomicInteger()
            var healthJob: Job? = null
            var stalled = 0
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            var last = SystemClock.elapsedRealtime()
            var lastHealth = last
            var lastPersist = last
            var lastNotify = 0L
            var lastWidget = 0L

            while (isActive) {
                // Screen off: nobody watches the speed, so wake up rarely (each wake-up costs battery)
                val screenOn = ScreenState.on
                delay(if (screenOn) 1000 else 10_000)
                val now = SystemClock.elapsedRealtime()
                val secs = ((now - last) / 1000.0).coerceAtLeast(1.0)
                last = now
                // ---- live traffic ----
                val (rx, tx) = counters()
                val dRx = (rx - lastRx).coerceAtLeast(0); val dTx = (tx - lastTx).coerceAtLeast(0)
                lastRx = rx; lastTx = tx
                val down = (dRx / secs).toLong(); val up = (dTx / secs).toLong()
                _traffic.update { t ->
                    t.copy(downBps = down, upBps = up, sessionRx = t.sessionRx + dRx, sessionTx = t.sessionTx + dTx,
                        history = (t.history + (down to up)).takeLast(60))
                }
                Repository.addUsage(day.format(Date()), dRx, dTx)
                if (now - lastPersist >= 30_000) { lastPersist = now; Repository.persistUsage() }
                // Notification and widget only while someone can see them
                if (screenOn && now - lastNotify >= 2000) { lastNotify = now; ConnectionNotifier.update(app, _state.value, _traffic.value) }
                if (screenOn && now - lastWidget >= 5000) { lastWidget = now; runCatching { NetinoWidget.update(app) } }

                // ---- stall detection: apps keep sending but nothing comes back -> check right away ----
                stalled = if (screenOn && dTx > 2048 && dRx == 0L) stalled + 1 else 0
                val stallCheck = stalled >= 6
                if (stallCheck) { stalled = 0; log(R.string.log_stall) }

                // ---- health check (through the tunnel's own probe inbound, so it follows the balancer) ----
                // Each check wakes the mobile radio: with the screen off they're rare, the battery saver spaces them more
                val st = Repository.settings.value
                val every = st.healthIntervalSec.coerceAtLeast(5) * 1000L *
                    (if (st.batterySaver) 2 else 1) * (if (screenOn) 1 else if (st.batterySaver) 12 else 4)
                if ((stallCheck || now - lastHealth >= every) && healthJob?.isActive != true) {
                    lastHealth = now
                    healthJob = launch(Dispatchers.IO) {
                        val ep = XrayVpnService.probe
                        val healthy = if (ep != null) SpeedProbe.delay(ep.port, ep.user, ep.pass, st.testUrl, tries = 1) > 0
                        else XrayCore.measureRunning(st.testUrl) > 0
                        val n = if (healthy) { failures.set(0); 0 } else failures.incrementAndGet()
                        if (!healthy) log(R.string.log_health_fail, n)
                        // A full re-test is the heaviest thing the app does (a core with dozens of servers, TLS handshakes,
                        // speed tests): spaced out, and further each time it finds nothing better, so a network where
                        // nothing works doesn't keep the CPU busy around the clock
                        val gap = minOf(AUTO_SWITCH_GAP_MS shl autoSwitchMisses.coerceAtMost(4), 60 * 60_000L) * (if (ScreenState.on) 1 else 4)
                        if (st.autoSwitch && n >= st.autoSwitchFailures && searchJob?.isActive != true &&
                            SystemClock.elapsedRealtime() - lastAutoSwitch >= gap) {
                            lastAutoSwitch = SystemClock.elapsedRealtime()
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
        // The best-known servers only (by stability score): enough to find a working one, a fraction of the work
        val pool = Repository.fastestCandidates().filter { it.id != current?.id }
            .ifEmpty { Repository.servers.value.filter { it.id != current?.id } }
            .sortedBy { it.score ?: Long.MAX_VALUE }.take(AUTO_SWITCH_CANDIDATES)
        if (searchAndConnect(pool)) {
            autoSwitchMisses = 0
            (state.value as? VpnState.Connected)?.server?.let { log(R.string.log_auto_switch, it.name) }
        } else {
            autoSwitchMisses++
            log(R.string.log_no_better)
            startMonitors()
        }
    }
}
