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
