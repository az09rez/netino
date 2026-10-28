package com.netino.vpn.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.netino.vpn.R
import com.netino.vpn.data.LinkParser
import com.netino.vpn.data.Repository
import com.netino.vpn.data.SubscriptionError
import com.netino.vpn.core.LocalRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Imports links / JSON / WireGuard text and tells the user how many servers were added. */
fun importConfigText(ctx: Context, text: String): Int {
    val n = Repository.addServers(LinkParser.parseMany(text))
    Toast.makeText(ctx, if (n > 0) ctx.getString(R.string.n_added, n) else ctx.getString(R.string.none_valid), Toast.LENGTH_SHORT).show()
    return n
}

/**
 * One entry point for anything pasted, typed, scanned or shared: subscription URL(s), share links,
 * base64 lists, Xray / sing-box JSON and WireGuard configs are told apart automatically.
 * Returns false only when nothing usable was found (subscriptions are fetched asynchronously).
 */
fun smartImport(ctx: Context, text: String, onAdded: () -> Unit = {}): Boolean {
    val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val subscriptions = lines.isNotEmpty() && lines.all { (it.startsWith("https://", true) || it.startsWith("http://", true)) && ' ' !in it }
    if (!subscriptions) return (importConfigText(ctx, text) > 0).also { if (it) onAdded() }
    importScope.launch {
        var servers = 0
        var ok = 0
        var failure: Throwable? = null
        for (url in lines) Repository.addSubscription("", url).onSuccess { servers += it; ok++ }.onFailure { failure = failure ?: it }
        val msg = if (ok > 0) ctx.getString(R.string.sub_ok, servers) else failure?.let { subscriptionMessage(ctx, it) } ?: ctx.getString(R.string.sub_failed)
        Toast.makeText(ctx, msg, if (ok > 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        if (ok > 0) onAdded()
    }
    return true
}

/** Outlives the add sheet / dialog, so a subscription keeps loading after they close. */
private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private class AddAction(val icon: ImageVector, val label: Int, val onClick: () -> Unit)

private fun clipboardText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}

private val CONFIG_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://", "ssr://", "hy2://", "hysteria2://", "hysteria://", "tuic://", "wireguard://", "wg://")

/** What's on the clipboard, judged by its look only (cheap enough to run when the sheet opens). */
private enum class ClipKind { SUBSCRIPTION, CONFIG }

private fun clipKind(text: String): Pair<ClipKind, Int>? {
    val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty() || lines.size > 2000) return null
    if (lines.all { (it.startsWith("https://", true) || it.startsWith("http://", true)) && ' ' !in it }) return ClipKind.SUBSCRIPTION to lines.size
    val links = lines.count { l -> CONFIG_SCHEMES.any { l.startsWith(it, true) } }
    if (links > 0) return ClipKind.CONFIG to links
    if (text.contains("[Interface]") && text.contains("PrivateKey")) return ClipKind.CONFIG to 1
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(onDismiss: () -> Unit, onScanQr: () -> Unit, onQrImage: () -> Unit, onFiles: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var dialog by remember { mutableStateOf(0) }   // 0 none, 1 manual, 2 subscription
    val clip = remember { clipboardText(ctx) }
    val found = remember(clip) { clipKind(clip) }
    var ignored by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if (isDarkSurface()) cs.surfaceContainer else cs.background) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
            AnimatedVisibility(found != null && !ignored) {
                val (kind, n) = found ?: return@AnimatedVisibility
                Surface(shape = RoundedCornerShape(24.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null)
                            Text(
                                if (kind == ClipKind.SUBSCRIPTION) stringResource(R.string.clip_found_sub)
                                else stringResource(R.string.clip_found_cfg, localDigits("$n")),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        // Links read left to right in both languages
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(clip.trim().lineSequence().first().take(200), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (isDarkSurface()) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.6f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = { ignored = true }, colors = ButtonDefaults.textButtonColors(contentColor = cs.onPrimaryContainer),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ignore)) }
                            Button(onClick = { if (smartImport(ctx, clip)) onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (kind == ClipKind.SUBSCRIPTION) R.string.add_sub_btn else R.string.add), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            val tiles = listOf(
                AddAction(Icons.Outlined.ContentPaste, R.string.add_from_clipboard) { if (smartImport(ctx, clipboardText(ctx))) onDismiss() },
                AddAction(Icons.Outlined.QrCodeScanner, R.string.add_scan_qr) { onDismiss(); onScanQr() },
                AddAction(Icons.Outlined.Image, R.string.add_qr_image) { onDismiss(); onQrImage() },
                AddAction(Icons.Outlined.Description, R.string.add_files) { onDismiss(); onFiles() },
                AddAction(Icons.Outlined.EditNote, R.string.add_manual) { dialog = 1 },
                AddAction(Icons.Outlined.Link, R.string.add_subscription) { dialog = 2 },
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { a -> Tile(a.icon, stringResource(a.label), Modifier.weight(1f), a.onClick) }
                    }
                }
            }
            Text(stringResource(R.string.add_support), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    when (dialog) {
        1 -> ManualDialog(onDismiss = { dialog = 0 }, onDone = onDismiss)
        2 -> SubscriptionDialog(initialUrl = clipboardText(ctx).trim().takeIf { it.startsWith("https://") }.orEmpty(),
            onDismiss = { dialog = 0 }, onDone = onDismiss)
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = modifier.heightIn(min = 104.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = cs.primary)
            }
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_manual)) },
        text = {
            Column {
                Text(stringResource(R.string.config_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.config)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { if (smartImport(ctx, text)) { onDismiss(); onDone() } }) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SubscriptionDialog(initialUrl: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_subscription)) },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.sub_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sub_name_optional)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                // Why it failed stays in the dialog (a toast is gone before it can be read), with the link still there to fix
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank() && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    Repository.addSubscription(name, url.trim())
                        .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.sub_ok, it), Toast.LENGTH_SHORT).show(); onDismiss(); onDone() }
                        .onFailure { error = subscriptionMessage(ctx, it) }
                    busy = false
                }
            }) { Text(stringResource(if (busy) R.string.fetching else R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What went wrong with a subscription, in words that say what to do about it. */
fun subscriptionMessage(ctx: Context, e: Throwable): String {
    val se = SubscriptionError.from(e)
    val main = when (se.reason) {
        SubscriptionError.Reason.BAD_URL -> ctx.getString(R.string.sub_err_url)
        SubscriptionError.Reason.DNS -> ctx.getString(R.string.sub_err_dns)
        SubscriptionError.Reason.BLOCKED -> ctx.getString(R.string.sub_err_blocked)
        SubscriptionError.Reason.TLS -> ctx.getString(R.string.sub_err_tls)
        SubscriptionError.Reason.HTTP -> ctx.getString(R.string.sub_err_http, se.code)
        SubscriptionError.Reason.EMPTY -> ctx.getString(R.string.sub_err_empty)
        SubscriptionError.Reason.OTHER -> ctx.getString(R.string.sub_err_other, se.message.orEmpty())
    }
    // Filtered: say whether the VPN route was tried too, or that connecting first lets it be
    val hint = when (se.reason) {
        SubscriptionError.Reason.DNS, SubscriptionError.Reason.BLOCKED ->
            " " + ctx.getString(if (LocalRoutes.tunnel() != null) R.string.sub_err_via_vpn else R.string.sub_err_connect_hint)
        else -> ""
    }
    return main + hint
}
