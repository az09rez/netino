package com.netino.vpn.core

import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.FragmentMode
import com.netino.vpn.data.Repository
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Server
import com.netino.vpn.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests many servers with ONE Xray core: server i is exposed on its own authenticated local SOCKS
 * port, and HTTP requests run through all of them in parallel. Much faster and lighter than starting
 * a core per server (the old way): a list of 50 takes seconds instead of minutes.
 * Falls back to the per-server test if the batch core can't start.
 *
 * WireGuard keys: Cloudflare keeps one live session per key, so a test with a key the tunnel uses would
 * cut the tunnel off. While connected such servers are not tested at all (and left out of the results);
 * servers that share a key are tested one after another, never at the same time.
 */
object BatchTester {

    private const val CHUNK = 64
    private const val PARALLEL = 24
    private const val REJECTED = "config rejected by the core"

    /**
     * Real delay of every server; [onResult] is called as each finishes (for live sorting).
     * Fragment AUTO: TLS / Reality servers that fail are tested again with the ClientHello fragmented;
     * the ones that then work are remembered as fragment servers (and those that stop needing it are reset).
     */
    suspend fun delays(
        servers: List<Server>, settings: AppSettings,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (Pinger.Result) -> Unit = {},
    ): List<Pinger.Result> = withContext(Dispatchers.IO) {
        val servers = testable(servers)
        val done = AtomicInteger()
        val total = servers.size
        val auto = settings.fragmentMode == FragmentMode.AUTO
        fun retryable(r: Pinger.Result) = auto && r.ms <= 0 && !r.server.fragment && r.error != REJECTED &&
            XrayConfigBuilder.usesFragment(r.server, settings, force = true)

        val first = pass(servers, settings, force = false) { r ->
            // A fragment server that fails even with fragmentation is tried plain again next time
            if (auto && r.ms <= 0 && r.server.fragment) Repository.setFragment(r.server.id, false)
            if (!retryable(r)) { onResult(r); onProgress(done.incrementAndGet(), total) }
        }
        val again = first.filter(::retryable)
        if (again.isEmpty()) return@withContext first
        val second = pass(again.map { it.server }, settings, force = true) { r ->
            if (r.ms > 0) Repository.setFragment(r.server.id, true)
            onResult(r); onProgress(done.incrementAndGet(), total)
        }.associateBy { it.server.id }
        first.map { second[it.server.id] ?: it }
    }

    /**
     * Real delay of each server exactly as given: no fragment retry, nothing stored.
     * [enough]: stop once that many answered (servers not tested by then are left out of the results);
     * [quick]: a single short try per server, for scans of many endpoints with one key.
     */
    suspend fun raw(servers: List<Server>, settings: AppSettings, enough: Int = Int.MAX_VALUE, quick: Boolean = false): List<Pinger.Result> =
        withContext(Dispatchers.IO) {
            val passed = AtomicInteger()
            pass(testable(servers), settings, force = false, Limit(enough, passed, quick)) { if (it.ms > 0) passed.incrementAndGet() }
        }

    /** Whether [s] connects with a WireGuard key the running tunnel uses (testing it would cut the tunnel off). */
    fun inUse(s: Server): Boolean {
        val active = XrayVpnService.activeKeys
        return active.isNotEmpty() && WireGuardCore.keys(s).any { it in active }
    }

    private fun testable(servers: List<Server>) = servers.filterNot(::inUse)

    private class Limit(val enough: Int, val passed: AtomicInteger, val quick: Boolean)

    /** One lock per WireGuard key, shared by every test (also concurrent ones), so a key is never used twice at once. */
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    /** Runs [block] holding the locks of all of [s]'s keys (taken in a fixed order: no deadlock between two hops' keys). */
    private suspend fun <T> exclusive(s: Server, block: suspend () -> T): T {
        suspend fun hold(keys: List<String>): T =
            if (keys.isEmpty()) block() else keyLocks.getOrPut(keys[0]) { Mutex() }.withLock { hold(keys.drop(1)) }
        return hold(WireGuardCore.keys(s).sorted())
    }

    /**
     * One batch core per chunk. Xray refuses a whole config if a single outbound is invalid, so a chunk the
     * core rejects is split in halves until the bad config is isolated (each try is just a core start, a few
     * ms) - one broken config can't push the whole list onto the slow per-server path any more.
     */
    private suspend fun pass(
        servers: List<Server>, settings: AppSettings, force: Boolean, limit: Limit? = null, onResult: (Pinger.Result) -> Unit,
    ): List<Pinger.Result> = servers.chunked(CHUNK).flatMap { testChunk(it, settings, force, limit, onResult) }

