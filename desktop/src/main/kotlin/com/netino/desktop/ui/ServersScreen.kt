package com.netino.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.vpn.core.Warp
import com.netino.vpn.data.LinkParser
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** One section of the list: manual servers, WARP, or a subscription. */
private data class Section(val key: String, val title: String, val subId: String?, val servers: List<Server>, val builtIn: Boolean = false)

@Composable
fun ServersScreen(onMessage: (String) -> Unit) {
    val servers by Repository.servers.collectAsState()
    val subs by Repository.subscriptions.collectAsState()
    val settings by Repository.settings.collectAsState()
    val search by Controller.search.collectAsState()
    val state by Controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val sections = remember(servers, subs) {
        val bySub = servers.groupBy { it.subscriptionId }
        buildList {
            bySub[null]?.let { add(Section("manual", L.t("سرورهای من", "My servers"), null, it)) }
            bySub[Warp.SUB]?.let { add(Section("warp", "WARP", Warp.SUB, it)) }
            subs.forEach { s -> add(Section(s.id, s.name.ifBlank { s.url }, s.id, bySub[s.id].orEmpty(), s.builtIn)) }
        }
    }
    val sorted: (List<Server>) -> List<Server> = { l -> l.sortedBy { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE } }
    val connectedId = (state as? ConnState.Connected)?.server?.id

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.t("سرورها (${servers.size})", "Servers (${servers.size})"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }) { Icon(NIcons.Add, null); Spacer(Modifier.width(6.dp)); Text(L.t("افزودن", "Add")) }
            OutlinedButton(onClick = { Controller.testServers(servers.filter { it.subscriptionId != Warp.SUB }) }, enabled = search == null && servers.isNotEmpty()) {
                Icon(NIcons.NetworkCheck, null); Spacer(Modifier.width(6.dp)); Text(L.t("تست همه", "Test all"))
            }
            OutlinedButton(onClick = {
                refreshing = true
                scope.launch { Repository.refreshAllSubscriptions(); refreshing = false; onMessage(L.t("اشتراک‌ها به‌روز شد", "Subscriptions updated")) }
            }, enabled = !refreshing && subs.isNotEmpty()) {
                Icon(NIcons.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (refreshing) L.t("در حال به‌روزرسانی…", "Updating…") else L.t("به‌روزرسانی", "Update"))
            }
        }
        search?.let { p ->
            Text(L.t("تست ${p.done}/${p.total}", "Testing ${p.done}/${p.total}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        if (servers.isEmpty() && subs.isEmpty()) {
            Text(L.t("هنوز سروری نیست. لینک کانفیگ یا اشتراک را کپی کنید و «افزودن» را بزنید.",
                "No servers yet. Copy a config or subscription link and press Add."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sections.forEach { sec ->
                val collapsed = sec.key in settings.collapsed
                item(key = "h:" + sec.key) {
                    SectionHeader(sec, collapsed,
                        onToggle = { Repository.toggleCollapsed(sec.key) },
                        onTest = { Controller.testServers(sec.servers) },
                        onRefresh = sec.subId?.takeIf { it != Warp.SUB }?.let { id ->
                            val refresh: () -> Unit = {
                                scope.launch { Repository.refreshSubscription(id).onFailure { onMessage(L.t("دریافت اشتراک نشد: ${it.message}", "Couldn't fetch: ${it.message}")) } }
                            }
                            refresh
                        },
                        onDelete = when {
                            sec.builtIn -> null
                            sec.subId == null || sec.subId == Warp.SUB -> ({ Repository.deleteServers(sec.servers.map { it.id }) })
                            else -> ({ Repository.deleteSubscription(sec.subId) })
                        },
                    )
                }
                if (!collapsed) items(sorted(sec.servers), key = { it.id }) { s ->
                    ServerRow(s, selected = s.id == settings.selectedServerId, connected = s.id == connectedId,
                        onSelect = { Repository.select(s.id) },
                        onConnect = { Controller.connect(s) },
                        onDelete = { Repository.deleteServer(s.id) })
                }
            }
        }
    }

    if (adding) AddDialog(onDismiss = { adding = false }, onMessage = onMessage)
}

@Composable
private fun SectionHeader(sec: Section, collapsed: Boolean, onToggle: () -> Unit, onTest: () -> Unit, onRefresh: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var confirm by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    val subUrl = remember(sec.subId) { Repository.subscriptions.value.firstOrNull { it.id == sec.subId }?.url }
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (collapsed) NIcons.ExpandMore else NIcons.ExpandLess, null)
        Spacer(Modifier.width(6.dp))
        Text("${sec.title} (${sec.servers.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onTest) { Icon(NIcons.NetworkCheck, L.t("تست", "Test")) }
        if (subUrl != null) IconButton(onClick = { qr = true }) { Icon(NIcons.QrCode2, L.t("QR اشتراک", "Subscription QR")) }
        if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(NIcons.Refresh, L.t("به‌روزرسانی", "Update")) }
        if (onDelete != null) IconButton(onClick = { confirm = true }) { Icon(NIcons.Delete, L.t("حذف", "Delete")) }
    }
    if (qr && subUrl != null) QrDialog(sec.title, subUrl, L.t("با گوشی اسکن کنید تا این اشتراک آنجا هم اضافه شود.", "Scan it with the phone to add this subscription there too.")) { qr = false }
    if (confirm && onDelete != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(L.t("حذف «${sec.title}»؟", "Delete \"${sec.title}\"?")) },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(L.t("حذف", "Delete")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(L.t("انصراف", "Cancel")) } },
    )
}

