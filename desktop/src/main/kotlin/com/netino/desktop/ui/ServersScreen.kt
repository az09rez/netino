package com.netino.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private data class Section(val key: String, val title: String, val subId: String?, val servers: List<Server>, val builtIn: Boolean = false, val groupId: String? = null)

private val Top = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
private val Bottom = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
private val Whole = RoundedCornerShape(18.dp)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ServersScreen(onMessage: (String) -> Unit) {
    val servers by Repository.servers.collectAsState()
    val subs by Repository.subscriptions.collectAsState()
    val settings by Repository.settings.collectAsState()
    val search by Controller.search.collectAsState()
    val state by Controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val groups by Repository.groups.collectAsState()
    val sections = remember(servers, subs, groups) {
        val bySub = servers.groupBy { it.subscriptionId }
        buildList {
            // Groups first: the user's own picks (a server can be in several, and stays in its subscription too)
            groups.forEach { g -> add(Section("g:${g.id}", L.t("گروه: ", "Group: ") + g.name, null, Repository.groupServers(g, servers), groupId = g.id)) }
            bySub[null]?.let { add(Section("manual", L.t("سرورهای من", "My servers"), null, it)) }
            bySub[Warp.SUB]?.let { add(Section("warp", "WARP", Warp.SUB, it)) }
            subs.forEach { s -> add(Section(s.id, if (s.builtIn) L.t("کانفیگ‌های رایگان نتینو", "Netino free configs") else L.t("اشتراک: ", "Subscription: ") + s.name.ifBlank { s.url },
                s.id, bySub[s.id].orEmpty(), s.builtIn)) }
        }
    }
    val shown: (List<Server>) -> List<Server> = { l ->
        l.filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
            .sortedBy { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE }
    }
    val connectedId = (state as? ConnState.Connected)?.server?.id

    Column(Modifier.fillMaxSize()) {
        val actions: @Composable () -> Unit = {
            SearchField(query, { query = it }, L.t("جست‌وجو", "Search"), Modifier.width(220.dp))
            Button(onClick = { adding = true }, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                Icon(NIcons.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(L.t("افزودن", "Add"))
            }
            OutlinedButton(onClick = { Controller.testServers(servers.filter { it.subscriptionId != Warp.SUB }) }, enabled = search == null && servers.isNotEmpty(),
                modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 16.dp), border = BorderStroke(1.dp, cs.outline)) {
                Icon(NIcons.NetworkCheck, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(L.t("تست همه", "Test all"), color = cs.onSurface)
            }
            OutlinedButton(onClick = {
                refreshing = true
                scope.launch { Repository.refreshAllSubscriptions(); refreshing = false; onMessage(L.t("اشتراک‌ها به‌روز شد", "Subscriptions updated")) }
            }, enabled = !refreshing && subs.isNotEmpty(), modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                border = BorderStroke(1.dp, cs.outline)) {
                Icon(NIcons.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(if (refreshing) L.t("در حال به‌روزرسانی…", "Updating…") else L.t("به‌روزرسانی", "Update"), color = cs.onSurface)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp)) {
            // Narrow window: the title alone, then search and the buttons on a line that wraps
            if (maxWidth < 720.dp) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageTitle(L.t("سرورها", "Servers"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageTitle(L.t("سرورها", "Servers"), Modifier.weight(1f))
                actions()
            }
        }
        search?.let { p ->
            LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(4.dp), trackColor = cs.primaryContainer, gapSize = 0.dp, drawStopIndicator = {})
            Text(L.t("تست ${localDigits("${p.done}")} از ${localDigits("${p.total}")}", "Testing ${p.done} of ${p.total}"), style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        if (servers.isEmpty() && subs.isEmpty()) {
            Text(L.t("هنوز سروری نیست. لینک کانفیگ یا اشتراک را کپی کنید و «افزودن» را بزنید.",
                "No servers yet. Copy a config or subscription link and press Add."), color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            var first = true
            sections.forEach { sec ->
                val list = shown(sec.servers)
                if (query.isNotBlank() && list.isEmpty()) return@forEach
                val collapsed = query.isBlank() && sec.key in settings.collapsed
                val withRows = !collapsed && list.isNotEmpty()
                val top = if (first) 0.dp else 12.dp
                first = false
                item(key = "h:" + sec.key) {
                    SectionHeader(sec, collapsed, if (withRows) Top else Whole, Modifier.padding(top = top),
                        onToggle = { Repository.toggleCollapsed(sec.key) },
                        onTest = { Controller.testServers(sec.servers) },
                        onRefresh = sec.subId?.takeIf { it != Warp.SUB }?.let { id ->
                            val refresh: () -> Unit = {
                                scope.launch { Repository.refreshSubscription(id).onFailure { onMessage(L.t("دریافت اشتراک نشد: ${it.message}", "Couldn't fetch: ${it.message}")) } }
                            }
                            refresh
                        },
                        onDelete = when {
                            sec.groupId != null -> ({ Repository.deleteGroup(sec.groupId) })
                            sec.builtIn -> null
                            sec.subId == null || sec.subId == Warp.SUB -> ({ Repository.deleteServers(sec.servers.map { it.id }) })
                            else -> ({ Repository.deleteSubscription(sec.subId) })
                        },
                    )
                }
                // Keys per section: a server in a group is listed twice
                if (withRows) itemsIndexed(list, key = { _, s -> sec.key + "/" + s.id }) { i, s ->
                    ServerRow(s, selected = s.id == settings.selectedServerId, connected = s.id == connectedId,
                        testing = search != null && s.lastPingMs == -1L,
                        shape = if (i == list.lastIndex) Bottom else RectangleShape,
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
private fun SectionHeader(sec: Section, collapsed: Boolean, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier, onToggle: () -> Unit,
                          onTest: () -> Unit, onRefresh: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    val sub = Repository.subscriptions.value.firstOrNull { it.id == sec.subId }
    val subUrl = sub?.url?.takeIf { !sub.builtIn }
    // Data and time left, from the panel (warning colour when running out, as the notification says)
    val level = sub?.takeIf { it.hasUsage }?.let { com.netino.desktop.SubAlerts.level(it) }
    val meta = sub?.takeIf { it.hasUsage }?.let { s ->
        buildList {
            if (s.total > 0) add(L.t("${localDigits(formatBytes(s.used))} از ${localDigits(formatBytes(s.total))}", "${formatBytes(s.used)} of ${formatBytes(s.total)}"))
            if (s.expire > 0) {
                val days = (s.expire * 1000 - System.currentTimeMillis()) / 86_400_000L
                add(if (days >= 0) L.t("${localDigits("$days")} روز مانده", "$days days left") else L.t("تمام شده", "expired"))
            }
        }.joinToString(" · ")
    } ?: when {
        sec.key == "warp" -> L.t("کلودفلر", "Cloudflare")
        else -> L.t("${localDigits("${sec.servers.size}")} سرور", "${sec.servers.size} servers")
    }
    val tone = if (level != null) MaterialTheme.status.warnText else null
    val icon = when {
        sec.groupId != null -> NIcons.Folder
        sec.key == "manual" -> NIcons.Person
        sec.key == "warp" -> NIcons.Bolt
        sec.builtIn -> NIcons.Lock
        else -> NIcons.Cloud
    }
    Surface(shape = shape, color = cs.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onToggle).heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = tone ?: cs.primary)
            // Title and meta take the free space, so the buttons always sit at the far end
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(sec.title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = tone ?: cs.onSurfaceVariant,
                    fontWeight = if (tone != null) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeaderIcon(NIcons.NetworkCheck, L.t("تست", "Test"), cs.onSurfaceVariant, onTest)
            if (subUrl != null) HeaderIcon(NIcons.QrCode2, L.t("QR لینک اشتراک", "Subscription QR"), cs.onSurfaceVariant) { qr = true }
            if (onRefresh != null) HeaderIcon(NIcons.Refresh, L.t("به‌روزرسانی", "Update"), cs.onSurfaceVariant, onRefresh)
            if (onDelete != null) HeaderIcon(NIcons.Delete, L.t("حذف", "Delete"), cs.error) { confirm = true }
            Icon(if (collapsed) NIcons.ExpandMore else NIcons.ExpandLess, if (collapsed) L.t("باز کردن", "Expand") else L.t("بستن", "Collapse"),
                tint = cs.onSurfaceVariant)
        }
    }
    if (qr && subUrl != null) QrDialog(sec.title, subUrl, L.t("با گوشی اسکن کنید تا این اشتراک آنجا هم اضافه شود.", "Scan it with the phone to add this subscription there too.")) { qr = false }
    if (confirm && onDelete != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(L.t("حذف «${sec.title}»؟", "Delete \"${sec.title}\"?")) },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(L.t("حذف", "Delete"), color = cs.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(L.t("انصراف", "Cancel")) } },
    )
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, label, Modifier.size(20.dp), tint = tint) }

@Composable
private fun ServerRow(s: Server, selected: Boolean, connected: Boolean, testing: Boolean, shape: androidx.compose.ui.graphics.Shape,
                      onSelect: () -> Unit, onConnect: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = shape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
        val wide = maxWidth >= 760.dp
        Column {
            HorizontalDivider(color = cs.outlineVariant)
            Row(
                Modifier.background(if (selected) cs.primaryContainer.copy(alpha = 0.7f) else Color.Transparent).clickable(onClick = onSelect)
                    .heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.size(20.dp))
                val name: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (connected) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.status.good))
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Addresses read left to right in both languages
                val address: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text("${s.protocol.label.lowercase()} · ${s.address}:${s.port}", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (wide) {
                    Box(Modifier.width(220.dp)) { name() }
                    Box(Modifier.weight(1f)) { address() }
                } else Column(Modifier.weight(1f)) { name(); address() }
                PingPill(s, testing)
                TextButton(onClick = onConnect, modifier = Modifier.height(36.dp)) { Text(L.t("اتصال", "Connect"), fontWeight = FontWeight.SemiBold) }
                var qr by remember { mutableStateOf(false) }
                val link = remember(s) { Repository.serverLink(s) }
                if (link.isNotBlank()) HeaderIcon(NIcons.QrCode2, "QR ${s.name}", cs.onSurfaceVariant) { qr = true }
                GroupMenu(s)
                HeaderIcon(NIcons.Delete, L.t("حذف ${s.name}", "Delete ${s.name}"), cs.onSurfaceVariant, onDelete)
                if (qr) QrDialog(s.name, link, L.t("با Netino یا v2rayNG روی گوشی اسکن کنید.", "Scan it with Netino or v2rayNG on the phone.")) { qr = false }
            }
        }
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

/** Put a server in groups (or a new one); groups show at the top of the list and as a scope for "fastest". */
@Composable
private fun GroupMenu(s: Server) {
    val groups by Repository.groups.collectAsState()
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        HeaderIcon(NIcons.CreateNewFolder, L.t("افزودن ${s.name} به گروه", "Add ${s.name} to a group"), MaterialTheme.colorScheme.onSurfaceVariant) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            groups.forEach { g ->
                val member = s.id in g.serverIds
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(g.name) },
                    leadingIcon = { androidx.compose.material3.Checkbox(checked = member, onCheckedChange = null) },
                    onClick = { Repository.setInGroup(g.id, s.id, !member) },
                )
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text(L.t("گروه جدید…", "New group…")) }, onClick = { open = false; naming = true })
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(L.t("گروه جدید", "New group")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(L.t("نام", "Name")) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { Repository.createGroup(name.trim(), listOf(s.id)); naming = false }) { Text(L.t("ساختن", "Create")) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(L.t("انصراف", "Cancel")) } },
        )
    }
}
