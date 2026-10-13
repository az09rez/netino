package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import java.util.Date
import java.text.DateFormat
import com.netino.vpn.service.AppWatchService
import com.netino.vpn.core.XrayCore
import com.netino.vpn.core.GeoFiles
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.core.Warp
import com.netino.vpn.data.AppSettings
import com.netino.vpn.data.Repository
import com.netino.vpn.data.WarpExit
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/** Settings that live in the tunnel's config: changing one while connected reconnects so it applies right away. */
private fun change(block: (AppSettings) -> AppSettings) {
    Repository.updateSettings(block)
    VpnController.applyNow()
}

/** Iranian sites direct, leaving through WARP, and sharing the connection with other devices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSharingCard() {
    val s by Repository.settings.collectAsStateWithLifecycle()
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val hasWarp = servers.any { it.subscriptionId == Warp.SUB }

    SectionCard(stringResource(R.string.routing_sharing)) {
        ToggleRow(stringResource(R.string.iran_direct), s.iranDirect, desc = stringResource(R.string.iran_direct_desc)) { v ->
            change { it.copy(iranDirect = v) }
        }
        GeoFilesRow(s)

        Text(stringResource(R.string.warp_exit), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        val modes = listOf(WarpExit.OFF to R.string.mode_off, WarpExit.SITES to R.string.warp_exit_sites, WarpExit.ALL to R.string.warp_exit_all)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(selected = s.warpExit == m, onClick = { change { it.copy(warpExit = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Text(stringResource(if (s.warpExit != WarpExit.OFF && !hasWarp) R.string.warp_exit_need_server else R.string.warp_exit_desc),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.warpExit != WarpExit.OFF && !hasWarp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))

        ToggleRow(stringResource(R.string.lan_share), s.lanShare, desc = stringResource(R.string.lan_share_desc)) { v ->
            change {
                // Credentials are made once, the first time sharing is turned on
                if (v && it.lanUser.isBlank()) it.copy(lanShare = true, lanUser = "netino", lanPass = randomPassword()) else it.copy(lanShare = v)
            }
        }
        if (s.lanShare) {
            ToggleRow(stringResource(R.string.lan_auth), s.lanAuth, desc = stringResource(R.string.lan_auth_desc)) { v -> change { it.copy(lanAuth = v) } }
            val text = lanText(s)
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            if (state !is VpnState.Connected) Text(stringResource(R.string.lan_share_off), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Netino proxy", text))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}

private fun randomPassword() = (1..12).map { "abcdefghjkmnpqrstuvwxyz23456789"[Random.nextInt(31)] }.joinToString("")

/** The phone's addresses other devices can reach (Wi-Fi, hotspot; not the VPN's own interface), with the ports. */
private fun lanText(s: AppSettings): String {
    val ips = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("xray") }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress to nif.name } }
    }.getOrDefault(emptyList())
    return buildString {
        if (ips.isEmpty()) appendLine("<phone IP>  (Wi-Fi / hotspot)")
        ips.forEach { (ip, name) -> appendLine("$ip  ($name)") }
        appendLine("SOCKS5  port ${s.lanSocksPort}")
        appendLine("HTTP    port ${s.lanHttpPort}")
        if (s.lanAuth && s.lanUser.isNotBlank()) {
            appendLine("user    ${s.lanUser}")
            append("pass    ${s.lanPass}")
        }
    }.trimEnd()
}

/**
 * The optional Iran / ads lists (geo files): what they add, whether they're there, and download / update / remove.
 */
@Composable
private fun GeoFilesRow(s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val have = s.geoUpdatedAt > 0 && XrayCore.hasGeoFiles
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.geo_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (have) stringResource(R.string.geo_have, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.geoUpdatedAt)))
            else stringResource(R.string.geo_desc),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val p = progress
        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        else Row {
            TextButton(onClick = {
                error = null
                progress = 0f
                scope.launch {
                    GeoFiles.update { f -> progress = f }
                        .onSuccess { change { it.copy(geoUpdatedAt = System.currentTimeMillis()) } }
                        .onFailure { error = ctx.getString(R.string.geo_failed, it.message ?: it.javaClass.simpleName) }
                    progress = null
                }
            }) { Text(stringResource(if (have) R.string.geo_update else R.string.geo_download)) }
            if (have) TextButton(onClick = {
                GeoFiles.remove()
                change { it.copy(geoUpdatedAt = 0, blockAds = false) }
            }) { Text(stringResource(R.string.geo_remove)) }
        }
    }
}

/** Automatic on / off by network and quiet hours (run by the background watcher). */
@Composable
fun AutoScheduleCard() {
    val s by Repository.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    fun set(block: (AppSettings) -> AppSettings) { Repository.updateSettings(block); AppWatchService.sync(ctx) }
    SectionCard(stringResource(R.string.auto_onoff)) {
        ToggleRow(stringResource(R.string.auto_on_mobile), s.autoOnMobile, desc = stringResource(R.string.auto_on_mobile_desc)) { v -> set { it.copy(autoOnMobile = v) } }
        ToggleRow(stringResource(R.string.auto_off_wifi), s.autoOffWifi, desc = stringResource(R.string.auto_off_wifi_desc)) { v -> set { it.copy(autoOffWifi = v) } }
        ToggleRow(stringResource(R.string.quiet_hours), s.quietEnabled, desc = stringResource(R.string.quiet_hours_desc)) { v -> set { it.copy(quietEnabled = v) } }
        if (s.quietEnabled) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.quiet_from), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { pickTime(ctx, s.quietFrom) { m -> set { it.copy(quietFrom = m) } } }) { Text(hhmm(s.quietFrom)) }
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.quiet_to), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { pickTime(ctx, s.quietTo) { m -> set { it.copy(quietTo = m) } } }) { Text(hhmm(s.quietTo)) }
        }
        if (s.autoOnMobile || s.autoOffWifi || s.quietEnabled) Text(stringResource(R.string.auto_onoff_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun hhmm(m: Int) = "%02d:%02d".format(java.util.Locale.US, m / 60, m % 60)

private fun pickTime(ctx: Context, minutes: Int, onPick: (Int) -> Unit) =
    android.app.TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
