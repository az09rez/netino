package com.netino.desktop

import com.netino.vpn.core.BatchTester
import com.netino.vpn.core.CdnDetector
import com.netino.vpn.core.HevTunnel
import com.netino.vpn.core.NationalNet
import com.netino.vpn.core.NetReport
import com.netino.vpn.core.Pinger
import com.netino.vpn.core.SpeedProbe
import com.netino.vpn.core.TlsPin
import com.netino.vpn.core.Warp
import com.netino.vpn.core.WireGuardCore
import com.netino.vpn.core.XrayConfigBuilder
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.FragmentMode
import com.netino.vpn.data.NetKey
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.data.WarpExit
import com.netino.vpn.service.XrayVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val server: Server) : ConnState
    data class Connected(val server: Server, val since: Long, val pool: Int = 1) : ConnState
    data class Error(val message: String) : ConnState
}

data class Traffic(val downBps: Long = 0, val upBps: Long = 0, val sessionRx: Long = 0, val sessionTx: Long = 0)

data class LogEvent(val time: Long, val text: String)

/** Stage 0 firewall check, 1 delay tests, 2 speed tests, 3 connecting; 20+ WARP. */
data class SearchProgress(val stage: Int, val done: Int, val total: Int, val name: String? = null)

/**
 * Connection logic of the Windows app: the Android VpnController's, with Xray behind the system proxy
 * instead of a VPN interface. Quick connect, auto mode, WARP and the national-internet rescue work the same way.
 */
object Controller {

