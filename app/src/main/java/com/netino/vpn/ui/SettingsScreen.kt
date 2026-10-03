package com.netino.vpn.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.BuildConfig
import com.netino.vpn.Locales
import com.netino.vpn.R
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Repository
import com.netino.vpn.data.ThemeMode
import com.netino.vpn.data.TunEngine
import com.netino.vpn.service.VpnController

private val DNS_OPTIONS = listOf(
    "Cloudflare" to "https://1.1.1.1/dns-query",
    "Quad9" to "https://dns.quad9.net/dns-query",
    "Google" to "https://dns.google/dns-query",
)
private val TEST_URLS = listOf(
    "Google" to "https://www.gstatic.com/generate_204",
    "Cloudflare" to "https://cp.cloudflare.com/generate_204",
)

@Composable
fun SettingsScreen(modifier: Modifier, openSplit: () -> Unit) {
    val s by Repository.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmWipe by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(Locales.current(ctx)) }

    LazyColumn(
        modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp)) }

        item {
            SectionCard(stringResource(R.string.appearance)) {
                Label(stringResource(R.string.language))
                Segmented(
                    listOf("" to stringResource(R.string.lang_system), "fa" to "فارسی", "en" to "English"), lang,
                ) { v ->
                    lang = v
                    if (Locales.set(ctx, v)) (ctx as? Activity)?.recreate()
                }
                Spacer(Modifier.size(12.dp))
                Label(stringResource(R.string.theme))
                Segmented(
                    listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system), ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark)), s.theme,
                ) { v -> Repository.updateSettings { it.copy(theme = v) } }
            }
        }

        item { NavRow(Icons.Outlined.AltRoute, stringResource(R.string.split_tunnel), stringResource(R.string.split_tunnel_desc), onClick = openSplit) }

        item {
            SectionCard(stringResource(R.string.smart_connection)) {
                ToggleRow(stringResource(R.string.auto_switch), s.autoSwitch,
                    desc = stringResource(R.string.auto_switch_desc, s.healthIntervalSec, s.autoSwitchFailures)) { v ->
                    Repository.updateSettings { it.copy(autoSwitch = v) }
                }
                ToggleRow(stringResource(R.string.bypass_lan), s.bypassLan, desc = stringResource(R.string.bypass_lan_desc)) { v ->
                    Repository.updateSettings { it.copy(bypassLan = v) }
                }
                ToggleRow(stringResource(R.string.mux), s.mux) { v -> Repository.updateSettings { it.copy(mux = v) } }
                Spacer(Modifier.size(6.dp))
                Label(stringResource(R.string.tun_engine))
                Segmented(
                    listOf(TunEngine.HEV to stringResource(R.string.tun_engine_hev), TunEngine.XRAY to stringResource(R.string.tun_engine_xray)),
                    s.tunEngine,
                ) { v -> Repository.updateSettings { it.copy(tunEngine = v) } }
                Text(stringResource(R.string.tun_engine_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                Spacer(Modifier.size(6.dp))
                Label(stringResource(R.string.test_url))
                Segmented(TEST_URLS.map { it.second to it.first }, s.testUrl) { v -> Repository.updateSettings { it.copy(testUrl = v) } }
            }
        }

        item {
            SectionCard(stringResource(R.string.privacy_security)) {
                ToggleRow(stringResource(R.string.ipv6_leak), s.blockIpv6Leak) { v -> Repository.updateSettings { it.copy(blockIpv6Leak = v) } }
                ToggleRow(stringResource(R.string.block_ads), s.blockAds && XrayCore.hasGeoFiles, enabled = XrayCore.hasGeoFiles,
                    desc = if (XrayCore.hasGeoFiles) null else stringResource(R.string.block_ads_unavailable)) { v ->
                    Repository.updateSettings { it.copy(blockAds = v) }
                }
                ToggleRow(stringResource(R.string.hide_screen), s.hideInRecents) { v -> Repository.updateSettings { it.copy(hideInRecents = v) } }
                Spacer(Modifier.size(6.dp))
                Label(stringResource(R.string.secure_dns))
                Segmented(DNS_OPTIONS.map { it.second to it.first }, s.dns) { v -> Repository.updateSettings { it.copy(dns = v) } }
                Spacer(Modifier.size(10.dp))
                NavRow(Icons.Outlined.Shield, stringResource(R.string.kill_switch), stringResource(R.string.kill_switch_desc), flat = true) {
                    ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                }
                Text(stringResource(R.string.applies_next), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item {
            OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.wipe_all), color = Bad)
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(72.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.about_line) + "\n" + stringResource(R.string.core_line, XrayCore.version),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text(stringResource(R.string.wipe_all)) },
        text = { Text(stringResource(R.string.wipe_q)) },
        confirmButton = {
            TextButton(onClick = { VpnController.disconnect(); Repository.wipeEverything(); confirmWipe = false }) {
                Text(stringResource(R.string.erase), color = Bad)
            }
        },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = selected == value, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, desc: String, flat: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.large,
        color = if (flat) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
