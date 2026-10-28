package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.core.NationalNet
import com.netino.vpn.core.SpeedProbe
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.service.SearchProgress
import com.netino.vpn.service.Traffic
import com.netino.vpn.service.Updater
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import com.netino.vpn.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home (docs/design/canvas/A-Home.dc.html): banners, the connect button with its status, live traffic, the current
 * server, speed test, search progress, the last good connection and the actions; or, with no servers, three ways to
 * start. Every state of the board: off, connecting, searching, connected (single / auto), error, kill switch,
 * national internet, empty.
 */
@Composable
fun HomeScreen(
    modifier: Modifier,
    onToggle: () -> Unit,
    onPick: (Server) -> Unit,
    onFastest: () -> Unit,
    onAddServer: () -> Unit,
    onWarp: () -> Unit,
    onGuide: () -> Unit,
    onEmergency: () -> Unit,
    onNational: () -> Unit,
) {
    val ctx = LocalContext.current
    val state by VpnController.state.collectAsStateWithLifecycle()
    val traffic by VpnController.traffic.collectAsStateWithLifecycle()
    val search by VpnController.search.collectAsStateWithLifecycle()
    val emergencyStep by VpnController.emergency.collectAsStateWithLifecycle()
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val selected = servers.firstOrNull { it.id == settings.selectedServerId } ?: servers.firstOrNull()
    var picker by remember { mutableStateOf(false) }
    var emergencyOpen by remember { mutableStateOf(false) }

    // Size: small screens and large text get a smaller button so the rest stays visible
    val fontScale = LocalDensity.current.fontScale
    val size = when {
        fontScale >= 1.5f -> ConnectSize.Tiny
        LocalConfiguration.current.screenHeightDp < 700 || fontScale >= 1.3f -> ConnectSize.Compact
        else -> ConnectSize.Normal
    }

    val idle = state is VpnState.Disconnected || state is VpnState.Error
    val empty = servers.isEmpty()
    // National internet (only Iranian sites answer): checked whenever the VPN is off
    var netState by remember { mutableStateOf<NationalNet.State?>(null) }
    LaunchedEffect(idle) { if (idle) netState = NationalNet.check() }
    // Android's "Block connections without VPN" keeps the internet closed while Netino is off
    val killed = idle && XrayVpnService.lockdown == true

    val look = when {
        search != null -> ConnectLook.SEARCHING
        state is VpnState.Connecting -> ConnectLook.CONNECTING
        state is VpnState.Connected -> ConnectLook.CONNECTED
        state is VpnState.Error -> ConnectLook.ERROR
        else -> ConnectLook.OFF
    }
    // Emergency / national rescue: its own dialog shows the steps
    LaunchedEffect(emergencyStep) { if (emergencyStep > 0) emergencyOpen = true }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        HomeHeader(onGuide)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Banner(idle && netState == NationalNet.State.NATIONAL && !killed, BannerKind.WARN, Icons.Outlined.PublicOff,
                stringResource(R.string.national_banner), stringResource(R.string.national_banner_btn), onNational)
            Banner(killed, BannerKind.BAD, Icons.Outlined.GppMaybe, stringResource(R.string.kill_banner), stringResource(R.string.unblock)) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }

            Hero(look, state, search, size, empty, killed, onToggle)

            if (state is VpnState.Connected) TrafficCard(traffic)

            if (!empty) {
                val connected = state as? VpnState.Connected
                val auto = (connected?.pool ?: 1) > 1
                ServerCard(
                    caption = if (auto) stringResource(R.string.auto_mode, connected!!.pool) else stringResource(R.string.current_server),
                    name = if (auto) scopeLabel(settings.fastestScope) else (connected?.server ?: selected)?.name.orEmpty(),
                    avatar = { if (auto) IconAvatar(Icons.Outlined.AutoMode) else ServerAvatar((connected?.server ?: selected)!!.protocol) },
                    pill = { if (state is VpnState.Error) LatencyPill(-2) else (connected?.server ?: selected)?.let { s -> PingPill(servers.firstOrNull { it.id == s.id } ?: s) } },
                    onClick = { picker = true },
                )
            }

            if (state is VpnState.Connected) HomeSpeedTest()

            search?.let { s -> SearchCard(s) }

            if (state is VpnState.Disconnected && search == null && !empty) {
                val last = remember(servers) { VpnController.lastSuccessful() }
                last?.let { (server, time) -> LastConnectionCard(server, time) { onPick(server) } }
            }

            if (!empty && search == null && state !is VpnState.Connected && state !is VpnState.Connecting) {
                Actions(errorFirst = state is VpnState.Error, scopeKey = settings.fastestScope, onFastest = onFastest, onWarp = onWarp,
                    onEmergency = { emergencyOpen = true })
            }

            if (empty) EmptyCards(onAddServer, onWarp, onGuide)
        }
    }

    // Asked once, when Netino's service exists: anonymous results of the free list
    val hasApi = remember { com.netino.vpn.core.NetinoApi.base != null }
    if (hasApi && settings.introDone && settings.shareResults == null) AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.VolunteerActivism, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.results_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.results_body)) },
        confirmButton = { Button(onClick = { Repository.updateSettings { it.copy(shareResults = true) } }) { Text(stringResource(R.string.results_yes)) } },
        dismissButton = { TextButton(onClick = { Repository.updateSettings { it.copy(shareResults = false) } }) { Text(stringResource(R.string.results_no)) } },
    )

    if (emergencyOpen) EmergencyDialog(
        running = emergencyStep > 0,
        onStart = onEmergency,
        onClose = { emergencyOpen = false },
    )

    if (picker) ServerPickerSheet(
        scope = settings.fastestScope,
        selectedId = selected?.id,
        onPick = { picker = false; onPick(it) },
        onDismiss = { picker = false },
    )
}

