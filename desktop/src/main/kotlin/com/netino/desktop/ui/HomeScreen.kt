package com.netino.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.desktop.SearchProgress
import com.netino.vpn.core.NationalNet
import com.netino.vpn.data.Repository
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onMessage: (String) -> Unit) {
    val state by Controller.state.collectAsState()
    val traffic by Controller.traffic.collectAsState()
    val search by Controller.search.collectAsState()
    val rescue by Controller.rescue.collectAsState()
    val servers by Repository.servers.collectAsState()
    val settings by Repository.settings.collectAsState()
    val selected = remember(servers, settings.selectedServerId) { Repository.selectedServer() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        val idle = state is ConnState.Disconnected || state is ConnState.Error
        var netState by remember { mutableStateOf<NationalNet.State?>(null) }
        LaunchedEffect(idle) { if (idle) netState = NationalNet.check() }
        if (idle && netState == NationalNet.State.NATIONAL) {
            NationalBanner { Controller.connectNational { if (!it) onMessage(L.t("هیچ راهی وصل نشد", "Nothing got through")) } }
            Spacer(Modifier.height(16.dp))
        }

        val update by com.netino.desktop.Updater.state.collectAsState()
        if (update is com.netino.desktop.Updater.State.Available || update is com.netino.desktop.Updater.State.Downloading || update is com.netino.desktop.Updater.State.Ready) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.width(480.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { UpdateRow(onMessage) }
            }
            Spacer(Modifier.height(16.dp))
        }

        ConnectButton(state, busy = search != null) { Controller.toggle() }
        Spacer(Modifier.height(20.dp))

        val (title, color) = when (val s = state) {
            is ConnState.Connected -> L.t("وصل", "Connected") to Good
            is ConnState.Connecting -> L.t("در حال اتصال…", "Connecting…") to Warn
            is ConnState.Error -> L.t("خطا", "Error") to Bad
            ConnState.Disconnected -> (if (search != null) L.t("در حال جست‌وجو…", "Searching…") else L.t("قطع", "Disconnected")) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
        Spacer(Modifier.height(4.dp))
        val detail = when (val s = state) {
            is ConnState.Connected -> if (s.pool > 1) L.t("خودکار: ${s.pool} سرور • ${s.server.name}", "Auto: ${s.pool} servers • ${s.server.name}") else s.server.name
            is ConnState.Connecting -> s.server.name
            is ConnState.Error -> s.message
            ConnState.Disconnected -> selected?.name ?: L.t("سروری اضافه کنید", "Add a server")
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)

        (state as? ConnState.Connected)?.let { c ->
            Spacer(Modifier.height(16.dp))
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(c.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("↓ " + formatSpeed(traffic.downBps), formatBytes(traffic.sessionRx))
                Stat("↑ " + formatSpeed(traffic.upBps), formatBytes(traffic.sessionTx))
                Stat(formatDuration(now - c.since), L.t("مدت اتصال", "Time"))
            }
            if (Prefs.tunMode) {
                Spacer(Modifier.height(8.dp))
                Text(L.t("تونل کامل: همهٔ برنامه‌ها از Netino رد می‌شوند", "Full tunnel: every program goes through Netino"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else if (Prefs.systemProxy) {
                Spacer(Modifier.height(8.dp))
                Text(L.t("پراکسی ویندوز روی Netino تنظیم شد (HTTP 127.0.0.1:${settings.lanHttpPort} • SOCKS ${settings.lanSocksPort})",
                    "Windows proxy set to Netino (HTTP 127.0.0.1:${settings.lanHttpPort} • SOCKS ${settings.lanSocksPort})"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(20.dp))
        ServerCard()

        search?.let { p ->
            Spacer(Modifier.height(16.dp))
            SearchCard(p, rescue) { Controller.cancelSearch() }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { Controller.connectFastest { onMessage(L.t("هیچ سروری جواب نداد", "No server answered")) } }, enabled = search == null) {
                Icon(NIcons.Bolt, null); Spacer(Modifier.width(6.dp))
                Text(L.t("سریع‌ترین", "Fastest") + " • " + scopeLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            }
            OutlinedButton(onClick = { Controller.connectWarp { if (!it) onMessage(L.t("WARP وصل نشد", "WARP didn't connect")) } }, enabled = search == null) {
                Icon(NIcons.Cloud, null); Spacer(Modifier.width(6.dp)); Text("WARP")
            }
            OutlinedButton(onClick = { Controller.connectNational { if (!it) onMessage(L.t("هیچ راهی وصل نشد", "Nothing got through")) } }, enabled = search == null) {
                Icon(NIcons.PublicOff, null); Spacer(Modifier.width(6.dp)); Text(L.t("اینترنت ملی", "National internet"))
            }
        }
    }
}

/** The full tunnel was asked for without administrator rights. */
@Composable
fun AdminDialog(onMessage: (String) -> Unit) {
    val ask by Controller.needsAdmin.collectAsState()
    if (!ask) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { Controller.dismissAdmin() },
        title = { Text(L.t("تونل کامل دسترسی ادمین می‌خواهد", "The full tunnel needs administrator rights")) },
        text = { Text(L.t("برای ساختن کارت شبکهٔ مجازی، Netino باید با دسترسی ادمین اجرا شود. Netino دوباره باز می‌شود و ویندوز اجازه می‌خواهد. یا در تنظیمات حالت «پراکسی سیستم» را انتخاب کنید.",
            "To create its network adapter Netino must run as administrator. It will start again and Windows will ask. Or choose \"System proxy\" in Settings.")) },
        confirmButton = { androidx.compose.material3.Button(onClick = {
            if (!Controller.restartAsAdmin()) onMessage(L.t("اجرا با دسترسی ادمین انجام نشد", "Couldn't restart as administrator"))
        }) { Text(L.t("اجرای دوباره به‌عنوان ادمین", "Restart as administrator")) } },
        dismissButton = { TextButton(onClick = { Controller.dismissAdmin() }) { Text(L.t("انصراف", "Cancel")) } },
    )
}

@Composable
private fun ConnectButton(state: ConnState, busy: Boolean, onClick: () -> Unit) {
    val on = state is ConnState.Connected
    val working = state is ConnState.Connecting || busy
    val pulse = if (working) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse)).value
    } else 1f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Box(Modifier.size(196.dp).scale(pulse).clip(CircleShape)
            .background(if (on) BrandGradient else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)))
        Box(
            Modifier.size(160.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                .border(2.dp, if (on) Good else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NIcons.PowerSettingsNew, L.t("اتصال", "Connect"), Modifier.size(72.dp),
                tint = when { on -> Good; working -> Warn; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun stageText(p: SearchProgress, rescue: Int): String {
    val step = if (rescue > 0) L.t("مرحلهٔ $rescue از ${Controller.NATIONAL_STEPS} • ", "Step $rescue of ${Controller.NATIONAL_STEPS} • ") else ""
    return step + when (p.stage) {
        0 -> L.t("بررسی فایروال این شبکه…", "Checking this network's firewall…")
        1 -> L.t("تست سرورها ${p.done}/${p.total}", "Testing servers ${p.done}/${p.total}")
        2 -> L.t("تست سرعت ${p.done}/${p.total}", "Speed test ${p.done}/${p.total}") + (p.name?.let { " • $it" } ?: "")
        3 -> L.t("اتصال…", "Connecting…")
        Controller.STAGE_WARP + 1 -> L.t("ساخت حساب WARP…", "Making a WARP account…")
        Controller.STAGE_WARP + 2 -> L.t("جست‌وجوی نقطهٔ اتصال WARP ${p.done}/${p.total}", "Scanning WARP endpoints ${p.done}/${p.total}")
        Controller.STAGE_WARP + 3 -> L.t("بررسی WARP در WARP…", "Checking WARP in WARP…")
        else -> L.t("لطفاً صبر کنید…", "Please wait…")
    }
}

@Composable
private fun SearchCard(p: SearchProgress, rescue: Int, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(420.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stageText(p, rescue), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(L.t("لغو", "Cancel")) }
        }
    }
}

@Composable
fun NationalBanner(onConnect: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = Warn.copy(alpha = 0.15f), modifier = Modifier.width(480.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NIcons.PublicOff, null, tint = Warn)
            Spacer(Modifier.width(10.dp))
            Text(L.t("فقط سایت‌های ایرانی باز می‌شوند: اینترنت ملی", "Only Iranian sites open: national internet"),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onConnect) { Text(L.t("اتصال ویژه", "Connect"), color = Color.Unspecified) }
        }
    }
}
