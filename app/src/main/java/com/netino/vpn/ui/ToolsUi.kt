package com.netino.vpn.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import com.netino.vpn.core.CleanIpScanner
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
    SectionCard(stringResource(R.string.anti_censorship)) {
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
            }
            Icon(Icons.Outlined.Radar, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (scanner) CleanIpSheet(onDismiss = { scanner = false })
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

    fun apply(ip: String) = scope.launch {
        val ids = withContext(Dispatchers.IO) { CleanIpScanner.cloudflareServers(Repository.servers.value) }
        Repository.updateSettings { it.copy(cleanIp = ip) }
        Repository.setCleanIpServers(ids)
        Toast.makeText(ctx, ctx.getString(R.string.clean_ip_applied, ip, ids.size), Toast.LENGTH_LONG).show()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { if (!running) onDismiss() }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.clean_ip), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.clean_ip_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            if (running) {
                val (d, t, f) = progress
                Text(stringResource(R.string.clean_ip_progress, d, t, f), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
            } else {
                Button(onClick = {
                    running = true
                    scope.launch {
                        results = CleanIpScanner.scan(onProgress = { d, t, f -> progress = Triple(d, t, f) })
                        running = false
                        if (results.isEmpty()) Toast.makeText(ctx, R.string.clean_ip_nothing, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.clean_ip_scan)) }
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.ip }) { h ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { apply(h.ip) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (h.ip == s.cleanIp) Icon(Icons.Outlined.CheckCircle, null, tint = Good, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text(h.ip, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${h.ms} ms", color = pingColor(h.ms), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (s.cleanIp.isNotBlank() && !running) TextButton(onClick = {
                Repository.updateSettings { it.copy(cleanIp = "") }
                Repository.setCleanIpServers(emptySet())
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
    val ctx = LocalContext.current
    val s by Repository.settings.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(AppWatchService.hasUsageAccess(ctx)) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.auto_connect_apps), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.auto_connect_desc), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (!access) {
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) { Text(stringResource(R.string.auto_connect_grant)) }
            TextButton(onClick = { access = AppWatchService.hasUsageAccess(ctx); AppWatchService.sync(ctx) },
                modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.auto_connect_check)) }
        }
        Spacer(Modifier.size(8.dp))
        AppList(s.autoConnectApps, enabled = access) { pkg, on ->
            Repository.updateSettings { it.copy(autoConnectApps = if (on) it.autoConnectApps + pkg else it.autoConnectApps - pkg) }
            AppWatchService.sync(ctx)
        }
    }
}