// ---------------------------------------------------------------- header

@Composable
private fun HomeHeader(onGuide: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(36.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        UpdateBadge()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onGuide, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.guide), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- button + status

@Composable
private fun Hero(look: ConnectLook, state: VpnState, search: SearchProgress?, size: ConnectSize, empty: Boolean, killed: Boolean, onToggle: () -> Unit) {
    val st = MaterialTheme.status
    val title = when {
        empty -> stringResource(R.string.empty_title)
        look == ConnectLook.SEARCHING -> stringResource(R.string.home_searching)
        look == ConnectLook.CONNECTING -> stringResource(R.string.status_connecting)
        look == ConnectLook.CONNECTED -> stringResource(R.string.status_connected)
        look == ConnectLook.ERROR -> stringResource(R.string.status_error)
        else -> stringResource(R.string.status_disconnected)
    }
    val titleColor = when (look) {
        ConnectLook.CONNECTED -> st.goodText
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warnText
        ConnectLook.ERROR -> MaterialTheme.colorScheme.error
        ConnectLook.OFF -> MaterialTheme.colorScheme.onSurface
    }
    val on = look == ConnectLook.CONNECTED || look == ConnectLook.CONNECTING || look == ConnectLook.SEARCHING
    Column(Modifier.fillMaxWidth().padding(top = if (size == ConnectSize.Normal) 20.dp else 4.dp, bottom = if (size == ConnectSize.Normal) 12.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ConnectButton(
            look, size,
            label = stringResource(if (on) R.string.cd_disconnect else R.string.cd_connect),
            stateLabel = title,
            searchProgress = searchFraction(search),
            enabled = !empty,
            onClick = onToggle,
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = titleColor, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(2.dp))
        val sub = MaterialTheme.typography.bodyMedium
        val variant = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            state is VpnState.Connected -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                RollingText(localDigits(formatDuration(now - state.since)), MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    color = variant)
            }
            search != null -> Text(searchLine(search, state), style = sub, color = variant, textAlign = TextAlign.Center)
            state is VpnState.Connecting -> Text(state.server.name, style = sub, color = variant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            state is VpnState.Error -> Text(stringResource(R.string.home_error_sub), style = sub, color = variant, textAlign = TextAlign.Center)
            empty -> Text(stringResource(R.string.empty_sub), style = sub, color = variant, textAlign = TextAlign.Center)
            killed -> Text(stringResource(R.string.kill_banner_sub), style = sub, color = variant, textAlign = TextAlign.Center)
            else -> Text(stringResource(R.string.tap_to_connect), style = sub, color = variant, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- connected: traffic + speed test

/** Bits per second, as speed tests and providers write them (bytes/s in, "4.2 Mbps" out). */
fun formatBits(bytesPerSec: Long): String {
    val bits = bytesPerSec * 8.0
    return when {
        bits >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.US, bits / 1_000_000)
        bits >= 1_000 -> "%.0f Kbps".format(java.util.Locale.US, bits / 1_000)
        else -> "%.0f bps".format(java.util.Locale.US, bits)
    }
}

/** Down / up / this session; tapping it opens the last 60 seconds as a chart. */
@Composable
private fun TrafficCard(traffic: Traffic) {
    val motion = LocalMotion.current
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = !open }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(motion.spec(Motion.Medium))) {
        Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrafficValue(Icons.Outlined.ArrowDownward, MaterialTheme.colorScheme.primary, stringResource(R.string.download), localDigits(formatBits(traffic.downBps)), Modifier.weight(1f))
                TrafficValue(Icons.Outlined.ArrowUpward, MaterialTheme.colorScheme.secondary, stringResource(R.string.upload), localDigits(formatBits(traffic.upBps)), Modifier.weight(1f))
                TrafficValue(Icons.Outlined.DataUsage, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.this_session),
                    localDigits(formatBytes(traffic.sessionRx + traffic.sessionTx)), Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AreaChart(traffic.history.map { it.first }, Modifier.fillMaxWidth().height(44.dp), up = traffic.history.map { it.second })
                Text(stringResource(R.string.last_60s), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrafficValue(icon: ImageVector, tint: Color, label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        RollingText(value, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
    }
}

private data class SpeedResult(val ms: Long, val downKbps: Long, val upKbps: Long)

/** Speed test through the running connection; collapsed by default (its header shows the last download speed). */
@Composable
private fun HomeSpeedTest() {
    val ctx = LocalContext.current
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    fun mbps(kbps: Long) = "%.1f Mbps".format(java.util.Locale.US, kbps * 8 / 1000.0)
    fun run() {
        val ep = XrayVpnService.probe ?: return
        running = true
        scope.launch {
            result = withContext(Dispatchers.IO) {
                val ms = SpeedProbe.delay(ep.port, ep.user, ep.pass, Repository.settings.value.testUrl, tries = 3)
                val d = SpeedProbe.run(ep.port, ep.user, ep.pass, timeoutMs = 10_000)
                val u = SpeedProbe.upload(ep.port, ep.user, ep.pass)
                SpeedResult(ms, d.kbps, u.kbps)
            }
            running = false
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(motion.spec(Motion.Medium))) {
        Column {
            Surface(onClick = { open = !open; if (open && result == null && !running) run() }, color = Color.Transparent) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Speed, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.speed_test), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    result?.let { Text("↓ " + localDigits(mbps(it.downKbps)), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                val r = result
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTile(stringResource(R.string.latency), r?.let { if (it.ms > 0) localDigits("${it.ms}") + "ms" else "—" } ?: "…", Modifier.weight(1f))
                    SmallTile(stringResource(R.string.download), r?.let { localDigits(mbps(it.downKbps)) } ?: "…", Modifier.weight(1f))
                    SmallTile(stringResource(R.string.upload), r?.let { localDigits(mbps(it.upKbps)) } ?: "…", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (r != null) TextButton(onClick = {
                        val text = "Netino speed test\nPing: ${if (r.ms > 0) "${r.ms} ms" else "-"}   Download: ${mbps(r.downKbps)}   Upload: ${mbps(r.upKbps)}"
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Netino", text))
                        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.copy_result))
                    }
                    FilledTonalButton(onClick = ::run, enabled = !running) { Text(stringResource(R.string.test_again)) }
                }
            }
        }
    }
}

@Composable
private fun SmallTile(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- searching

/** Steps of the running search: quick connect (firewall, servers, speed, connect) or WARP (saved, account, scan, chain). */
@Composable
private fun SearchCard(search: SearchProgress) {
    val warp = search.stage >= VpnController.STAGE_WARP_CHECK
    val steps = if (warp) listOf(stringResource(R.string.step_warp_saved), stringResource(R.string.step_warp_account),
        stringResource(R.string.step_warp_scan), stringResource(R.string.step_warp_chain))
    else listOf(stringResource(R.string.step_firewall), stringResource(R.string.step_servers), stringResource(R.string.step_speed), stringResource(R.string.step_connect))
    val current = (if (warp) search.stage - VpnController.STAGE_WARP_CHECK else search.stage).coerceIn(0, 3)
    // The firewall check and account steps have no count: indeterminate
    val fraction = if (search.total > 0) search.done.toFloat() / search.total else null
    ProgressCard(
        title = stringResource(R.string.step_of, localDigits("${current + 1}"), localDigits("4"), searchLine(search, VpnState.Disconnected)),
        steps = steps, current = current, fraction = fraction, onCancel = { VpnController.cancelSearch() },
    )
}

// ---------------------------------------------------------------- off: last good connection + actions

@Composable
private fun LastConnectionCard(server: Server, time: Long, onReconnect: () -> Unit) {
    val ago = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.last_ok, localDigits(ago)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = onReconnect, modifier = Modifier.heightIn(min = 40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text(stringResource(R.string.reconnect))
            }
        }
    }
}

/**
 * Fastest (with its scope) and WARP, 56dp; WARP is the filled one, except after an error, when "fastest" is the next
 * thing to try and swaps with it. Emergency mode is an underlined text button under them.
 */
@Composable
private fun Actions(errorFirst: Boolean, scopeKey: String, onFastest: () -> Unit, onWarp: () -> Unit, onEmergency: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val filled = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)
    val tonal = ButtonDefaults.buttonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)
    val shape = MaterialTheme.shapes.medium
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onFastest, colors = if (errorFirst) filled else tonal, shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = ActionHeight)) {
                Icon(Icons.Outlined.Speed, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fastest_connect), style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), textAlign = TextAlign.Center)
            }
            ScopePicker(scopeKey)
        }
        Button(onClick = onWarp, colors = if (errorFirst) tonal else filled, shape = shape,
            modifier = Modifier.fillMaxWidth().heightIn(min = ActionHeight)) {
            Icon(Icons.Outlined.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.warp_home), style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
        }
        TextButton(onClick = onEmergency, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.emergency_button), style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                textAlign = TextAlign.Center)
        }
    }
}

