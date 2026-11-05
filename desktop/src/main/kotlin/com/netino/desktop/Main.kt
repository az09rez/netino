package com.netino.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.collectLatest
import com.netino.desktop.ui.AppTheme
import com.netino.desktop.ui.NIcons
import com.netino.desktop.ui.HomeScreen
import com.netino.desktop.ui.LogScreen
import com.netino.desktop.ui.ServersScreen
import com.netino.desktop.ui.SettingsScreen
import com.netino.vpn.core.GeoFiles
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    // A notification button: hand its command to the running Netino (or, if none runs, do it after starting)
    val command = args.firstOrNull { it.startsWith("netino://") }
    if (!SingleInstance.acquire(wait = "--restarted" in args, command = command)) return
    Repository.init(Store.context)
    XrayCore.init(Store.context)
    Controller.init()
    Toast.register()
    // Whatever way the app ends (sign-out, shutdown), Windows must not keep pointing at a proxy that is gone
    Runtime.getRuntime().addShutdownHook(Thread { Controller.shutdown() })
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        Repository.refreshDueSubscriptions()
        SubAlerts.check()
        // The Iran lists aren't in the download (they'd double its size): fetched on first start, and kept for a
        // national-internet shutdown, when they can't be fetched any more
        if (!XrayCore.hasGeoFiles && "--selftest" !in args) GeoFiles.update()
            .onSuccess { Repository.updateSettings { it.copy(geoUpdatedAt = System.currentTimeMillis()) } }
    }
    val selfTest = "--selftest" in args
    val failures = if (selfTest) SelfTest.run() else 0
    if (!selfTest && (Prefs.connectOnStart || "--connect" in args)) Controller.connectFastestOrSelected()
    if (!selfTest) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { Updater.check() }
    val startHidden = "--tray" in args

    if (!selfTest && Prefs.hotkey) Hotkey.start { Controller.toggle() }
    // Subscriptions running out, checked again every few hours
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        while (true) { kotlinx.coroutines.delay(6 * 3_600_000L); runCatching { Repository.refreshDueSubscriptions(); SubAlerts.check() } }
    }

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        SingleInstance.onShow = { visible = true }
        val cmdScope = rememberCoroutineScope()
        fun run(cmd: String) {
            when (cmd.trimEnd('/')) {
                Commands.FASTEST -> Controller.connectFastest()
                Commands.RELEASE -> Controller.disconnect()
                Commands.UPDATE -> {
                    visible = true
                    (Updater.state.value as? Updater.State.Available)?.let { a -> cmdScope.launch { Updater.download(a.release) } }
                }
                else -> visible = true
            }
        }
        SingleInstance.onCommand = { run(it) }
        LaunchedEffect(Unit) { command?.let { run(it) } }
        val fa by Prefs.faFlow.collectAsState()
        val theme by Prefs.theme.collectAsState()
        val state by Controller.state.collectAsState()
        val icon = painterResource("netino.png")
        fun quit() { Controller.shutdown(); exitApplication() }

        // Windows notifications from the tray icon
        val trayState = androidx.compose.ui.window.rememberTrayState()
        LaunchedEffect(Unit) {
            // With buttons: a Windows toast (the tray's own notifications can't have any); else, or if it fails, the tray's
            Controller.notices.collect { n ->
                val shown = n.actions.isNotEmpty() && kotlinx.coroutines.withContext(Dispatchers.IO) { Toast.show(n) }
                if (!shown) trayState.sendNotification(androidx.compose.ui.window.Notification(n.title, n.text))
            }
        }
        LaunchedEffect(Unit) {
            Updater.state.collect {
                if (it is Updater.State.Available) Controller.notify(L.t("نسخهٔ جدید نتینو", "New Netino version"), com.netino.desktop.ui.localDigits(it.release.version),
                    listOf(L.t("به‌روزرسانی", "Update") to Commands.UPDATE))
            }
        }
        Tray(
            state = trayState,
            icon = remember(state is ConnState.Connected, state is ConnState.Connecting, state is ConnState.Error) { StatusIcon(icon, statusDot(state)) },
            tooltip = trayTooltip(state),
            onAction = { visible = true },
            menu = {
                Item(if (Controller.isActive) L.t("قطع اتصال", "Disconnect") else L.t("اتصال", "Connect"), onClick = { Controller.toggle() })
                Item(L.t("نمایش نتینو", "Show Netino"), onClick = { visible = true })
                Separator()
                Item(L.t("خروج", "Exit"), onClick = { quit() })
            },
        )

        val windowState = remember { restoredWindowState() }
        Window(
            onCloseRequest = { if (Prefs.closeToTray) visible = false else quit() },
            visible = visible,
            title = "Netino",
            icon = icon,
            state = windowState,
            onPreviewKeyEvent = { e -> shortcut(e) },
        ) {
            LaunchedEffect(visible) { Prefs.windowVisible = visible; if (visible) window.toFront() }
            // Smaller than this and the pages stop fitting
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(720, 560) }
            val dark = when (theme) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            LaunchedEffect(dark) { Win.setDarkTitleBar(window.windowHandle, dark) }
            // Opens where it was left, at the size it had
            LaunchedEffect(windowState) {
                androidx.compose.runtime.snapshotFlow { Triple(windowState.position, windowState.size, windowState.placement) }
                    .collectLatest { (pos, size, placement) -> kotlinx.coroutines.delay(800); saveBounds(pos, size, placement) }
            }
            // Self-test: the window has to draw every tab without an exception, then the app exits
            if (selfTest) LaunchedEffect(Unit) {
                for (t in 0..5) { com.netino.desktop.ui.Ui.tab.value = t; kotlinx.coroutines.delay(2500) }
                SelfTest.report("PASS window: drew every tab")
                Controller.shutdown()
                kotlin.system.exitProcess(failures)
            }
            AppTheme(theme, fa) { key(fa) { App() } }
        }
    }
}