@Composable
private fun ServerRow(s: Server, selected: Boolean, connected: Boolean, onSelect: () -> Unit, onConnect: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.protocol.label} • ${s.address}:${s.port}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val ping = when {
                s.lastPingMs > 0 -> "${s.lastPingMs} ms"
                s.lastPingMs == -2L -> L.t("ناموفق", "failed")
                else -> ""
            }
            Text(ping, style = MaterialTheme.typography.labelMedium, color = pingColor(s.lastPingMs, s.pingKind))
            TextButton(onClick = onConnect) { Text(L.t("اتصال", "Connect")) }
            var qr by remember { mutableStateOf(false) }
            val link = remember(s) { Repository.serverLink(s) }
            if (link.isNotBlank()) IconButton(onClick = { qr = true }) { Icon(NIcons.QrCode2, "QR", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (qr) QrDialog(s.name, link, L.t("با Netino یا v2rayNG روی گوشی اسکن کنید.", "Scan it with Netino or v2rayNG on the phone.")) { qr = false }
            IconButton(onClick = onDelete) { Icon(NIcons.Delete, L.t("حذف", "Delete"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun clipboard(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}.getOrDefault("")

/**
 * Anything pasted: subscription URL(s), share links, base64 lists, Xray / sing-box / Clash configs and
 * WireGuard files are told apart automatically (same rules as the Android app).
 */
@Composable
private fun AddDialog(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    var text by remember { mutableStateOf(clipboard().trim()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(L.t("افزودن سرور یا اشتراک", "Add servers or a subscription")) },
        text = {
            Column {
                Text(L.t("لینک کانفیگ (vless، vmess، trojan، ss، …)، لینک اشتراک، یا متن کانفیگ WireGuard / JSON را اینجا بگذارید.",
                    "Paste config links (vless, vmess, trojan, ss, …), a subscription link, or a WireGuard / JSON config."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp), enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Text, or a copied picture of a QR code
                    TextButton(onClick = { text = clipboard().trim().ifEmpty { Qr.fromClipboard().joinToString("\n") } }) { Text(L.t("چسباندن از کلیپ‌بورد", "Paste from clipboard")) }
                    TextButton(onClick = {
                        val found = Qr.scanScreen()
                        if (found.isEmpty()) onMessage(L.t("QR روی صفحه پیدا نشد", "No QR code on the screen")) else text = found.joinToString("\n")
                    }) { Icon(NIcons.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(L.t("خواندن QR از صفحه", "Read QR from screen")) }
                    TextButton(onClick = {
                        val d = java.awt.FileDialog(null as java.awt.Frame?, L.t("تصویر QR", "QR image"), java.awt.FileDialog.LOAD)
                        d.setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".bmp") || it.endsWith(".gif") } }
                        d.isVisible = true
                        val f = d.files.firstOrNull()
                        if (f != null) {
                            val found = Qr.fromFile(f)
                            if (found.isEmpty()) onMessage(L.t("QR در تصویر پیدا نشد", "No QR code in the image")) else text = found.joinToString("\n")
                        }
                    }) { Icon(NIcons.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(L.t("از تصویر", "From an image")) }
                }
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank() && !busy, onClick = {
                val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val urls = lines.isNotEmpty() && lines.all { (it.startsWith("https://", true) || it.startsWith("http://", true)) && ' ' !in it }
                if (!urls) {
                    val n = Repository.addServers(LinkParser.parseMany(text))
                    onMessage(if (n > 0) L.t("$n سرور اضافه شد", "$n servers added") else L.t("کانفیگ معتبری پیدا نشد", "No valid config found"))
                    if (n > 0) onDismiss()
                } else {
                    busy = true
                    scope.launch {
                        var added = 0; var ok = 0; var failure: Throwable? = null
                        for (url in lines) Repository.addSubscription("", url).onSuccess { added += it; ok++ }.onFailure { failure = failure ?: it }
                        busy = false
                        onMessage(if (ok > 0) L.t("اشتراک اضافه شد: $added سرور", "Subscription added: $added servers")
                            else L.t("دریافت اشتراک نشد: ${failure?.message ?: ""}", "Couldn't fetch the subscription: ${failure?.message ?: ""}"))
                        if (ok > 0) onDismiss()
                    }
                }
            }) { Text(if (busy) L.t("در حال دریافت…", "Fetching…") else L.t("افزودن", "Add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L.t("انصراف", "Cancel")) } },
    )
}