/** Where "connect to fastest" searches: all servers, the user's own, one subscription or one group. */
@Composable
private fun ScopePicker(scopeKey: String) {
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val options = scopeOptions(subs, groups)
    val current = options.firstOrNull { it.first == scopeKey }?.second ?: options.first().second
    val short = if (scopeKey == "all") stringResource(R.string.scope_short_all) else current
    Box {
        OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(start = 12.dp, end = 6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.heightIn(min = ActionHeight).widthIn(max = 140.dp)) {
            Text(short, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.scope_choose, current), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text(stringResource(R.string.fastest_scope), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        Repository.updateSettings { it.copy(fastestScope = key) }
                        // The server card and its list now show this scope: pick its best server if the current one isn't in it
                        val pool = Repository.fastestCandidates(key)
                        if (pool.none { it.id == Repository.settings.value.selectedServerId }) {
                            pool.minByOrNull { it.score ?: if (it.lastPingMs > 0) it.lastPingMs * 10 else Long.MAX_VALUE }?.let { Repository.select(it.id) }
                        }
                        open = false
                    },
                    leadingIcon = { RadioButton(selected = key == scopeKey, onClick = null) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- empty

/** No servers yet: add one, WARP (needs none), or the guide. Netino's free list is fetched meanwhile. */
@Composable
private fun EmptyCards(onAdd: () -> Unit, onWarp: () -> Unit, onGuide: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var attempt by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        loading = true
        failed = null
        Repository.refreshSubscription(Repository.BUILTIN_SUB_ID).onFailure { failed = subscriptionMessage(ctx, it) }
        loading = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyCard(Icons.Outlined.AddCircleOutline, stringResource(R.string.add_first_server), stringResource(R.string.empty_add_sub), cs.primary, cs.onPrimary, onAdd)
        EmptyCard(Icons.Outlined.Bolt, stringResource(R.string.warp_home), stringResource(R.string.empty_warp_sub), cs.primaryContainer, cs.onPrimaryContainer, onWarp)
        EmptyCard(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.guide), stringResource(R.string.empty_help_sub), cs.surfaceContainer, cs.onSurface, onGuide)
        // Netino's own free list: arriving, or why not
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.status.warnText)
            Text(if (loading) stringResource(R.string.free_list_loading) else listOfNotNull(stringResource(R.string.free_list_failed), failed).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!loading && failed != null) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, sub: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = fg.copy(alpha = 0.85f))
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
        }
    }
}