/** "Netino — connected · server · 00:12:48" (ticks while connected). */
@Composable
private fun trayTooltip(state: ConnState): String {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (state is ConnState.Connected) LaunchedEffect(state.since) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val name = L.t("نتینو", "Netino")
    return when (state) {
        is ConnState.Connected -> "$name — " + L.t("متصل", "Connected") + " · " + state.server.name + " · " +
            com.netino.desktop.ui.localDigits(com.netino.desktop.ui.formatDuration(now - state.since))
        is ConnState.Connecting -> "$name — " + L.t("در حال اتصال", "Connecting")
        is ConnState.Error -> "$name — " + L.t("اتصال برقرار نشد", "Couldn't connect")
        else -> "$name — " + L.t("متصل نیست", "Not connected")
    }
}

private fun statusDot(state: ConnState): androidx.compose.ui.graphics.Color? = when (state) {
    is ConnState.Connected -> com.netino.desktop.ui.Good
    is ConnState.Connecting -> com.netino.desktop.ui.Warn
    is ConnState.Error -> com.netino.desktop.ui.Bad
    else -> null
}

/** The tray icon with a small status dot in its corner (green connected, orange connecting, red error). */
private class StatusIcon(private val base: androidx.compose.ui.graphics.painter.Painter, private val dot: androidx.compose.ui.graphics.Color?) :
    androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize get() = base.intrinsicSize
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        with(base) { draw(size) }
        val c = dot ?: return
        val r = size.minDimension * 0.17f
        val center = androidx.compose.ui.geometry.Offset(size.width - r * 1.3f, size.height - r * 1.3f)
        drawCircle(androidx.compose.ui.graphics.Color(0xFF101826), r * 1.35f, center)
        drawCircle(c, r, center)
    }
}


/** The window as it was left (size, place, maximized), or 1000×720 centered the first time. */
private fun restoredWindowState(): androidx.compose.ui.window.WindowState {
    val v = Prefs.windowBounds.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (v.size < 5 || v[2] < 720 || v[3] < 560) return androidx.compose.ui.window.WindowState(
        position = androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.Center),
        size = androidx.compose.ui.unit.DpSize(1000.dp, 720.dp))
    // A position off every screen now (a monitor unplugged) falls back to the centre
    val onScreen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .any { it.defaultConfiguration.bounds.contains(v[0].toInt() + 40, v[1].toInt() + 20) }
    return androidx.compose.ui.window.WindowState(
        placement = if (v[4] > 0) androidx.compose.ui.window.WindowPlacement.Maximized else androidx.compose.ui.window.WindowPlacement.Floating,
        position = if (onScreen) androidx.compose.ui.window.WindowPosition(v[0].dp, v[1].dp)
            else androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.Center),
        size = androidx.compose.ui.unit.DpSize(v[2].dp, v[3].dp),
    )
}

private fun saveBounds(pos: androidx.compose.ui.window.WindowPosition, size: androidx.compose.ui.unit.DpSize,
                       placement: androidx.compose.ui.window.WindowPlacement) {
    val max = placement == androidx.compose.ui.window.WindowPlacement.Maximized
    // Maximized: keep the last normal size and place, so un-maximizing goes back to them
    val old = Prefs.windowBounds.split(',')
    if (max) { if (old.size >= 4) Prefs.windowBounds = old.take(4).joinToString(",") + ",1"; return }
    if (placement != androidx.compose.ui.window.WindowPlacement.Floating || !pos.isSpecified) return
    Prefs.windowBounds = "${pos.x.value},${pos.y.value},${size.width.value},${size.height.value},0"
}


@Composable
private fun App() {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            com.netino.desktop.ui.AdminDialog(message)
            com.netino.desktop.ui.AppShell(message) { tab ->
                when (tab) {
                    0 -> HomeScreen(message)
                    1 -> ServersScreen(message)
                    2 -> SettingsScreen(message)
                    4 -> com.netino.desktop.ui.StatsScreen()
                    5 -> com.netino.desktop.ui.ToolsScreen(message)
                    else -> LogScreen(message)
                }
            }
        }
    }
}

/**
 * Keyboard shortcuts: Ctrl+K finder, Ctrl+N add (the dialog fills in the clipboard), Ctrl+Enter connect / disconnect,
 * Ctrl+B the servers panel, Ctrl+, settings, Ctrl+1…6 the pages.
 */
private fun shortcut(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !e.isCtrlPressed) return false
    val k = e.key
    val ui = com.netino.desktop.ui.Ui
    when (k) {
        androidx.compose.ui.input.key.Key.K -> ui.finderOpen.value = true
        androidx.compose.ui.input.key.Key.N -> ui.addOpen.value = true
        androidx.compose.ui.input.key.Key.Enter -> Controller.toggle()
        androidx.compose.ui.input.key.Key.B -> ui.sidebar.value = !(ui.sidebar.value ?: true)
        androidx.compose.ui.input.key.Key.Comma -> ui.tab.value = 2
        else -> {
            val n = listOf(androidx.compose.ui.input.key.Key.One, androidx.compose.ui.input.key.Key.Two, androidx.compose.ui.input.key.Key.Three,
                androidx.compose.ui.input.key.Key.Four, androidx.compose.ui.input.key.Key.Five, androidx.compose.ui.input.key.Key.Six).indexOf(k)
            if (n < 0) return false
            ui.tab.value = ui.TABS[n].first
        }
    }
    return true
}
