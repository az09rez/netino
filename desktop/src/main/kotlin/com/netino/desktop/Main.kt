package com.netino.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
    if (!SingleInstance.acquire(wait = "--restarted" in args)) return
    Repository.init(Store.context)
    XrayCore.init(Store.context)
    Controller.init()
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
        val fa by Prefs.faFlow.collectAsState()
        val theme by Prefs.theme.collectAsState()
        val state by Controller.state.collectAsState()
        val icon = painterResource("netino.png")
        fun quit() { Controller.shutdown(); exitApplication() }

        // Windows notifications from the tray icon
        val trayState = androidx.compose.ui.window.rememberTrayState()
        LaunchedEffect(Unit) {
            Controller.notices.collect { (title, text) -> trayState.sendNotification(androidx.compose.ui.window.Notification(title, text)) }
        }
        LaunchedEffect(Unit) {
            Updater.state.collect { if (it is Updater.State.Available) Controller.notify(L.t("نسخهٔ جدید Netino", "New Netino version"), it.release.version) }
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

        Window(
            onCloseRequest = { if (Prefs.closeToTray) visible = false else quit() },
            visible = visible,
            title = "Netino",
            icon = icon,
            state = rememberWindowState(width = 1000.dp, height = 720.dp),
        ) {
            LaunchedEffect(visible) { Prefs.windowVisible = visible; if (visible) window.toFront() }
            // Self-test: the window has to draw every tab without an exception, then the app exits
            if (selfTest) LaunchedEffect(Unit) {
                for (t in 0..5) { Nav.tab.value = t; kotlinx.coroutines.delay(2500) }
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

/** The selected tab (a flow, so the self-test can walk through the tabs). */
private object Nav { val tab = kotlinx.coroutines.flow.MutableStateFlow(0) }

@Composable
private fun App() {
    val tab by Nav.tab.collectAsState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            val items = listOf(
                0 to com.netino.desktop.ui.NavEntry(L.t("خانه", "Home"), NIcons.Shield, NIcons.ShieldFilled),
                1 to com.netino.desktop.ui.NavEntry(L.t("سرورها", "Servers"), NIcons.Dns),
                4 to com.netino.desktop.ui.NavEntry(L.t("آمار", "Usage"), NIcons.BarChart),
                5 to com.netino.desktop.ui.NavEntry(L.t("ابزارها", "Tools"), NIcons.Build),
                2 to com.netino.desktop.ui.NavEntry(L.t("تنظیمات", "Settings"), NIcons.Settings),
                3 to com.netino.desktop.ui.NavEntry(L.t("گزارش", "Log"), NIcons.ReceiptLong),
            )
            androidx.compose.foundation.layout.BoxWithConstraints {
                // Narrow window: icons only
                com.netino.desktop.ui.NetinoRail(items.map { it.second }, items.indexOfFirst { it.first == tab }, narrow = maxWidth < 600.dp) {
                    Nav.tab.value = items[it].first
                }
            }
            com.netino.desktop.ui.AdminDialog(message)
            Box(Modifier.weight(1f)) {
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
