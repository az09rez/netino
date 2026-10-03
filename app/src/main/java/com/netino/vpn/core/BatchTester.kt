package com.netino.vpn.core

import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests many servers with ONE Xray core: server i is exposed on its own authenticated local SOCKS
 * port, and HTTP requests run through all of them in parallel. Much faster and lighter than starting
 * a core per server (the old way): a list of 50 takes seconds instead of minutes.
 * Falls back to the per-server test if the batch core can't start.
 */
object BatchTester {

    private const val CHUNK = 64
    private const val PARALLEL = 16

    /** Real delay of every server; [onResult] is called as each finishes (for live sorting). */
    suspend fun delays(
        servers: List<Server>, settings: AppSettings,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (Pinger.Result) -> Unit = {},
    ): List<Pinger.Result> = withContext(Dispatchers.IO) {
        val done = AtomicInteger()
        servers.chunked(CHUNK).flatMap { chunk ->
            runChunk(chunk, settings) { ports ->
                coroutineScope {
                    val gate = Semaphore(PARALLEL)
                    chunk.indices.map { i ->
                        async {
                            gate.withPermit {
                                val ep = ports[i]
                                val ms = SpeedProbe.delay(ep.port, ep.user, ep.pass, settings.testUrl)
                                    .let { if (it > 0) it else SpeedProbe.delay(ep.port, ep.user, ep.pass, FALLBACK_URL, tries = 1) }
                                val r = Pinger.Result(chunk[i], if (ms > 0) ms else Pinger.FAILED, PingKind.REAL, if (ms > 0) null else "timeout")
                                onResult(r)
                                onProgress(done.incrementAndGet(), servers.size)
                                r
                            }
                        }
                    }.awaitAll()
                }
            } ?: chunk.map { s ->
                // Batch core rejected the list: test one by one (slow path)
                Pinger.realOne(s, settings).also { onResult(it); onProgress(done.incrementAndGet(), servers.size) }
            }
        }
    }

    /** Download speed (KB/s) of each server, tested one after another so they don't share bandwidth. */
    suspend fun speeds(servers: List<Server>, settings: AppSettings, onEach: (Int, Server) -> Unit = { _, _ -> }): Map<String, SpeedProbe.Result> =
        withContext(Dispatchers.IO) {
            runChunk(servers, settings) { ports ->
                servers.mapIndexed { i, s ->
                    onEach(i, s)
                    s.id to SpeedProbe.run(ports[i].port, ports[i].user, ports[i].pass, timeoutMs = 4000)
                }.toMap()
            }.orEmpty()
        }

    private const val FALLBACK_URL = "https://cp.cloudflare.com/generate_204"

    /** Starts a batch core for [chunk], runs [block] with its SOCKS endpoints, always stops it. Null if it can't start. */
    private suspend fun <T> runChunk(
        chunk: List<Server>, settings: AppSettings,
        block: suspend (List<XrayConfigBuilder.Inbound.Socks>) -> T,
    ): T? {
        val pins = coroutineScope {
            chunk.map { s -> async(Dispatchers.IO) { TlsPin.forServer(s)?.let { s.id to it } } }.awaitAll().filterNotNull().toMap()
        }
        val ports = chunk.map { HevTunnel.newEndpoint().let { XrayConfigBuilder.Inbound.Socks(it.port, it.user, it.pass) } }
        val core = runCatching {
            XrayCore.startTestCore(XrayConfigBuilder.buildBatch(chunk, ports, settings, pins))
        }.getOrNull() ?: return null
        return try {
            block(ports)
        } finally {
            XrayCore.stopTestCore(core)
        }
    }
}