    private val errors = CoroutineExceptionHandler { _, e -> log("Error: ${e.javaClass.simpleName}: ${e.message}") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()
    private val _log = MutableStateFlow<List<LogEvent>>(emptyList())
    val logs: StateFlow<List<LogEvent>> = _log.asStateFlow()
    private val _search = MutableStateFlow<SearchProgress?>(null)
    val search: StateFlow<SearchProgress?> = _search.asStateFlow()
    private val _rescue = MutableStateFlow(0)
    /** The national-internet rescue's current step (1..[NATIONAL_STEPS]), 0 = not running. */
    val rescue: StateFlow<Int> = _rescue.asStateFlow()
    const val NATIONAL_STEPS = 5

    private var monitorJob: Job? = null
    private var searchJob: Job? = null
    private var connectJob: Job? = null
    @Volatile private var connectGen = 0
    @Volatile private var lastPool: List<Server> = emptyList()
    @Volatile private var metricsPort = 0
    @Volatile private var lastAutoSwitch = 0L
    @Volatile private var autoSwitchMisses = 0

    private const val AUTO_POOL = 6
    private const val SEARCH_LIMIT_MS = 75_000L
    private const val SPEED_CHECKS = 4
    private const val AUTO_SWITCH_GAP_MS = 5 * 60_000L
    private const val AUTO_SWITCH_CANDIDATES = 40
    const val STAGE_WARP = 20

    fun init() {
        XrayCore.onStatus = { msg -> if (msg.contains("error", true) || msg.contains("failed", true)) log("Xray: $msg") }
        XrayCore.onExit = {
            if (_state.value is ConnState.Connected) {
                log(L.t("هستهٔ Xray متوقف شد", "Xray core stopped"))
                monitorJob?.cancel()
                cleanup()
                _state.value = ConnState.Error(L.t("هسته متوقف شد", "core stopped"))
            }
        }
        Repository.onServersRemoved = { removed, left ->
            val used = (left + removed.filter(BatchTester::inUse)).flatMapTo(mutableSetOf()) { it.warpRegs }
            val gone = removed.flatMap { it.warpRegs }.distinct().filter { it !in used }
            if (gone.isNotEmpty()) scope.launch { Warp.unregister(gone, Repository.settings.value) }
        }
        // A crash last time may have left Windows pointing at a proxy that no longer exists
        runCatching { SystemProxy.disable() }
    }

    fun log(text: String) = _log.update { (it + LogEvent(System.currentTimeMillis(), text)).takeLast(300) }

    val isActive get() = _state.value is ConnState.Connected || _state.value is ConnState.Connecting

    fun toggle() = if (isActive || searchJob?.isActive == true) disconnect() else connectFastestOrSelected()

    /** The main button: the selected server, or quick connect when nothing is selected yet. */
    fun connectFastestOrSelected() {
        val s = Repository.selectedServer()
        if (s != null && Repository.settings.value.selectedServerId != null) connect(s) else connectFastest()
    }

    fun connect(server: Server? = Repository.selectedServer()) {
        cancelSearch()
        connectJob = scope.launch { connectPool(listOfNotNull(server)) }
    }

    /** Settings that live in the core's config (routing, fragment, proxy): applied by reconnecting. */
    fun reapply() {
        if (_state.value !is ConnState.Connected || searchJob?.isActive == true) return
        val pool = lastPool.ifEmpty { return }
        connectJob?.cancel()
        connectJob = scope.launch { connectPool(pool) }
    }

    fun disconnect() {
        monitorJob?.cancel()
        cancelSearch()
        connectJob?.cancel()
        scope.launch {
            cleanup()
            Repository.persistUsage()
            _state.value = ConnState.Disconnected
            _traffic.value = Traffic()
            log(L.t("قطع شد", "Disconnected"))
        }
    }

    /** Called on exit: never leave Windows pointing at a proxy that is gone. */
    fun shutdown() {
        monitorJob?.cancel()
        cleanup()
        Repository.persistUsage()
    }

    fun cancelSearch() { searchJob?.cancel(); _search.value = null; _rescue.value = 0 }

    private suspend fun connectPool(requested: List<Server>): Boolean {
        val pool = onePerKey(requested)
        val first = pool.firstOrNull() ?: run { _state.value = ConnState.Error(L.t("سروری نیست", "no server")); return false }
        Repository.select(first.id)
        _state.value = ConnState.Connecting(first)
        val gen = ++connectGen
        return try {
            withTimeoutOrNull(8_000) { coroutineScope { pool.map { async(Dispatchers.IO) { TlsPin.forServer(it) } }.awaitAll() } }
            withContext(Dispatchers.IO) { startEngine(pool) }
            lastPool = pool
            _state.value = ConnState.Connected(first, System.currentTimeMillis(), pool.size)
            log(if (pool.size > 1) L.t("وصل شد (خودکار، ${pool.size} سرور): ${first.name}", "Connected (auto, ${pool.size} servers): ${first.name}")
                else L.t("وصل شد: ${first.name}", "Connected: ${first.name}"))
            NetReport.add("Connected: ${first.name}" + if (pool.size > 1) " (auto, ${pool.size} servers)" else "")
            startMonitors()
            true
        } catch (e: CancellationException) {
            if (gen == connectGen && _state.value is ConnState.Connecting) {
                cleanup()
                _state.value = ConnState.Disconnected
            }
            throw e
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            log(L.t("خطا: $msg", "Error: $msg"))
            NetReport.add("Connect failed (${first.name}): $msg")
            cleanup()
            _state.value = ConnState.Error(msg)
            false
        }
    }

    private fun onePerKey(servers: List<Server>): List<Server> {
        val used = mutableSetOf<String>()
        return servers.filter { s -> WireGuardCore.keys(s).let { k -> k.none { it in used }.also { if (it) used += k } } }
    }

    private fun warpExitFor(pool: List<Server>, s: AppSettings): Server? {
        if (s.warpExit == WarpExit.OFF || pool.any { it.protocol == Protocol.WIREGUARD }) return null
        val best = Repository.servers.value.filter { it.subscriptionId == Warp.SUB }.minByOrNull { it.score ?: Long.MAX_VALUE } ?: return null
        return best.copy(wgConf = best.wgOuter ?: best.wgConf, wgOuter = null)
    }

    /** Starts Xray with SOCKS + HTTP on this computer, then points Windows at it. */
    private fun startEngine(pool: List<Server>) {
        val s = Repository.settings.value
        val unused = HevTunnel.newEndpoint()
        val probe = HevTunnel.newEndpoint()
        val exit = warpExitFor(pool, s)
        val config = XrayConfigBuilder.buildTunnel(
            pool, s.copy(lanShare = false),
            XrayConfigBuilder.Inbound.Socks(unused.port, unused.user, unused.pass),
            hasGeoFiles = XrayCore.hasGeoFiles,
            pins = pool.mapNotNull { sv -> TlsPin.forServer(sv)?.let { sv.id to it } }.toMap(),
            probe = XrayConfigBuilder.Inbound.Socks(probe.port, probe.user, probe.pass),
            warpExit = exit,
        )
        metricsPort = HevTunnel.newEndpoint().port
        XrayCore.start(localInbounds(config, s, metricsPort))
        XrayVpnService.probe = probe
        XrayVpnService.isRunning = true
        XrayVpnService.activeKeys = (pool + listOfNotNull(exit)).flatMapTo(mutableSetOf()) { WireGuardCore.keys(it) }
        exit?.let { log("WARP exit: ${s.warpExit.name} through ${it.name}") }
        if (Prefs.systemProxy) SystemProxy.enable(s.lanHttpPort)
    }

    /**
     * The builder's tunnel inbound (password SOCKS, made for hev on Android) becomes what Windows programs use:
     * SOCKS5 and HTTP without a password, on 127.0.0.1 only. Plus Xray's metrics endpoint, for the live speed.
     */
    private fun localInbounds(config: String, s: AppSettings, metrics: Int): String {
        val root = Json.parseToJsonElement(config).jsonObject
        val listen = "127.0.0.1"
        val inbounds = root["inbounds"]!!.jsonArray.flatMap { ib ->
            val o = ib.jsonObject
            if (o["tag"]?.jsonPrimitive?.content != XrayConfigBuilder.IN_TAG) return@flatMap listOf(ib)
            val sniffing = o["sniffing"] ?: JsonObject(emptyMap())
            listOf(
                buildJsonObject {
                    put("tag", XrayConfigBuilder.IN_TAG); put("listen", listen); put("port", s.lanSocksPort); put("protocol", "socks")
                    put("settings", buildJsonObject { put("auth", "noauth"); put("udp", true); put("userLevel", 8) })
                    put("sniffing", sniffing)
                },
                buildJsonObject {
                    put("tag", "in-http"); put("listen", listen); put("port", s.lanHttpPort); put("protocol", "http")
                    put("settings", buildJsonObject { put("userLevel", 8) })
                    put("sniffing", sniffing)
                },
            )
        }
        val out = root.toMutableMap<String, JsonElement>()
        out["inbounds"] = JsonArray(inbounds)
        out["metrics"] = buildJsonObject { put("tag", "metrics"); put("listen", "127.0.0.1:$metrics") }
        return JsonObject(out).toString()
    }

    private fun cleanup() {
        runCatching { SystemProxy.disable() }
        XrayCore.stop()
        XrayVpnService.probe = null
        XrayVpnService.isRunning = false
        XrayVpnService.activeKeys = emptySet()
    }

    // ---------------- quick connect / WARP / national internet ----------------

    fun connectFastest(onNone: () -> Unit = {}) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch { if (!searchAndConnect(Repository.fastestCandidates())) { log(L.t("هیچ سروری جواب نداد", "No server answered")); onNone() } }
    }

