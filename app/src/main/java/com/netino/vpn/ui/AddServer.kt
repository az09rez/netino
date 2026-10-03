package com.netino.vpn.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.netino.vpn.R
import com.netino.vpn.data.LinkParser
import com.netino.vpn.data.Repository
import kotlinx.coroutines.launch

/** Imports links / WireGuard text and tells the user how many servers were added. */
fun importConfigText(ctx: Context, text: String): Int {
    val n = Repository.addServers(LinkParser.parseMany(text))
    Toast.makeText(ctx, if (n > 0) ctx.getString(R.string.n_added, n) else ctx.getString(R.string.none_valid), Toast.LENGTH_SHORT).show()
    return n
}

private fun clipboardText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(onDismiss: () -> Unit, onScanQr: () -> Unit, onQrImage: () -> Unit) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf(0) }   // 0 none, 1 manual, 2 subscription

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Option(Icons.Outlined.ContentPaste, stringResource(R.string.add_from_clipboard)) {
                val text = clipboardText(ctx)
                // A bare https URL in the clipboard is treated as a subscription link
                if (text.trim().startsWith("https://") && !text.contains("\n")) dialog = 2
                else if (importConfigText(ctx, text) > 0) onDismiss()
            }
            Option(Icons.Outlined.QrCodeScanner, stringResource(R.string.add_scan_qr)) { onDismiss(); onScanQr() }
            Option(Icons.Outlined.Image, stringResource(R.string.add_qr_image)) { onDismiss(); onQrImage() }
            Option(Icons.Outlined.EditNote, stringResource(R.string.add_manual)) { dialog = 1 }
            Option(Icons.Outlined.Link, stringResource(R.string.add_subscription)) { dialog = 2 }
        }
    }
    when (dialog) {
        1 -> ManualDialog(onDismiss = { dialog = 0 }, onDone = onDismiss)
        2 -> SubscriptionDialog(initialUrl = clipboardText(ctx).trim().takeIf { it.startsWith("https://") }.orEmpty(),
            onDismiss = { dialog = 0 }, onDone = onDismiss)
    }
}

@Composable
private fun Option(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp),
    )
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
            TextButton(enabled = text.isNotBlank(), onClick = { if (importConfigText(ctx, text) > 0) { onDismiss(); onDone() } }) {
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
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    Repository.addSubscription(name, url.trim())
                        .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.sub_ok, it), Toast.LENGTH_SHORT).show(); onDismiss(); onDone() }
                        .onFailure { Toast.makeText(ctx, R.string.sub_failed, Toast.LENGTH_SHORT).show() }
                    busy = false
                }
            }) { Text(stringResource(if (busy) R.string.fetching else R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
