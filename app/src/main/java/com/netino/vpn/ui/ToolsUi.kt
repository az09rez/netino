package com.netino.vpn.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.core.CdnDetector
import com.netino.vpn.core.Warp
import androidx.compose.material.icons.outlined.Shield
import com.netino.vpn.core.CleanIpScanner
import com.netino.vpn.data.CdnMethod
import com.netino.vpn.data.NetKey
import com.netino.vpn.data.firewall
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.RadioButton
import com.netino.vpn.core.SpeedProbe
import com.netino.vpn.data.FragmentMode
import com.netino.vpn.data.Repository
import com.netino.vpn.service.AppWatchService
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import com.netino.vpn.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- anti-censorship

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiCensorshipCard() {
    val s by Repository.settings.collectAsStateWithLifecycle()
    var scanner by remember { mutableStateOf(false) }
    var cdnSheet by remember { mutableStateOf(false) }
    var warpSheet by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.anti_censorship)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable { warpSheet = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.warp), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.warp_row_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable { cdnSheet = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cdn_path), style = MaterialTheme.typography.bodyLarge)
                Text(cdnSummary(s.cdnForced, s.cdnByNet[NetKey.current]), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.fragment), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        val options = listOf(FragmentMode.OFF to R.string.mode_off, FragmentMode.AUTO to R.string.fragment_auto, FragmentMode.ALWAYS to R.string.fragment_always)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (mode, label) ->
                SegmentedButton(selected = s.fragmentMode == mode, onClick = { Repository.updateSettings { it.copy(fragmentMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Text(stringResource(R.string.fragment_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        ToggleRow(stringResource(R.string.wg_noise), s.wgNoise, desc = stringResource(R.string.wg_noise_desc)) { v ->
            Repository.updateSettings { it.copy(wgNoise = v) }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable { scanner = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.clean_ip), style = MaterialTheme.typography.bodyLarge)
                Text(if (s.cleanIp.isBlank()) stringResource(R.string.clean_ip_none) else stringResource(R.string.clean_ip_using, s.cleanIp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.cleanIp6.isNotBlank()) Text(stringResource(R.string.clean_ip6_using, s.cleanIp6),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.Radar, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (scanner) CleanIpSheet(onDismiss = { scanner = false })
    if (cdnSheet) CdnSheet(onDismiss = { cdnSheet = false })
    if (warpSheet) WarpSheet(onDismiss = { warpSheet = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarpSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Warp.Mode.WARP) }
    var ipv6 by remember { mutableStateOf<Boolean?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val search by VpnController.search.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = { if (!running) onDismiss() }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.warp), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.warp_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                listOf(Warp.Mode.WARP to R.string.warp_mode_plain, Warp.Mode.WARP_IN_WARP to R.string.warp_mode_wiw).forEachIndexed { i, (m, label) ->
                    SegmentedButton(selected = mode == m, onClick = { if (!running) mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label), maxLines = 1) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                listOf(null to R.string.ip_auto, false to R.string.clean_ip_v4, true to R.string.clean_ip_v6).forEachIndexed { i, (on, label) ->
                    SegmentedButton(selected = ipv6 == on, onClick = { if (!running) ipv6 = on },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
                }
            }
            val live = search?.takeIf { running }?.let { sp ->
                when (sp.stage) {
                    VpnController.STAGE_WARP_CHECK -> stringResource(R.string.warp_stage0, sp.total)
                    VpnController.STAGE_WARP_CHECK + 1 -> stringResource(R.string.warp_stage1)
                    VpnController.STAGE_WARP_CHECK + 2 -> stringResource(R.string.warp_stage2, sp.done, sp.total)
                    VpnController.STAGE_WARP_CHECK + 3 -> stringResource(R.string.warp_stage3)
                    else -> stringResource(R.string.status_connecting)
                }
            }
            (live ?: status)?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp)) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
            else Button(onClick = {
                running = true
                status = null
                // Connects right away when the VPN permission is already there; otherwise only finds and saves
                val canConnect = android.net.VpnService.prepare(ctx) == null
                VpnController.connectWarp(mode, ipv6, fresh = true, connect = canConnect) { f ->
                    status = when (f) {
                        null -> ctx.getString(if (canConnect) R.string.warp_connected else R.string.warp_saved)
                        VpnController.WarpFailure.NoEndpoint -> ctx.getString(R.string.warp_none)
                        VpnController.WarpFailure.Connect -> ctx.getString(R.string.warp_connect_failed)
                        is VpnController.WarpFailure.Account -> ctx.getString(R.string.warp_failed, f.message)
                    }
                    running = false
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.warp_create)) }
        }
    }
}

@Composable
private fun cdnLabel(m: CdnMethod): String = stringResource(when (m) {
    CdnMethod.PLAIN -> R.string.cdn_plain
    CdnMethod.ECH -> R.string.cdn_ech
    CdnMethod.IPV6 -> R.string.cdn_ipv6
    CdnMethod.IPV6_FF -> R.string.cdn_ipv6_ff
    CdnMethod.FF -> R.string.cdn_ff
})

@Composable
private fun cdnSummary(forced: CdnMethod?, detected: CdnMethod?): String = when {
    forced != null -> cdnLabel(forced)
    detected == null -> stringResource(R.string.cdn_auto) + " • " + stringResource(R.string.cdn_not_detected)
    detected == CdnMethod.PLAIN -> stringResource(R.string.cdn_auto) + " • " + stringResource(R.string.cdn_detected, stringResource(R.string.cdn_open))
    else -> stringResource(R.string.cdn_auto) + " • " + stringResource(R.string.cdn_detected, cdnLabel(detected) +
        when (detected.firewall()) { "mci" -> " (" + stringResource(R.string.cdn_fw_mci) + ")"; "irancell" -> " (" + stringResource(R.string.cdn_fw_irancell) + ")"; else -> "" })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CdnSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Repository.settings.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val labels = CdnMethod.entries.associateWith { cdnLabel(it) }

    ModalBottomSheet(onDismissRequest = { if (!running) onDismiss() }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.cdn_path), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.cdn_path_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            val options = listOf<CdnMethod?>(null) + CdnMethod.entries
            options.forEach { m ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .clickable { Repository.updateSettings { it.copy(cdnForced = m) } }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.cdnForced == m, onClick = { Repository.updateSettings { it.copy(cdnForced = m) } })
                    Text(if (m == null) stringResource(R.string.cdn_auto) else labels.getValue(m), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(cdnSummary(null, s.cdnByNet[NetKey.current]), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            status?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
            else FilledTonalButton(onClick = {
                running = true
                scope.launch {
                    val candidates = Repository.servers.value
                    CdnDetector.markCloudflare(candidates)
                    val fresh = Repository.servers.value
                    val n = fresh.count(com.netino.vpn.core.XrayConfigBuilder::isCdn).coerceAtMost(4)
                    status = if (n == 0) ctx.getString(R.string.cdn_detect_none) else {
                        status = ctx.getString(R.string.cdn_detecting, n)
                        val r = CdnDetector.detect(fresh, Repository.settings.value)
                        if (r == null) ctx.getString(R.string.cdn_detect_failed)
                        else ctx.getString(R.string.cdn_detect_result, labels.getValue(r.method),
                            CdnMethod.entries.joinToString("\n", prefix = "\n") { k ->
                                "${labels.getValue(k)}: ${r.working[k] ?: 0}/${r.tested}" + (r.medianMs[k]?.let { " • $it ms" } ?: "")
                            })
                    }
                    running = false
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) { Text(stringResource(R.string.cdn_detect_now)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanIpSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Repository.settings.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Triple(0, 0, 0)) }
    var results by remember { mutableStateOf<List<CleanIpScanner.Hit>>(emptyList()) }
    var v6 by remember { mutableStateOf(false) }

    fun apply(ip: String) = scope.launch {
        val ids = withContext(Dispatchers.IO) { CleanIpScanner.cloudflareServers(Repository.servers.value) }
        Repository.updateSettings { if (':' in ip) it.copy(cleanIp6 = ip) else it.copy(cleanIp = ip) }
        Repository.markCloudflare(ids)
        Toast.makeText(ctx, ctx.getString(R.string.clean_ip_applied, ip, ids.size), Toast.LENGTH_LONG).show()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { if (!running) onDismiss() }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.clean_ip), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.clean_ip_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            if (!running) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                listOf(false to R.string.clean_ip_v4, true to R.string.clean_ip_v6).forEachIndexed { i, (on, label) ->
                    SegmentedButton(selected = v6 == on, onClick = { v6 = on; results = emptyList() },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                }
            }
            if (running) {
                val (d, t, f) = progress
                Text(stringResource(R.string.clean_ip_progress, d, t, f), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
            } else {
                Button(onClick = {
                    running = true
                    scope.launch {
                        results = CleanIpScanner.scan(ipv6 = v6, onProgress = { d, t, f -> progress = Triple(d, t, f) })
                        running = false
                        if (results.isEmpty()) Toast.makeText(ctx, if (v6) R.string.clean_ip6_nothing else R.string.clean_ip_nothing, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.clean_ip_scan)) }
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.ip }) { h ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { apply(h.ip) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (h.ip == s.cleanIp || h.ip == s.cleanIp6) Icon(Icons.Outlined.CheckCircle, null, tint = Good, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text(h.ip, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${h.ms} ms", color = pingColor(h.ms), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if ((s.cleanIp.isNotBlank() || s.cleanIp6.isNotBlank()) && !running) TextButton(onClick = {
                // Servers stay marked as Cloudflare: the CDN methods still need to know them
                Repository.updateSettings { it.copy(cleanIp = "", cleanIp6 = "") }
                onDismiss()
            }) { Text(stringResource(R.string.clean_ip_stop)) }
        }
    }
}

// ---------------------------------------------------------------- speed test

@Composable
fun SpeedTestCard() {
    val state by VpnController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val connected = state is VpnState.Connected
    SectionCard(stringResource(R.string.speed_test)) {
        result?.let { (down, up) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                StatTile(stringResource(R.string.download), "$down KB/s", Modifier.weight(1f))
                StatTile(stringResource(R.string.upload), "$up KB/s", Modifier.weight(1f))
            }
        }
        FilledTonalButton(
            enabled = connected && !running,
            onClick = {
                val ep = XrayVpnService.probe ?: return@FilledTonalButton
                running = true
                scope.launch {
                    val (d, u) = withContext(Dispatchers.IO) {
                        SpeedProbe.run(ep.port, ep.user, ep.pass, timeoutMs = 8000) to SpeedProbe.upload(ep.port, ep.user, ep.pass)
                    }
                    result = d.kbps to u.kbps
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(if (running) R.string.speed_testing else if (connected) R.string.speed_start else R.string.speed_need_connect)) }
    }
}

// ---------------------------------------------------------------- backup

@Composable
fun BackupCard(onExport: (String) -> Unit, onImport: (String) -> Unit) {
    var ask by remember { mutableStateOf(0) }   // 1 export, 2 import
    SectionCard(stringResource(R.string.backup)) {
        Text(stringResource(R.string.backup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ask = 1 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = { ask = 2 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.backup_import)) }
        }
    }
    if (ask != 0) PasswordDialog(confirm = ask == 1, onDismiss = { ask = 0 }) { pw -> if (ask == 1) onExport(pw) else onImport(pw); ask = 0 }
}

@Composable
private fun PasswordDialog(confirm: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val ok = pw.length >= 6 && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (confirm) R.string.backup_export else R.string.backup_import)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_password_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.backup_password)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (confirm) OutlinedTextField(pw2, { pw2 = it }, singleLine = true, label = { Text(stringResource(R.string.backup_password_again)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(pw) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- auto-connect for apps

@Composable
fun AutoConnectScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val s by Repository.settings.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(AppWatchService.hasUsageAccess(ctx)) }
    // Coming back from Android's "usage access" page: check again and start the watcher if allowed
    LifecycleResumeEffect(Unit) {
        access = AppWatchService.hasUsageAccess(ctx)
        AppWatchService.sync(ctx)
        onPauseOrDispose { }
    }
    val count = s.autoConnectApps.size

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.auto_connect_apps), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            // ---- status ----
            val active = access && count > 0
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (active) Good.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(if (active) Good.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.AppShortcut, null, tint = if (active) Good else MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                !access -> stringResource(R.string.auto_connect_state_permission)
                                count == 0 -> stringResource(R.string.auto_connect_state_pick)
                                else -> pluralStringResource(R.plurals.auto_connect_state_on, count, count)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (active) Good else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(stringResource(R.string.auto_connect_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- step 1: permission (only while missing) ----
            if (!access) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Text("1", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.auto_connect_step_permission), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(stringResource(R.string.auto_connect_permission_why), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.auto_connect_grant)) }
                    }
                }
            }

            // ---- apps ----
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.apps), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                if (count > 0) TextButton(onClick = {
                    Repository.updateSettings { it.copy(autoConnectApps = emptySet()) }
                    AppWatchService.sync(ctx)
                }) { Text(stringResource(R.string.clear_selection)) }
            }
            AppList(s.autoConnectApps, enabled = access) { pkg, on ->
                Repository.updateSettings { it.copy(autoConnectApps = if (on) it.autoConnectApps + pkg else it.autoConnectApps - pkg) }
                AppWatchService.sync(ctx)
            }
        }
    }
}
