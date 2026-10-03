package com.netino.vpn.core

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * Thin wrapper around AndroidLibXrayLite (libv2ray.aar, gomobile bindings).
 * startLoop(config, tunFd): tunFd > 0 feeds Xray's own `tun` inbound; 0 = no TUN (hev-socks5-tunnel mode).
 */
object XrayCore {

    private var controller: CoreController? = null
    var hasGeoFiles = false
        private set

    /** Receives core status lines (errors, startup) for the live report. */
    var onStatus: (String) -> Unit = {}

    fun init(context: Context) {
        val dir = File(context.filesDir, "xray").apply { mkdirs() }
        // Copy optional geoip.dat / geosite.dat from assets (enables geoip:ir, geosite:category-ads-all ...)
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val out = File(dir, name)
            if (!out.exists()) runCatching {
                context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        hasGeoFiles = File(dir, "geoip.dat").exists() && File(dir, "geosite.dat").exists()
        Libv2ray.initCoreEnv(dir.absolutePath, "")
    }

    val version: String get() = runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")

    @Synchronized
    fun start(config: String, tunFd: Int) {
        stop()
        val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, msg: String?): Long {
                if (!msg.isNullOrBlank()) onStatus(msg)
                return 0
            }
        })
        c.startLoop(config, tunFd)
        controller = c
    }

    @Synchronized
    fun stop() {
        runCatching { controller?.stopLoop() }
        controller = null
    }

    val isRunning get() = controller != null

    /**
     * A second, short-lived core for batch tests (SOCKS inbounds only, no TUN), independent of the
     * running tunnel. Throws if the core can't start (e.g. a config the core rejects).
     */
    fun startTestCore(config: String): CoreController {
        val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, msg: String?): Long = 0
        })
        c.startLoop(config, 0)
        return c
    }

    fun stopTestCore(c: CoreController) { runCatching { c.stopLoop() } }

    /** Real end-to-end delay through the running tunnel (ms), or -1. */
    fun measureRunning(url: String = TEST_URL): Long =
        runCatching { controller?.measureDelay(url) ?: -1 }.getOrDefault(-1)

    data class Delay(val ms: Long, val error: String?)

    /**
     * Real delay for a server that is NOT running. libv2ray keeps only the outbound, makes up to
     * 2 HTTP requests and returns the best; the core's error text is kept for diagnostics.
     */
    fun measureOffline(config: String, url: String = TEST_URL): Delay =
        try {
            val ms = Libv2ray.measureOutboundDelay(config, url)
            if (ms > 0) Delay(ms, null) else Delay(-1, "no response")
        } catch (e: Exception) {
            Delay(-1, e.message?.lineSequence()?.firstOrNull()?.take(300) ?: e.javaClass.simpleName)
        }

    const val TEST_URL = "https://www.gstatic.com/generate_204"
}
