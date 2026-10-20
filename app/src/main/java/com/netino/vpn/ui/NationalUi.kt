package com.netino.vpn.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.core.GeoFiles
import com.netino.vpn.core.NationalNet
import com.netino.vpn.core.Warp
import com.netino.vpn.data.Repository
import kotlinx.coroutines.launch
import java.io.File

/**
 * The national-internet page: what the network is like now, one button that tries everything (servers inside
 * Iran first), and what to get ready while the international internet still works.
 */
@Composable
fun NationalCard(onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Repository.settings.collectAsStateWithLifecycle()
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<NationalNet.State?>(null) }
    var checking by remember { mutableStateOf(false) }
    var bridges by remember { mutableStateOf<Int?>(null) }
    var geoProgress by remember { mutableStateOf<Float?>(null) }

    fun check() = scope.launch { checking = true; state = NationalNet.check(); checking = false }
    // One list item holding several cards: a Column, or they would be drawn on top of each other
    Column {
    LaunchedEffect(Unit) { check() }
    // How many saved servers sit inside Iran (resolved once; again when the lists or servers change)
    LaunchedEffect(servers.size, s.geoUpdatedAt) { bridges = if (NationalNet.canClassify()) NationalNet.bridges().size else null }

    SectionCard {
        Text(stringResource(R.string.national_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    checking || state == null -> stringResource(R.string.national_checking)
                    state == NationalNet.State.NORMAL -> stringResource(R.string.national_state_normal)
                    state == NationalNet.State.NATIONAL -> stringResource(R.string.national_state_national)
                    else -> stringResource(R.string.national_state_offline)
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (state == NationalNet.State.NATIONAL) Warn else if (state == NationalNet.State.OFFLINE) Bad else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { check() }, enabled = !checking) { Text(stringResource(R.string.national_check)) }
        }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.large) {
            Icon(Icons.Outlined.PublicOff, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.national_connect), style = MaterialTheme.typography.titleMedium)
        }
    }

    Spacer(Modifier.size(12.dp))
    SectionCard(stringResource(R.string.national_ready)) {
        Text(stringResource(R.string.national_ready_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        // 1. Iran lists: without them servers inside Iran can't be recognised
        val geo = NationalNet.canClassify()
        ReadyRow(geo, stringResource(R.string.national_geo), stringResource(if (geo) R.string.national_geo_ok else R.string.national_geo_missing)) {
            val p = geoProgress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.width(72.dp))
            else if (!geo) TextButton(onClick = {
                geoProgress = 0f
                scope.launch {
                    GeoFiles.update { f -> geoProgress = f }
                        .onSuccess { Repository.updateSettings { it.copy(geoUpdatedAt = System.currentTimeMillis()) } }
                        .onFailure { Toast.makeText(ctx, ctx.getString(R.string.geo_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                    geoProgress = null
                }
            }) { Text(stringResource(R.string.geo_download)) }
        }
        // 2. Servers inside Iran: usually the only ones that keep working
        val b = bridges
        ReadyRow((b ?: 0) > 0, stringResource(R.string.national_bridges),
            when {
                b == null -> stringResource(R.string.national_bridges_unknown)
                b == 0 -> stringResource(R.string.national_bridges_none)
                else -> stringResource(R.string.national_bridges_n, b)
            }) {}
        // 3. Lists refreshed recently (subscriptions won't be reachable during a shutdown)
        val last = subs.maxOfOrNull { it.lastUpdated } ?: 0L
        val fresh = System.currentTimeMillis() - last < 2 * 86_400_000L
        ReadyRow(fresh, stringResource(R.string.national_saved, servers.count { it.subscriptionId != Warp.SUB }),
            stringResource(if (fresh) R.string.national_saved_fresh else R.string.national_saved_old)) {
            if (!fresh) TextButton(onClick = { scope.launch { Repository.refreshAllSubscriptions() } }) { Text(stringResource(R.string.national_refresh)) }
        }
        // 4. WARP accounts made in advance (making one needs Cloudflare's API, which a shutdown blocks)
        val warp = servers.count { it.subscriptionId == Warp.SUB }
        ReadyRow(warp > 0, stringResource(R.string.national_warp), stringResource(if (warp > 0) R.string.national_warp_ok else R.string.national_warp_none)) {}
    }

    Spacer(Modifier.size(12.dp))
    SectionCard(stringResource(R.string.national_pack)) {
        Text(stringResource(R.string.national_pack_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = { sharePack(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.national_pack_share))
        }
    }
    }
}

@Composable
private fun ReadyRow(ok: Boolean, title: String, detail: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (ok) Good else Warn, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

/** Saved servers as a .txt of share links, to pass on by Bale / Eitaa / Bluetooth; any client imports it. */
private fun sharePack(ctx: Context) {
    val text = NationalNet.pack()
    if (text.isBlank()) { Toast.makeText(ctx, R.string.national_pack_empty, Toast.LENGTH_SHORT).show(); return }
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, "netino-servers.txt").apply { writeText(text + "\n") }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}

/** Home: shown when only Iranian sites answer (checked while disconnected), with the one button that fits. */
@Composable
fun NationalBanner(onConnect: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = Warn.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PublicOff, null, tint = Warn)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.national_banner), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onConnect) { Text(stringResource(R.string.national_banner_btn), color = Color.Unspecified) }
        }
    }
}
