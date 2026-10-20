package com.netino.desktop

import com.netino.vpn.core.LocalRoutes
import com.netino.vpn.core.XrayConfigBuilder
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.LinkParser
import com.netino.vpn.data.Repository
import com.netino.vpn.data.SecureStore
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
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
        check("geo") { "hasGeo=${XrayCore.hasGeoFiles} ir=${"ir" in XrayCore.geoIpTags} sites=${XrayCore.geoSiteTags.size}".also { require(XrayCore.hasGeoFiles) } }
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
                SystemProxy.enable(port)
                val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
                val server = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "ProxyServer")
                require(server == "127.0.0.1:$port") { "registry has $server" }
                val c = URI("https://www.gstatic.com/generate_204").toURL()
                    .openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val code = c.responseCode
                SystemProxy.disable()
                val after = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, key, "ProxyEnable")
                "HTTP $code through xray, proxy restored (ProxyEnable=$after)".also { require(code == 204 && after == 0) }
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
        return failures
    }
}