    private suspend fun testChunk(
        chunk: List<Server>, settings: AppSettings, force: Boolean, limit: Limit?, onResult: (Pinger.Result) -> Unit,
    ): List<Pinger.Result> {
        if (chunk.isEmpty()) return emptyList()
        fun done() = limit != null && limit.passed.get() >= limit.enough
        if (done()) return emptyList()
        val results = runChunk(chunk, settings, force) { ports ->
            coroutineScope {
                val gate = Semaphore(PARALLEL)
                chunk.indices.map { i ->
                    async {
                        // Key first, then a parallel slot: a server waiting for its key doesn't hold a slot
                        exclusive(chunk[i]) {
                            gate.withPermit {
                                if (done()) return@withPermit null
                                val ep = ports[i]
                                val ms = if (limit?.quick == true) SpeedProbe.delay(ep.port, ep.user, ep.pass, settings.testUrl, tries = 1, timeoutMs = 3000)
                                else SpeedProbe.delay(ep.port, ep.user, ep.pass, settings.testUrl)
                                    .let { if (it > 0) it else SpeedProbe.delay(ep.port, ep.user, ep.pass, FALLBACK_URL, tries = 1) }
                                Pinger.Result(chunk[i], if (ms > 0) ms else Pinger.FAILED, PingKind.REAL, if (ms > 0) null else "timeout")
                                    .also(onResult)
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
        if (results != null) return results
        if (!coreUsable()) {
            // A second core can't run here at all: per-server tests, in parallel
            return coroutineScope {
                val gate = Semaphore(8)
                chunk.map { s ->
                    async { exclusive(s) { gate.withPermit { if (done()) null else Pinger.realOne(s, settings).also(onResult) } } }
                }.awaitAll().filterNotNull()
            }
        }
        if (chunk.size == 1) {
            // The core rejects this config on its own: report it instead of waiting on a slow test
            return listOf(Pinger.Result(chunk[0], Pinger.FAILED, PingKind.REAL, REJECTED).also(onResult))
        }
        val mid = chunk.size / 2
        return testChunk(chunk.subList(0, mid), settings, force, limit, onResult) +
            testChunk(chunk.subList(mid, chunk.size), settings, force, limit, onResult)
    }

    /** Download speed (KB/s) of each server, tested one after another so they don't share bandwidth. */
    suspend fun speeds(servers: List<Server>, settings: AppSettings, onEach: (Int, Server) -> Unit = { _, _ -> }): Map<String, SpeedProbe.Result> =
        withContext(Dispatchers.IO) {
            val servers = testable(servers)
            if (servers.isEmpty()) return@withContext emptyMap()
            runChunk(servers, settings, false) { ports ->
                servers.mapIndexed { i, s ->
                    onEach(i, s)
                    s.id to exclusive(s) { SpeedProbe.run(ports[i].port, ports[i].user, ports[i].pass, timeoutMs = 4000) }
                }.toMap()
            }.orEmpty()
        }

    private const val FALLBACK_URL = "https://cp.cloudflare.com/generate_204"

    @Volatile private var usable: Boolean? = null

    /** Whether a second (test) core can start on this device at all; checked once with a trivial config. */
    private fun coreUsable(): Boolean = usable ?: runCatching {
        XrayCore.stopTestCore(XrayCore.startTestCore("""{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom"}]}"""))
    }.isSuccess.also { usable = it }

    /** Starts a batch core for [chunk], runs [block] with its SOCKS endpoints, always stops it. Null if it can't start. */
    private suspend fun <T> runChunk(
        chunk: List<Server>, settings: AppSettings, force: Boolean,
        block: suspend (List<XrayConfigBuilder.Inbound.Socks>) -> T,
    ): T? {
        val pins = coroutineScope {
            chunk.map { s -> async(Dispatchers.IO) { TlsPin.forServer(s)?.let { s.id to it } } }.awaitAll().filterNotNull().toMap()
        }
        val ports = chunk.map { HevTunnel.newEndpoint().let { XrayConfigBuilder.Inbound.Socks(it.port, it.user, it.pass) } }
        val core = runCatching {
            XrayCore.startTestCore(XrayConfigBuilder.buildBatch(chunk, ports, settings, pins, forceFragment = force))
        }.getOrNull() ?: return null
        return try {
            block(ports)
        } finally {
            XrayCore.stopTestCore(core)
        }
    }
}
