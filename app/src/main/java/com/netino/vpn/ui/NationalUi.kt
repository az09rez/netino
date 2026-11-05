package com.netino.vpn.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val cs = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    val s by Repository.settings.collectAsStateWithLifecycle()
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<NationalNet.State?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkedAt by remember { mutableStateOf(0L) }
    var bridges by remember { mutableStateOf<Int?>(null) }
    var geoProgress by remember { mutableStateOf<Float?>(null) }
    var qr by remember { mutableStateOf(false) }

    fun check() = scope.launch { checking = true; state = NationalNet.check(); checkedAt = System.currentTimeMillis(); checking = false }
    LaunchedEffect(Unit) { check() }
    // How many saved servers sit inside Iran (resolved once; again when the lists or servers change)
    LaunchedEffect(servers.size, s.geoUpdatedAt) { bridges = if (NationalNet.canClassify()) NationalNet.bridges().size else null }

    // One list item holding several cards: a Column, or they would be drawn on top of each other
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- the network now ----
        val national = state == NationalNet.State.NATIONAL
        val offline = state == NationalNet.State.OFFLINE
        val dark = isDarkSurface()
        val (bg, fg) = when {
            national -> st.warnContainer to if (dark) st.warnText else Color(0xFF5C3700)
            offline -> cs.errorContainer to cs.onErrorContainer
            else -> cs.surfaceContainer to cs.onSurface
        }
        Surface(shape = RoundedCornerShape(24.dp), color = bg, contentColor = fg, modifier = Modifier.animateContentSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (state == NationalNet.State.NORMAL) Icons.Outlined.Public else Icons.Outlined.PublicOff, null, Modifier.size(28.dp))
                    Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(
                            stringResource(when {
                                checking || state == null -> R.string.national_checking
                                national -> R.string.national_detected
                                offline -> R.string.national_state_offline
                                else -> R.string.national_state_normal
                            }),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.Bold,
                        )
                        if (checkedAt > 0 && !checking) Text(
                            stringResource(R.string.national_last_check, localDigits(DateUtils.getRelativeTimeSpanString(checkedAt,
                                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        )
                    }
                    if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = fg)
                    else IconButton(onClick = { check() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.national_check)) }
                }
                if (state != null && !checking) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val domestic = state != NationalNet.State.OFFLINE
                    val intl = state == NationalNet.State.NORMAL
                    ReachChip(domestic, stringResource(R.string.national_iranian_sites), Modifier.weight(1f))
                    ReachChip(intl, stringResource(R.string.national_foreign_sites), Modifier.weight(1f))
                }
                Button(
                    onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = ActionHeight), shape = RoundedCornerShape(18.dp),
                    colors = if (national) ButtonDefaults.buttonColors(containerColor = st.warn, contentColor = Color(0xFF2B1A00)) else ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.national_connect), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(stringResource(R.string.national_desc), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))

        // ---- get ready while the international internet still works ----
        val geo = NationalNet.canClassify()
        val b = bridges
        val last = subs.maxOfOrNull { it.lastUpdated } ?: 0L
        val fresh = System.currentTimeMillis() - last < 2 * 86_400_000L
        val warp = servers.count { it.subscriptionId == Warp.SUB }
        val done = listOf(geo, (b ?: 0) > 0, fresh, warp > 0).count { it }
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.national_ready), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                    Text(stringResource(R.string.n_of_m, localDigits("$done"), localDigits("4")),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cs.onSurfaceVariant)
                }
                Text(stringResource(R.string.national_ready_desc), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                // 1. Iran lists: without them servers inside Iran can't be recognised
                ReadyRow(geo, stringResource(R.string.national_geo), stringResource(if (geo) R.string.national_geo_ok else R.string.national_geo_missing)) {
                    val p = geoProgress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.width(72.dp))
                    else if (!geo) TonalAction(stringResource(R.string.geo_download)) {
                        geoProgress = 0f
                        scope.launch {
                            GeoFiles.update { f -> geoProgress = f }
                                .onSuccess { Repository.updateSettings { it.copy(geoUpdatedAt = System.currentTimeMillis()) } }
                                .onFailure { Toast.makeText(ctx, ctx.getString(R.string.geo_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                            geoProgress = null
                        }
                    }
                }
                // 2. Servers inside Iran: usually the only ones that keep working
                ReadyRow((b ?: 0) > 0, stringResource(R.string.national_bridges),
                    when {
                        b == null -> stringResource(R.string.national_bridges_unknown)
                        b == 0 -> stringResource(R.string.national_bridges_none)
                        else -> stringResource(R.string.national_bridges_n, b)
                    }) {}
                // 3. Lists refreshed recently (subscriptions won't be reachable during a shutdown)
                ReadyRow(fresh, stringResource(R.string.national_saved, servers.count { it.subscriptionId != Warp.SUB }),
                    stringResource(if (fresh) R.string.national_saved_fresh else R.string.national_saved_old)) {
                    if (!fresh) TonalAction(stringResource(R.string.national_refresh)) { scope.launch { Repository.refreshAllSubscriptions() } }
                }
                // 4. WARP accounts made in advance (making one needs Cloudflare's API, which a shutdown blocks)
                ReadyRow(warp > 0, stringResource(R.string.national_warp), stringResource(if (warp > 0) R.string.national_warp_ok else R.string.national_warp_none)) {}
            }
        }

        // ---- server pack for people without Netino ----
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Inventory2, null, Modifier.size(30.dp), tint = cs.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.national_pack), style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.national_pack_desc), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cs.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { sharePack(ctx) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.Share, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.national_pack_send), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = { qr = true }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.QrCode2, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.show_qr), fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (qr) PackQrDialog(onDismiss = { qr = false })
}

@Composable
private fun ReachChip(ok: Boolean, label: String, modifier: Modifier) {
    val dark = isDarkSurface()
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(if (dark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.7f))
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel, stringResource(if (ok) R.string.works else R.string.blocked),
            Modifier.size(20.dp), tint = if (ok) MaterialTheme.status.goodText else MaterialTheme.status.badText)
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TonalAction(label: String, onClick: () -> Unit) =
    FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp)) { Text(label, fontWeight = FontWeight.SemiBold) }

@Composable
private fun ReadyRow(ok: Boolean, title: String, detail: String, action: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (ok) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, stringResource(if (ok) R.string.done else R.string.not_done),
            tint = if (ok) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = FontWeight.Medium,
                textDecoration = if (ok) TextDecoration.LineThrough else null, color = if (ok) cs.onSurfaceVariant else cs.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cs.onSurfaceVariant)
        }
        action()
    }
}

/** The best saved servers that fit in one QR code, to scan with any client. */
@Composable
private fun PackQrDialog(onDismiss: () -> Unit) {
    val img = remember {
        val links = Repository.servers.value.filter { it.subscriptionId != Warp.SUB && it.link.isNotBlank() }
            .sortedBy { it.score ?: Long.MAX_VALUE }.map { it.link }.distinct()
        val picked = StringBuilder()
        for (l in links) { if (picked.length + l.length + 1 > 1200) break; if (picked.isNotEmpty()) picked.append('\n'); picked.append(l) }
        picked.toString().takeIf { it.isNotEmpty() }?.let { QrImage.encode(it)?.asImageBitmap() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.national_pack)) },
        text = {
            if (img != null) Image(img, stringResource(R.string.national_pack), Modifier.fillMaxWidth().aspectRatio(1f))
            else Text(stringResource(R.string.national_pack_empty))
        },
    )
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
