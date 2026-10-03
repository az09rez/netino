package com.netino.vpn.ui

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.res.stringResource
import com.netino.vpn.R
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.data.Repository
import com.netino.vpn.data.SplitMode
import com.netino.vpn.data.SplitTunnelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

/** Windscribe-style split tunnelling: per app, and per domain / IP / CIDR. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val sp = settings.split
    fun save(block: (SplitTunnelSettings) -> SplitTunnelSettings) = Repository.updateSettings { it.copy(split = block(it.split)) }
    var tab by remember { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.split_tunnel), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(tab == 0, { tab = 0 }, text = {
                    Text(stringResource(R.string.apps) + if (sp.apps.isNotEmpty()) " (${sp.apps.size})" else "")
                })
                Tab(tab == 1, { tab = 1 }, text = {
                    val n = sp.domains.size + sp.ips.size
                    Text(stringResource(R.string.sites_ips) + if (n > 0) " ($n)" else "")
                })
            }
            if (tab == 0) {
                ModeSelector(sp.appMode, R.string.mode_bypass_apps, R.string.mode_only_apps) { m -> save { it.copy(appMode = m) } }
                AppList(sp.apps, enabled = sp.appMode != SplitMode.OFF) { pkg, on ->
                    save { it.copy(apps = if (on) it.apps + pkg else it.apps - pkg) }
                }
            } else {
                ModeSelector(sp.routeMode, R.string.mode_bypass_routes, R.string.mode_only_routes) { m -> save { it.copy(routeMode = m) } }
                RouteEditor(sp, ::save)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: SplitMode, @StringRes bypassDesc: Int, @StringRes onlyDesc: Int, onChange: (SplitMode) -> Unit) {
    val opts = listOf(SplitMode.OFF to R.string.mode_off, SplitMode.BYPASS to R.string.mode_bypass, SplitMode.ONLY to R.string.mode_only)
    Column(Modifier.padding(16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (m, label) ->
                SegmentedButton(selected = mode == m, onClick = { onChange(m) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size)) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
        Text(
            stringResource(when (mode) { SplitMode.OFF -> R.string.mode_off_desc; SplitMode.BYPASS -> bypassDesc; SplitMode.ONLY -> onlyDesc }),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AppList(selected: Set<String>, enabled: Boolean, onToggle: (String, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != ctx.packageName }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedBy { it.label.lowercase() }
        }
    }
    OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_apps)) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    val list = apps
    if (list == null) { Text(stringResource(R.string.loading), Modifier.padding(16.dp)); return }
    val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedByDescending { it.pkg in selected }
    LazyColumn(contentPadding = PaddingValues(8.dp)) {
        items(shown, key = { it.pkg }) { app ->
            val checked = app.pkg in selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox) { onToggle(app.pkg, it) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bmp = remember(app.pkg) { app.icon.toBitmap(96, 96).asImageBitmap() }
                Image(bmp, null, Modifier.size(40.dp))
                Spacer(Modifier.size(12.dp))
                Text(app.label, Modifier.weight(1f))
                Checkbox(checked, null, enabled = enabled)
            }
        }
    }
}

@Composable
private fun RouteEditor(sp: SplitTunnelSettings, save: ((SplitTunnelSettings) -> SplitTunnelSettings) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            input, { input = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.route_hint)) },
        )
        Button(
            enabled = input.isNotBlank(),
            onClick = {
                val items = input.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                val isIp = { v: String -> v.startsWith("geoip:") || Regex("""^[0-9a-fA-F:.]+(/\d{1,3})?$""").matches(v) }
                save { s -> s.copy(ips = (s.ips + items.filter(isIp)).distinct(), domains = (s.domains + items.filterNot(isIp)).distinct()) }
                input = ""
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.add)) }
        Text(stringResource(R.string.wg_split_note),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(sp.domains, key = { "d$it" }) { d -> Entry("🌐 $d") { save { s -> s.copy(domains = s.domains - d) } } }
        items(sp.ips, key = { "i$it" }) { ip -> Entry("📍 $ip") { save { s -> s.copy(ips = s.ips - ip) } } }
    }
}

@Composable
private fun Entry(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, stringResource(R.string.remove, text)) }
    }
}