    /** WARP: saved accounts first (connect at once, check in the background), else new ones. */
    fun connectWarp(fresh: Boolean = false, onDone: (Boolean) -> Unit = {}) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            val ok = try { warp(Warp.Mode.WARP_IN_WARP, null, fresh) } finally { _search.value = null }
            onDone(ok)
        }
    }

    /**
     * National internet (only Iranian sites reachable): servers inside Iran first, then every server, then every
     * server with TLS fragmentation, then WARP in WARP and a new WARP over IPv6. Each step is checked through the
     * connection itself before it counts.
     */
    fun connectNational(onDone: (Boolean) -> Unit = {}) {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            val ok = try { nationalSteps() } finally { _rescue.value = 0; _search.value = null }
            onDone(ok)
        }
    }

    private suspend fun works(step: Int, connected: Boolean): Boolean {
        val ok = connected && tunnelDelay() > 0
        NetReport.add("National step $step: " + if (ok) "connected, tunnel answers" else "didn't get through")
        return ok
    }

    private suspend fun nationalSteps(): Boolean {
        val all = Repository.servers.value.filter { it.subscriptionId != Warp.SUB }.sortedBy { it.score ?: Long.MAX_VALUE }
        NetReport.add("National internet mode started (${NationalNet.check().name.lowercase()} network, ${all.size} servers)")
        _rescue.value = 1
        val bridges = NationalNet.bridges(all)
        log(L.t("${bridges.size} سرور داخل ایران", "${bridges.size} servers inside Iran"))
        if (bridges.isNotEmpty() && works(1, searchAndConnect(bridges))) return true
        _rescue.value = 2
        Repository.updateSettings { it.copy(cdnCheckedAt = it.cdnCheckedAt - NetKey.current) }
        if (works(2, searchAndConnect(all))) return true
        _rescue.value = 3
        val before = Repository.settings.value.fragmentMode
        Repository.updateSettings { it.copy(fragmentMode = FragmentMode.ALWAYS) }
        if (works(3, searchAndConnect(all))) return true
        Repository.updateSettings { it.copy(fragmentMode = before) }
        _rescue.value = 4
        if (works(4, warp(Warp.Mode.WARP_IN_WARP, null, fresh = false))) return true
        _rescue.value = 5
        if (works(5, warp(Warp.Mode.WARP, ipv6 = true, fresh = true))) return true
        if (_state.value is ConnState.Connected) {
            monitorJob?.cancel(); cleanup(); _state.value = ConnState.Disconnected; _traffic.value = Traffic()
        }
        return false
    }

    private suspend fun warp(mode: Warp.Mode, ipv6: Boolean?, fresh: Boolean): Boolean {
        val saved = Repository.servers.value.filter { it.subscriptionId == Warp.SUB }.sortedBy { it.score ?: Long.MAX_VALUE }
        if (saved.isNotEmpty() && !fresh) {
            if (connectLive(saved)) {
                _search.value = null
                if (tunnelDelay() > 0) { checkWarp(saved); return true }
            }
            val live = checkWarp(saved).filterNot(BatchTester::inUse)
            if (live.isNotEmpty() && connectLive(live) && tunnelDelay() > 0) return true
            log(L.t("هیچ‌کدام از سرورهای WARP ذخیره‌شده جواب نداد", "None of the saved WARP servers answered"))
        }
        val found = try {
            Warp.create(mode, ipv6, Repository.settings.value) { st, d, t -> _search.value = SearchProgress(STAGE_WARP + st, d, t) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(L.t("ساخت WARP نشد: ${e.message}", "WARP failed: ${e.message}"))
            NetReport.add("WARP failed: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        if (found.isEmpty()) { log(L.t("هیچ نقطهٔ اتصال WARP روی این شبکه جواب نداد", "No WARP endpoint answered on this network")); return false }
        Repository.addServers(found)
        log(L.t("${found.size} سرور WARP اضافه شد", "${found.size} WARP servers added"))
        return connectLive(found)
    }

    private suspend fun checkWarp(saved: List<Server>): List<Server> {
        val results = BatchTester.raw(saved, Repository.settings.value)
        results.forEach { Repository.setPing(it.server.id, it.ms, PingKind.REAL) }
        val dead = results.filter { it.ms <= 0 }.map { it.server.id }
        Repository.deleteServers(dead)
        val tested = results.mapTo(mutableSetOf()) { it.server.id }
        return results.filter { it.ms > 0 }.sortedBy { it.ms }.map { it.server } + saved.filter { it.id !in tested && BatchTester.inUse(it) }
    }

    private suspend fun connectLive(live: List<Server>): Boolean {
        val pool = onePerKey(live).take(AUTO_POOL)
        _search.value = SearchProgress(3, pool.size, pool.size)
        return connectPool(pool)
    }

    /** Real delay through the running connection, -1 if it doesn't answer or isn't up. */
    suspend fun tunnelDelay(): Long = withContext(Dispatchers.IO) {
        val ep = XrayVpnService.probe ?: return@withContext -1L
        SpeedProbe.delay(ep.port, ep.user, ep.pass, Repository.settings.value.testUrl)
    }

    /** Tests [servers] (real delay) and records the results; for the Servers page. */
    fun testServers(servers: List<Server>) {
        if (searchJob?.isActive == true || servers.isEmpty()) return
        searchJob = scope.launch {
            try {
                Pinger.measureAll(servers, Repository.settings.value, real = true,
                    onProgress = { d, t -> _search.value = SearchProgress(1, d, t) },
                    onResult = { r ->
                        Repository.setPing(r.server.id, r.ms, r.kind)
                        if (r.ms <= 0 && r.error != null) log("✗ ${r.server.name}: ${r.error}")
                    })
            } finally {
                Repository.saveServers()
                _search.value = null
            }
        }
    }

    private suspend fun searchAndConnect(candidates: List<Server>): Boolean = try {
        if (CdnDetector.due(Repository.settings.value)) {
            _search.value = SearchProgress(0, 0, 0)
            CdnDetector.markCloudflare(candidates)
            val fresh = Repository.servers.value.associateBy { it.id }
            val r = CdnDetector.detect(candidates.mapNotNull { fresh[it.id] }, Repository.settings.value)
            if (r != null) NetReport.add("Firewall check: ${r.method.name} chosen")
        }
        val settings = Repository.settings.value
        _search.value = SearchProgress(1, 0, candidates.size)
        val ok = ConcurrentHashMap<String, Long>()
        val passed = try {
            withTimeoutOrNull(SEARCH_LIMIT_MS) {
                Pinger.rank(
                    Repository.servers.value.associateBy { it.id }.let { m -> candidates.map { m[it.id] ?: it } }, settings,
                    onStage = { st, d, t -> _search.value = SearchProgress(st, d, t) },
                    onResult = {
                        Repository.setPing(it.server.id, it.ms, it.kind)
                        if (it.ms > 0) ok[it.server.id] = it.ms
                    },
                )
            } ?: candidates.filter { ok.containsKey(it.id) }.sortedBy { ok[it.id] }
        } finally {
            Repository.saveServers()
        }
        if (passed.isEmpty()) false
        else {
            val byId = Repository.servers.value.associateBy { it.id }
            val ranked = passed.mapNotNull { byId[it.id] }.sortedBy { it.score ?: Long.MAX_VALUE }
            val top = ranked.take(SPEED_CHECKS)
            val speeds = BatchTester.speeds(top, settings) { i, s -> _search.value = SearchProgress(2, i + 1, top.size, s.name) }
            val fast = if (speeds.isEmpty()) top else top.filter { speeds[it.id]?.ok == true }
            val pool = onePerKey(fast + ranked.drop(SPEED_CHECKS)).take(AUTO_POOL).ifEmpty { ranked.take(1) }
            _search.value = SearchProgress(3, pool.size, pool.size)
            connectPool(pool)
        }
    } finally {
        _search.value = null
    }

    // ---------------- live speed + health ----------------

    /** Total bytes up / down of every outbound, from Xray's metrics endpoint. */
    private fun counters(): Pair<Long, Long>? = runCatching {
        val c = URI("http://127.0.0.1:$metricsPort/debug/vars").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 1000
        val text = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        val outbound = Json.parseToJsonElement(text).jsonObject["stats"]?.jsonObject?.get("outbound")?.jsonObject ?: return null
        var up = 0L; var down = 0L
        for ((_, v) in outbound) {
            val o = v.jsonObject
            up += o["uplink"]?.jsonPrimitive?.longOrNull ?: 0
            down += o["downlink"]?.jsonPrimitive?.longOrNull ?: 0
        }
        down to up
    }.getOrNull()

    private fun startMonitors() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var (lastRx, lastTx) = counters() ?: (0L to 0L)
            var failures = 0
            var healthJob: Job? = null
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            var last = System.currentTimeMillis()
            var lastHealth = last
            var lastPersist = last
            while (isActive) {
                // Hidden in the tray: nobody watches the speed
                delay(if (Prefs.windowVisible) 1000 else 10_000)
                val now = System.currentTimeMillis()
                val secs = ((now - last) / 1000.0).coerceAtLeast(1.0)
                last = now
                val (rx, tx) = counters() ?: (lastRx to lastTx)
                val dRx = (rx - lastRx).coerceAtLeast(0); val dTx = (tx - lastTx).coerceAtLeast(0)
                lastRx = rx; lastTx = tx
                _traffic.update { it.copy(downBps = (dRx / secs).toLong(), upBps = (dTx / secs).toLong(), sessionRx = it.sessionRx + dRx, sessionTx = it.sessionTx + dTx) }
                Repository.addUsage(day.format(Date()), dRx, dTx)
                if (now - lastPersist >= 60_000) { lastPersist = now; Repository.persistUsage() }

                val st = Repository.settings.value
                if (now - lastHealth >= 30_000 && healthJob?.isActive != true) {
                    lastHealth = now
                    healthJob = launch(Dispatchers.IO) {
                        val ep = XrayVpnService.probe
                        val healthy = ep != null && SpeedProbe.delay(ep.port, ep.user, ep.pass, st.testUrl, tries = 1) > 0
                        failures = if (healthy) 0 else failures + 1
                        if (!healthy) log(L.t("بررسی اتصال ناموفق ($failures)", "Health check failed ($failures)"))
                        val gap = minOf(AUTO_SWITCH_GAP_MS shl autoSwitchMisses.coerceAtMost(4), 60 * 60_000L)
                        if (st.autoSwitch && failures >= st.autoSwitchFailures && searchJob?.isActive != true &&
                            System.currentTimeMillis() - lastAutoSwitch >= gap) {
                            lastAutoSwitch = System.currentTimeMillis()
                            failures = 0
                            searchJob = scope.launch { autoSwitch() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun autoSwitch() {
        val current = (state.value as? ConnState.Connected)?.takeIf { it.pool <= 1 }?.server
        log(L.t("اتصال جواب نمی‌دهد؛ جست‌وجوی سرور بهتر…", "Connection not answering; looking for a better server…"))
        val pool = Repository.fastestCandidates().filter { it.id != current?.id }
            .ifEmpty { Repository.servers.value.filter { it.id != current?.id } }
            .sortedBy { it.score ?: Long.MAX_VALUE }.take(AUTO_SWITCH_CANDIDATES)
        if (searchAndConnect(pool)) autoSwitchMisses = 0
        else {
            autoSwitchMisses++
            log(L.t("سرور بهتری پیدا نشد", "No better server found"))
            startMonitors()
        }
    }
}