// ---------------------------------------------------------------- dialogs

/**
 * Emergency / national rescue: before it starts, its steps and "Start"; while it runs, each step's state
 * (failed, testing, queued). Hiding keeps it running; Stop cancels it.
 */
@Composable
private fun EmergencyDialog(running: Boolean, onStart: () -> Unit, onClose: () -> Unit) {
    val step by VpnController.emergency.collectAsStateWithLifecycle()
    val national by VpnController.national.collectAsStateWithLifecycle()
    val search by VpnController.search.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    // Closes by itself once the rescue ends connected
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(running, state) {
        if (running) started = true
        if (started && !running && state is VpnState.Connected) onClose()
    }
    val names = if (national && started) listOf(R.string.national_step1, R.string.national_step2, R.string.national_step3, R.string.emergency_step2, R.string.emergency_step3)
        else listOf(R.string.emergency_step1, R.string.emergency_step2, R.string.emergency_step3, R.string.emergency_step4)
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        icon = { Icon(Icons.Outlined.Warning, null, tint = cs.primary) },
        title = { Text(stringResource(if (national && started) R.string.national_title else R.string.em_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.em_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                names.forEachIndexed { i, res ->
                    val n = i + 1
                    val failed = started && (n < step || (!running && step == 0))
                    val testing = running && n == step
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(when {
                            testing -> cs.primary; failed -> cs.errorContainer; else -> cs.surfaceContainerHighest
                        }), contentAlignment = Alignment.Center) {
                            Text(localDigits("$n"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                color = when { testing -> cs.onPrimary; failed -> cs.error; else -> cs.onSurfaceVariant })
                        }
                        Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            color = if (testing || failed) cs.onSurface else cs.onSurfaceVariant)
                        Text(stringResource(when { testing -> R.string.step_testing; failed -> R.string.step_failed; else -> R.string.step_queued }),
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (running) Text(searchLine(search, state), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = {
            if (running) TextButton(onClick = onClose) { Text(stringResource(R.string.search_hide)) }
            else Button(onClick = { started = true; onStart() }) { Text(stringResource(R.string.em_start)) }
        },
        dismissButton = {
            TextButton(onClick = { if (running) VpnController.cancelSearch(); onClose() }) {
                Text(stringResource(if (running) R.string.stop else R.string.cancel), color = if (running) cs.error else cs.primary)
            }
        },
    )
}

// ---------------------------------------------------------------- shared helpers

/** One line describing the running search (quick connect or WARP). */
@Composable
private fun searchLine(search: SearchProgress?, state: VpnState): String = when (search?.stage) {
    null -> if (state is VpnState.Connecting) stringResource(R.string.status_connecting) else stringResource(R.string.fastest_stage1, 0, 0)
    0 -> stringResource(R.string.fastest_stage0)
    1 -> stringResource(R.string.home_search_servers, localDigits("${search.done}"), localDigits("${search.total}"))
    2 -> stringResource(R.string.fastest_stage3, search.done, search.total, search.name.orEmpty())
    VpnController.STAGE_WARP_CHECK -> stringResource(R.string.warp_stage0, search.total)
    VpnController.STAGE_WARP_CHECK + 1 -> stringResource(R.string.warp_stage1)
    VpnController.STAGE_WARP_CHECK + 2 -> stringResource(R.string.warp_stage2, search.done, search.total)
    VpnController.STAGE_WARP_CHECK + 3 -> stringResource(R.string.warp_stage3)
    else -> stringResource(R.string.fastest_connecting, search.total)
}

/** Overall progress of a search over its four steps (for the ring around the button). */
private fun searchFraction(search: SearchProgress?): Float {
    if (search == null) return 0f
    val f = if (search.total == 0) 0f else search.done.toFloat() / search.total
    val step = (if (search.stage >= VpnController.STAGE_WARP_CHECK) search.stage - VpnController.STAGE_WARP_CHECK else search.stage).coerceIn(0, 3)
    return (step + f) / 4f
}

/** "New version" chip next to the app name; downloads, verifies and installs the new version. */
@Composable
private fun UpdateBadge() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf<Updater.Release?>(null) }
    val (label, release) = when (val u = st) {
        is Updater.State.Available -> stringResource(R.string.new_version) to u.release
        is Updater.State.Downloading -> localDigits("${(u.progress * 100).toInt()}٪") to null
        is Updater.State.Ready -> stringResource(R.string.update_install) to u.release
        is Updater.State.Failed -> stringResource(R.string.update_retry) to u.release
        Updater.State.Idle -> return
    }
    Surface(
        onClick = {
            when (val u = st) {
                is Updater.State.Ready -> Updater.install(ctx, u.file)
                else -> ask = release
            }
        },
        enabled = release != null,
        shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.height(28.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    ask?.let { r ->
        AlertDialog(
            onDismissRequest = { ask = null },
            icon = { Icon(Icons.Outlined.SystemUpdate, null) },
            title = { Text(stringResource(R.string.update_title, r.version)) },
            text = { Text(stringResource(R.string.update_body)) },
            confirmButton = {
                Button(onClick = { ask = null; scope.launch { Updater.downloadAndInstall(ctx.applicationContext, r) } }) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.later)) } },
        )
    }
}

/** Choose a server: filter by where it comes from, search, and star the ones to keep at the top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerSheet(scope: String, selectedId: String?, onPick: (Server) -> Unit, onDismiss: () -> Unit) {
    val all by Repository.servers.collectAsStateWithLifecycle()
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    val connectedId = (state as? VpnState.Connected)?.server?.id
    val cs = MaterialTheme.colorScheme
    val options = scopeOptions(subs, groups)
    // Starts on the scope chosen next to "connect to fastest"
    var picked by remember { mutableStateOf(options.firstOrNull { it.first == scope }?.first ?: "all") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) } // 0 best, 1 delay, 2 name
    var sortMenu by remember { mutableStateOf(false) }
    val sortNames = listOf(R.string.sort_best, R.string.sort_ping, R.string.sort_name)
    val manual = stringResource(R.string.manual_servers)
    val builtin = stringResource(R.string.builtin_sub)
    val warp = stringResource(R.string.warp_section)
    fun source(s: Server): String = when (val id = s.subscriptionId) {
        null -> manual
        com.netino.vpn.core.Warp.SUB -> warp
        else -> subs.firstOrNull { it.id == id }?.let { if (it.builtIn) builtin else it.name } ?: ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if (isDarkSurface()) cs.surfaceContainer else cs.background) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.choose_server), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Box {
                    val name = stringResource(sortNames[sort])
                    val sortCd = stringResource(R.string.sort_cd, name)
                    TextButton(onClick = { sortMenu = true }, colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = sortCd }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text(name)
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, shape = MaterialTheme.shapes.medium) {
                        sortNames.forEachIndexed { i, r ->
                            DropdownMenuItem({ Text(stringResource(r)) }, { sort = i; sortMenu = false }, leadingIcon = { RadioButton(i == sort, null) })
                        }
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.first }) { (key, label) ->
                    FilterChip(picked == key, onClick = { picked = key }, label = { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)) },
                        leadingIcon = if (picked == key) { { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) } } else null,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.height(36.dp))
                }
            }
            val scopeName = options.firstOrNull { it.first == picked }?.second.orEmpty()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(cs.surfaceContainerHigh).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Search, null, tint = cs.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_in, scopeName), color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth()
                            .semantics { contentDescription = scopeName })
                }
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
            }
            val favs = settings.favorites
            val list = remember(all, picked, query, sort, favs) {
                Repository.fastestCandidates(picked).filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
                    .let { l ->
                        when (sort) {
                            1 -> l.sortedWith(compareBy<Server>({ if (it.lastPingMs > 0) 0 else 1 }, { it.lastPingMs }))
                            2 -> l.sortedBy { it.name.lowercase() }
                            else -> l.sortedWith(compareBy<Server>({ it.score ?: Long.MAX_VALUE }, { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE }))
                        }
                    }
                    .sortedBy { if (it.id in favs) 0 else 1 }
            }
            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                items(list, key = { it.id }) { s ->
                    val starred = s.id in favs
                    ServerItem(
                        s, selected = s.id == selectedId, connected = s.id == connectedId, onClick = { onPick(s) },
                        shape = RectangleShape, container = Color.Transparent,
                        subtitle = "${s.protocol.label.lowercase()} · ${source(s)}",
                        modifier = Modifier.animateItem(),
                        trailing = {
                            val label = stringResource(if (starred) R.string.unstar else R.string.star, s.name)
                            IconButton(onClick = { Repository.toggleFavorite(s.id) }, modifier = Modifier.semantics { this.selected = starred }) {
                                Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline, label,
                                    tint = if (starred) (if (isDarkSurface()) Color(0xFFFBBF24) else Color(0xFFB7791F)) else cs.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun scopeOptions(subs: List<com.netino.vpn.data.Subscription>, groups: List<com.netino.vpn.data.ServerGroup>) = buildList {
    add("all" to stringResource(R.string.scope_all))
    add("manual" to stringResource(R.string.manual_servers))
    subs.forEach { add("sub:${it.id}" to if (it.builtIn) stringResource(R.string.builtin_sub) else it.name) }
    groups.forEach { add("group:${it.id}" to it.name) }
}

@Composable
private fun scopeLabel(scope: String): String {
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    val options = scopeOptions(subs, groups)
    return options.firstOrNull { it.first == scope }?.second ?: options.first().second
}
