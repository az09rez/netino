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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
            icon = icon,
            tooltip = "Netino • " + when (state) {
                is ConnState.Connected -> L.t("وصل", "Connected")
                is ConnState.Connecting -> L.t("در حال اتصال", "Connecting")
                else -> L.t("قطع", "Disconnected")
            },
            onAction = { visible = true },
            menu = {
                Item(if (Controller.isActive) L.t("قطع اتصال", "Disconnect") else L.t("اتصال", "Connect"), onClick = { Controller.toggle() })
                Item(L.t("نمایش Netino", "Show Netino"), onClick = { visible = true })
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
            NavigationRail(header = {
                Spacer(Modifier.height(8.dp))
                Image(painterResource("netino.png"), "Netino", Modifier.size(44.dp).clip(MaterialTheme.shapes.medium))
                Spacer(Modifier.height(8.dp))
            }) {
                val items = listOf(
                    Triple(NIcons.Home, L.t("خانه", "Home"), 0),
                    Triple(NIcons.Dns, L.t("سرورها", "Servers"), 1),
                    Triple(NIcons.BarChart, L.t("آمار", "Usage"), 4),
                    Triple(NIcons.Shield, L.t("ابزارها", "Tools"), 5),
                    Triple(NIcons.Settings, L.t("تنظیمات", "Settings"), 2),
                    Triple(NIcons.ReceiptLong, L.t("گزارش", "Log"), 3),
                )
                items.forEach { (icon, label, i) ->
                    NavigationRailItem(selected = tab == i, onClick = { Nav.tab.value = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
            VerticalDivider()
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
