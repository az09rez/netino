package com.netino.desktop

/**
 * The desktop's proxy setting on Linux, which browsers and most programs follow: GNOME (and Cinnamon, Budgie,
 * Unity, ... through gsettings) and KDE Plasma (kioslaverc). Both are set when both exist. What was there is
 * saved and put back on disconnect, and at the next start after a crash, like the Windows setting.
 */
object LinuxProxy {
    private const val GNOME = "org.gnome.system.proxy"
    private val IGNORE = listOf("localhost", "127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "*.local")

    private val gnome get() = Platform.has("gsettings") && Platform.run("gsettings", "get", GNOME, "mode") != null
    private val kwrite get() = listOf("kwriteconfig6", "kwriteconfig5").firstOrNull(Platform::has)
    private val kread get() = listOf("kreadconfig6", "kreadconfig5").firstOrNull(Platform::has)

    val available get() = Platform.isLinux && (gnome || kwrite != null)

    private fun gget(schema: String, key: String) = Platform.run("gsettings", "get", schema, key)
    private fun gset(schema: String, key: String, value: String) =
        checkNotNull(Platform.run("gsettings", "set", schema, key, value)) { "gsettings refused $schema $key" }

    private fun kget(key: String) = kread?.let { Platform.run(it, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key) }
    private fun kset(key: String, value: String) {
        val w = kwrite ?: return
        Platform.run(w, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key, value)
    }
    /** Running KDE programs re-read the setting on this signal. */
    private fun kNotify() {
        if (Platform.has("dbus-send")) Platform.run("dbus-send", "--type=signal", "/KIO/Scheduler", "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:")
    }

    /** "mode" for GNOME ('none' / 'manual' / 'auto'), with host:port of http. */
    fun current(): String? = when {
        gnome -> "${gget(GNOME, "mode")} ${gget("$GNOME.http", "host")}:${gget("$GNOME.http", "port")}"
        kread != null -> "kde type=${kget("ProxyType")} http=${kget("httpProxy")}"
        else -> null
    }

    /** Points the desktop at the local proxy; throws if no setting took it. */
    fun enable(httpPort: Int, socksPort: Int) {
        val ours = "127.0.0.1:$httpPort"
        if (Store.read("proxy_ours_linux") == null) {
            // Saved once, before the first change: a reconnect must not save our own values as "what was there"
            val saved = buildList {
                if (gnome) {
                    add("g mode=" + gget(GNOME, "mode"))
                    add("g ignore-hosts=" + gget(GNOME, "ignore-hosts"))
                    for (k in listOf("http", "https", "socks")) {
                        add("g $k.host=" + gget("$GNOME.$k", "host"))
                        add("g $k.port=" + gget("$GNOME.$k", "port"))
                    }
                }
                if (kread != null) for (k in listOf("ProxyType", "httpProxy", "httpsProxy", "socksProxy", "NoProxyFor"))
                    add("k $k=" + kget(k).orEmpty())
            }
            Store.write("proxy_saved_linux", saved.joinToString("\n"))
        }
        Store.write("proxy_ours_linux", ours)
        var done = false
        if (gnome) {
            for (k in listOf("http", "https")) { gset("$GNOME.$k", "host", "'127.0.0.1'"); gset("$GNOME.$k", "port", "$httpPort") }
            gset("$GNOME.socks", "host", "'127.0.0.1'"); gset("$GNOME.socks", "port", "$socksPort")
            gset(GNOME, "ignore-hosts", IGNORE.joinToString(", ", "[", "]") { "'$it'" })
            gset(GNOME, "mode", "'manual'")
            done = gget(GNOME, "mode") == "'manual'"
        }
        if (kwrite != null) {
            kset("httpProxy", "http://127.0.0.1 $httpPort")
            kset("httpsProxy", "http://127.0.0.1 $httpPort")
            kset("socksProxy", "socks://127.0.0.1 $socksPort")
            kset("NoProxyFor", IGNORE.joinToString(","))
            kset("ProxyType", "1")
            kNotify()
            done = done || kread == null || kget("ProxyType") == "1"
        }
        check(done) { "the desktop's proxy setting didn't change" }
    }

    /** Puts back what was there before. */
    fun disable() {
        Store.read("proxy_ours_linux") ?: return
        val saved = Store.read("proxy_saved_linux").orEmpty().lines().filter { it.length > 2 && '=' in it }
        for (line in saved) {
            val (key, value) = line.substring(2).split('=', limit = 2)
            when (line[0]) {
                'g' -> if (gnome && value.isNotEmpty() && value != "null") {
                    val dot = key.indexOf('.')
                    runCatching { if (dot < 0) gset(GNOME, key, value) else gset("$GNOME.${key.substring(0, dot)}", key.substring(dot + 1), value) }
                }
                'k' -> kset(key, value)
            }
        }
        // Nothing saved for GNOME (a crash before saving): no proxy
        if (gnome && saved.none { it.startsWith("g mode=") }) runCatching { gset(GNOME, "mode", "'none'") }
        if (kwrite != null) {
            if (saved.none { it.startsWith("k ProxyType=") }) kset("ProxyType", "0")
            kNotify()
        }
        Store.delete("proxy_ours_linux")
        Store.delete("proxy_saved_linux")
    }
}
