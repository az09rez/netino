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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.AppShortcut
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
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
import com.netino.vpn.service.Updater
import com.netino.vpn.service.VpnController
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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
fun SettingsScreen(
    modifier: Modifier, openSplit: () -> Unit, openAutoConnect: () -> Unit, openGuide: () -> Unit, onNational: () -> Unit,
    onExport: (String) -> Unit, onImport: (String) -> Unit, onAppLock: (Boolean) -> Unit,
) {
    val s by Repository.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmWipe by remember { mutableStateOf(false) }
    var netReport by remember { mutableStateOf(false) }
    if (netReport) NetReportDialog(onDismiss = { netReport = false })
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf(Locales.current(ctx)) }

    // Categories on the main page; each opens its own page (the settings grew too long for one list)
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    LazyColumn(
        modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = page
        if (p == null) {
            item {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        SETTINGS_PAGES.forEach { pg -> CategoryRow(pg) { page = pg.key } }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        PlainRow(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.guide), onClick = openGuide) {
                            // The guide is inside the app: it opens without internet
                            Surface(shape = CircleShape, color = MaterialTheme.status.goodContainer, contentColor = MaterialTheme.status.goodText) {
                                Row(Modifier.height(26.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Outlined.OfflinePin, null, Modifier.size(16.dp))
                                    Text(stringResource(R.string.works_offline), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        PlainRow(Icons.AutoMirrored.Outlined.ReceiptLong, stringResource(R.string.net_report)) { netReport = true }
                        PlainRow(Icons.Outlined.DeleteForever, stringResource(R.string.wipe_all), tint = MaterialTheme.colorScheme.error) { confirmWipe = true }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(R.drawable.logo), stringResource(R.string.cd_logo), Modifier.size(48.dp))
                    Text(stringResource(R.string.about_version, stringResource(R.string.app_name), localDigits(BuildConfig.VERSION_NAME)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.core_line, XrayCore.version), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = {
                        scope.launch {
                            val r = Updater.check(ctx, force = true)
                            Toast.makeText(ctx, if (r != null) ctx.getString(R.string.update_title, r.version) else ctx.getString(R.string.update_none),
                                Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.check_updates)) }
                }
            }
        } else {
            val title = SETTINGS_PAGES.first { it.key == p }.title
            item {
                Row(Modifier.heightIn(min = 64.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() })
                }
            }
            when (p) {
                "general" -> item {
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
                "connection" -> item {
                SectionCard(stringResource(R.string.smart_connection)) {
                    ToggleRow(stringResource(R.string.battery_saver), s.batterySaver, desc = stringResource(R.string.battery_saver_desc)) { v ->
                        Repository.updateSettings { it.copy(batterySaver = v) }
                        VpnController.applyNow()
                    }
                    ToggleRow(stringResource(R.string.auto_switch), s.autoSwitch,
                        desc = stringResource(R.string.auto_switch_desc, s.healthIntervalSec * (if (s.batterySaver) 2 else 1), s.autoSwitchFailures)) { v ->
                        Repository.updateSettings { it.copy(autoSwitch = v) }
                    }
                    ToggleRow(stringResource(R.string.reconnect_network), s.reconnectOnNetworkChange, desc = stringResource(R.string.reconnect_network_desc)) { v ->
                        Repository.updateSettings { it.copy(reconnectOnNetworkChange = v) }
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
                    Label(stringResource(R.string.wg_mtu))
                    Segmented(listOf(0 to stringResource(R.string.wg_mtu_auto), 1280 to "1280", 1380 to "1380", 1420 to "1420"), s.wgMtu) { v ->
                        Repository.updateSettings { it.copy(wgMtu = v) }
                    }
                    Text(stringResource(R.string.wg_mtu_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Spacer(Modifier.size(6.dp))
                    Label(stringResource(R.string.test_url))
                    Segmented(TEST_URLS.map { it.second to it.first }, s.testUrl) { v -> Repository.updateSettings { it.copy(testUrl = v) } }
                }
                }
                "anti" -> item { AntiCensorshipCard() }
                "routing" -> {
                    item { RoutingSharingCard() }
                    item { NavRow(Icons.Outlined.AltRoute, stringResource(R.string.split_tunnel), stringResource(R.string.split_tunnel_desc), onClick = openSplit) }
                }
                "auto" -> {
                    item { AutoScheduleCard() }
                    item { NavRow(Icons.Outlined.AppShortcut, stringResource(R.string.auto_connect_apps), stringResource(R.string.auto_connect_short), onClick = openAutoConnect) }
                }
                "privacy" -> item {
                SectionCard(stringResource(R.string.privacy_security)) {
                    ToggleRow(stringResource(R.string.ipv6_leak), s.blockIpv6Leak) { v -> Repository.updateSettings { it.copy(blockIpv6Leak = v) } }
                    // Needs the ads list from the optional geo files (recomposed when they change: geoUpdatedAt)
                    val adsOk = s.geoUpdatedAt >= 0 && "category-ads-all" in XrayCore.geoSiteTags
                    ToggleRow(stringResource(R.string.block_ads), s.blockAds && adsOk, enabled = adsOk,
                        desc = if (adsOk) null else stringResource(R.string.block_ads_unavailable)) { v ->
                        Repository.updateSettings { it.copy(blockAds = v) }
                    }
                    ToggleRow(stringResource(R.string.hide_screen), s.hideInRecents) { v -> Repository.updateSettings { it.copy(hideInRecents = v) } }
                    ToggleRow(stringResource(R.string.app_lock), s.appLock, desc = stringResource(R.string.app_lock_desc)) { v ->
                        onAppLock(v)
                    }
                    Spacer(Modifier.size(6.dp))
                    Label(stringResource(R.string.secure_dns))
                    Segmented(DNS_OPTIONS.map { it.second to it.first }, s.dns) { v -> Repository.updateSettings { it.copy(dns = v) } }
                    Spacer(Modifier.size(10.dp))
                    ToggleRow(stringResource(R.string.share_results), s.shareResults == true, desc = stringResource(R.string.share_results_desc)) { v ->
                        Repository.updateSettings { it.copy(shareResults = v) }
                    }
                    // Status as Android reported it at the last connect (Android 10+); the guide explains the two switches
                    var guide by remember { mutableStateOf(false) }
                    val ks = when {
                        com.netino.vpn.service.XrayVpnService.lockdown == true -> stringResource(R.string.kill_switch_on)
                        com.netino.vpn.service.XrayVpnService.alwaysOn == true -> stringResource(R.string.kill_switch_partial)
                        com.netino.vpn.service.XrayVpnService.alwaysOn == false -> stringResource(R.string.kill_switch_off)
                        else -> stringResource(R.string.kill_switch_desc)
                    }
                    NavRow(Icons.Outlined.Shield, stringResource(R.string.kill_switch), ks, flat = true) { guide = true }
                    if (guide) AlertDialog(
                        onDismissRequest = { guide = false },
                        title = { Text(stringResource(R.string.kill_switch)) },
                        text = { Text(stringResource(R.string.kill_switch_guide)) },
                        confirmButton = {
                            TextButton(onClick = { guide = false; ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
                                Text(stringResource(R.string.open_vpn_settings))
                            }
                        },
                        dismissButton = { TextButton(onClick = { guide = false }) { Text(stringResource(R.string.later)) } },
                    )
                    Text(stringResource(R.string.applies_next), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                }
                "backup" -> item { BackupCard(onExport, onImport) }
                "national" -> item { NationalCard(onNational) }
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
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = if (flat) MaterialTheme.shapes.small else RoundedCornerShape(24.dp),
        color = if (flat) Color.Transparent else cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = if (flat) 0.dp else 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = cs.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), fontWeight = FontWeight.Medium)
                Text(desc, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

/** A category on the main settings page: tinted icon tile, title, what's inside. */
@Composable
private fun CategoryRow(pg: SettingsPage, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = pg.tint.colors(isDarkSurface())
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                Icon(pg.icon, null, Modifier.size(28.dp), tint = fg)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(stringResource(pg.title), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold)
                Text(stringResource(pg.desc), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlainRow(icon: ImageVector, title: String, tint: Color? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = Color.Transparent, contentColor = tint ?: cs.onSurface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = tint ?: cs.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = if (tint != null) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** Icon tile colors (light / dark), the same family as the protocol avatars. */
private enum class Tint(val light: Pair<Long, Long>, val dark: Pair<Long, Long>) {
    BLUE(0xFFD6E7FF to 0xFF0A3A73, 0xFF0D3F78 to 0xFFD6E7FF),
    TEAL(0xFFCCF0EE to 0xFF0B5550, 0xFF0B3A37 to 0xFFB5EFEA),
    GREEN(0xFFDDF3C8 to 0xFF2C5A08, 0xFF22420A to 0xFFC8F09E),
    PURPLE(0xFFEBDDFF to 0xFF4A2A8A, 0xFF3A2A66 to 0xFFE3D4FF),
    ROSE(0xFFFDE2EE to 0xFF8A1F4C, 0xFF4A1530 to 0xFFFFC9DF),
    AMBER(0xFFFEF3DC to 0xFF5C3700, 0xFF3A2A06 to 0xFFFBBF24);

    fun colors(dark: Boolean): Pair<Color, Color> = (if (dark) this.dark else light).let { Color(it.first) to Color(it.second) }
}

private data class SettingsPage(val key: String, val icon: ImageVector, val title: Int, val desc: Int, val tint: Tint)

private val SETTINGS_PAGES = listOf(
    SettingsPage("general", Icons.Outlined.Tune, R.string.settings_general, R.string.settings_general_desc, Tint.BLUE),
    SettingsPage("connection", Icons.Outlined.AutoMode, R.string.smart_connection, R.string.settings_connection_desc, Tint.TEAL),
    SettingsPage("anti", Icons.Outlined.Security, R.string.anti_censorship, R.string.settings_anti_desc, Tint.GREEN),
    SettingsPage("routing", Icons.Outlined.AltRoute, R.string.routing_sharing, R.string.settings_routing_desc, Tint.PURPLE),
    SettingsPage("auto", Icons.Outlined.Schedule, R.string.settings_auto, R.string.settings_auto_desc, Tint.BLUE),
    SettingsPage("privacy", Icons.Outlined.Lock, R.string.privacy_security, R.string.settings_privacy_desc, Tint.ROSE),
    SettingsPage("backup", Icons.Outlined.Backup, R.string.settings_backup, R.string.settings_backup_desc, Tint.TEAL),
    SettingsPage("national", Icons.Outlined.PublicOff, R.string.national_title, R.string.settings_national_desc, Tint.AMBER),
)
