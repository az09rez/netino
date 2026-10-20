package com.netino.desktop

import com.netino.vpn.core.LocalRoutes
import com.netino.vpn.core.NetReport
import com.netino.vpn.core.Warp
import com.netino.vpn.core.XrayConfigBuilder
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.LinkParser
import com.netino.vpn.data.Repository
import com.netino.vpn.data.SecureStore
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.net.HttpURLConnection

/**
 * `--selftest` (run by CI on Windows): the parts that only exist on Windows (xray.exe, the proxy setting,
 * DPAPI) checked for real. Prints one line per check; the exit code is the number of failures.
 */
object SelfTest {
    private var failures = 0
    /** Also in a file: the installed launcher is a GUI program, its console output may go nowhere. */
    private val out = java.io.File(Store.dir, "selftest.txt")

    fun report(line: String) {
        println(line)
        out.appendText(line + "\n")
    }

    private fun check(name: String, block: () -> String) {
        val r = runCatching(block)
        if (r.isFailure) failures++
        report((if (r.isSuccess) "PASS " else "FAIL ") + name + ": " + (r.getOrNull() ?: r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }))
    }

    fun run(): Int {
        out.delete()
        check("xray") { XrayCore.version.also { require(it != "?") { "xray.exe not found" } } }
        // Not bundled any more: downloaded as on a first start
        check("geo") {
            kotlinx.coroutines.runBlocking { com.netino.vpn.core.GeoFiles.update() }.getOrThrow()
            "hasGeo=${XrayCore.hasGeoFiles} ir=${"ir" in XrayCore.geoIpTags} sites=${XrayCore.geoSiteTags.size}".also { require(XrayCore.hasGeoFiles) }
        }
        check("store") {
            val s = SecureStore(Store.context)
            s.write("selftest", "سلام")
            (s.read("selftest") ?: error("unreadable")).also { require(it == "سلام") }
        }
        check("builtin list") { "${Repository.subscriptions.value.size} subscriptions" }
        val port = ServerSocket(0).use { it.localPort }
        check("core + proxy") {
            XrayCore.start("""{"log":{"loglevel":"error"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"http"}],"outbounds":[{"protocol":"freedom"}]}""")
            try {
                val before = SystemProxy.current()
                SystemProxy.enable(port)
                val set = SystemProxy.current()
                require(set?.proxyOn == true && set.server == "127.0.0.1:$port") { "WinINet has $set" }
                val c = URI("https://www.gstatic.com/generate_204").toURL()
                    .openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val code = c.responseCode
                SystemProxy.disable()
                val after = SystemProxy.current()
                "HTTP $code through xray; set $set; restored $after (was $before)".also { require(code == 204 && after?.flags == before?.flags && after?.proxyOn != true) }
            } finally {
                SystemProxy.disable()
                XrayCore.stop()
            }
        }
        check("fragment core") { (LocalRoutes.withFragmentCore { "socks ${it.port}" } ?: error("didn't start")) }
        check("offline delay") {
            val s = LinkParser.parse("vless://11111111-2222-3333-4444-555555555555@127.0.0.1:9?encryption=none&security=tls&sni=example.com&type=ws#t")!!
            val d = XrayCore.measureOffline(XrayConfigBuilder.build(s, Repository.settings.value, XrayConfigBuilder.Inbound.None))
            "$d".also { require(d.ms < 0) }
        }
        check("qr") {
            val text = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#نتینو"
            val read = com.netino.desktop.ui.Qr.decode(com.netino.desktop.ui.Qr.encodeImage(text)!!)
            "read back: ${read.size} code".also { require(read == listOf(text)) { "got $read" } }
        }
        check("warp") { warpTest() }
        val iface = Win.defaultInterface()
        check("default interface") { iface ?: error("not found") }
        check("full tunnel") { tunTest(iface) }
        return failures
    }

    /** WARP from scratch, as the WARP button does: an account, an endpoint scan, then real traffic through it. */
    private fun warpTest(): String = kotlinx.coroutines.runBlocking {
        val log = mutableListOf<String>()
        NetReport.onAdd = { log += it }
        try {
            val found = Warp.create(Warp.Mode.WARP, ipv6 = false, settings = Repository.settings.value, keep = 1)
            require(found.isNotEmpty()) { "no endpoint answered; " + log.joinToString(" | ") }
            val d = XrayCore.measureOffline(XrayConfigBuilder.build(found.first(), Repository.settings.value, XrayConfigBuilder.Inbound.None))
            Warp.unregister(found.flatMap { it.warpRegs }, Repository.settings.value)
            "${found.first().name}: scan ${found.first().lastPingMs} ms, real delay $d".also { require(d.ms > 0) { "$it; " + log.joinToString(" | ") } }
        } finally {
            NetReport.onAdd = {}
        }
    }

    /**
     * The real TUN (the runner is administrator): a request made without any proxy must come out of Xray, and a
     * test core bound to the physical interface must still reach the internet beside it.
     */
    private fun tunTest(iface: String?): String {
        require(Win.isAdmin) { "not administrator" }
        val metrics = ServerSocket(0).use { it.localPort }
        XrayCore.start("""{"log":{"loglevel":"error"},
            "inbounds":[{"tag":"in","port":0,"protocol":"tun","settings":{"name":"${Controller.TUN_NAME}","mtu":1500,
              "gateway":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"dns":["172.19.0.2"],
              "autoSystemRoutingTable":["0.0.0.0/0","::/0"],"autoSystemWfpBlockLeak":["dns"]},
              "sniffing":{"enabled":true,"destOverride":["http","tls"],"routeOnly":true}}],
            "outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"dns-out","protocol":"dns"}],
            "dns":{"servers":["1.1.1.1"],"queryStrategy":"UseIPv4"},
            "routing":{"rules":[{"inboundTag":["in"],"port":"53","outboundTag":"dns-out"}]},
            "stats":{},"policy":{"system":{"statsOutboundDownlink":true}},
            "metrics":{"tag":"metrics","listen":"127.0.0.1:$metrics"}}""")
        try {
            Thread.sleep(3000)   // adapter, addresses and routes
            val c = URI("http://connectivitycheck.gstatic.com/generate_204").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            val code = c.responseCode
            val vars = URI("http://127.0.0.1:$metrics/debug/vars").toURL().openConnection(Proxy.NO_PROXY).getInputStream().bufferedReader().readText()
            val down = Regex("\"direct\"\\s*:\\s*\\{[^}]*\"downlink\"\\s*:\\s*(\\d+)").find(vars)?.groupValues?.get(1)?.toLong() ?: 0
            require(code == 204 && down > 0) { "HTTP $code, $down bytes through Xray" }
            // A test core beside the tunnel, bound to the real interface
            XrayCore.outboundInterface = iface
            val bound = LocalRoutes.withFragmentCore { ep -> com.netino.vpn.core.SpeedProbe.delay(ep.port, ep.user, ep.pass, "https://www.gstatic.com/generate_204") } ?: -1
            val tunText = "HTTP $code with $down bytes through the TUN; test core on $iface: ${bound} ms".also { require(bound > 0) { it } }
            // Kill switch: another program (PowerShell) gets out through the tunnel, not without it, and again once released
            KillSwitch.enable()
            val withTunnel = psFetch()
            XrayCore.outboundInterface = null
            XrayCore.stop()
            Thread.sleep(2000)
            val withoutTunnel = psFetch()
            KillSwitch.disable()
            val released = psFetch()
            return "$tunText; kill switch: tunnel $withTunnel, dropped $withoutTunnel, released $released".also {
                require(withTunnel && !withoutTunnel && released) { it }
            }
        } finally {
            runCatching { KillSwitch.disable() }
            XrayCore.outboundInterface = null
            XrayCore.stop()
        }
    }

    /** A request by another program (PowerShell), as the firewall sees any program but Netino and Xray. */
    private fun psFetch(): Boolean = runCatching {
        Win.powershell("(Invoke-WebRequest -UseBasicParsing -TimeoutSec 8 'http://connectivitycheck.gstatic.com/generate_204').StatusCode", 40).contains("204")
    }.getOrDefault(false)
}
