package com.netino.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.vpn.core.Warp
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.data.ServerSort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The window's layout (after SoulConnection): the servers always at hand in a side panel, the page on the
 * other side under a header with its icon buttons, and a status bar with the live numbers underneath.
 */
object Ui {
    /** 0 home, 1 server management, 2 settings, 3 log, 4 usage, 5 tools (a flow: the self-test walks through them). */
    val tab = MutableStateFlow(0)
    val finderOpen = MutableStateFlow(false)
    val addOpen = MutableStateFlow(false)
    /** The side panel, shown by default on a wide window; the header's button shows or hides it. */
    val sidebar = MutableStateFlow<Boolean?>(null)

    val TABS = listOf(
        Triple(0, NIcons.Shield, { L.t("کنترل اتصال", "Connection") }),
        Triple(1, NIcons.Dns, { L.t("مدیریت سرورها", "Manage servers") }),
        Triple(4, NIcons.BarChart, { L.t("مصرف", "Usage") }),
        Triple(5, NIcons.Build, { L.t("ابزارها", "Tools") }),
        Triple(2, NIcons.Settings, { L.t("تنظیمات", "Settings") }),
        Triple(3, NIcons.ReceiptLong, { L.t("گزارش", "Log") }),
    )
}

private val SIDEBAR = 320.dp
private val PAGE_MAX = 1100.dp

@Composable
fun AppShell(onMessage: (String) -> Unit, page: @Composable (Int) -> Unit) {
    val tab by Ui.tab.collectAsState()
    val sidebarPref by Ui.sidebar.collectAsState()
    val finder by Ui.finderOpen.collectAsState()
    val adding by Ui.addOpen.collectAsState()
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val showSidebar = sidebarPref ?: (maxWidth >= 900.dp)
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(showSidebar) {
                    Row {
                        Sidebar(onMessage, Modifier.width(SIDEBAR).fillMaxHeight())
                        VerticalDivider(color = cs.outlineVariant)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(tab, showSidebar)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = PAGE_MAX).fillMaxSize()) { page(tab) }
                    }
                }
            }
        }
        StatusBar()
    }
    if (finder) FinderDialog(onDismiss = { Ui.finderOpen.value = false })
    if (adding) AddDialog(onDismiss = { Ui.addOpen.value = false }, onMessage = onMessage)
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(tab: Int, sidebarShown: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { Ui.sidebar.value = !sidebarShown }) {
            Icon(NIcons.Menu, if (sidebarShown) L.t("بستن فهرست سرورها", "Hide the servers") else L.t("نمایش فهرست سرورها", "Show the servers"),
                tint = cs.onSurfaceVariant)
        }
        val title = Ui.TABS.firstOrNull { it.first == tab }?.third?.invoke().orEmpty()
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Ui.TABS.forEach { (i, icon, label) ->
            val on = i == tab
            IconButton(
                onClick = { Ui.tab.value = i },
                modifier = Modifier.semantics { selected = on },
                colors = if (on) IconButtonDefaults.iconButtonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)
                else IconButtonDefaults.iconButtonColors(contentColor = cs.onSurfaceVariant),
            ) { Icon(icon, label()) }
        }
    }
}

// ---------------------------------------------------------------- side panel

@Composable
private fun Sidebar(onMessage: (String) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val servers by Repository.servers.collectAsState()
    val subs by Repository.subscriptions.collectAsState()
    val groups by Repository.groups.collectAsState()
    val settings by Repository.settings.collectAsState()
    val state by Controller.state.collectAsState()
    val search by Controller.search.collectAsState()
    var query by remember { mutableStateOf("") }
    val connectedId = (state as? ConnState.Connected)?.server?.id

    Column(modifier.background(if (isDarkSurface()) cs.surfaceContainerLowest else cs.surfaceContainer)) {
        // ---- brand ----
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource("netino.png"), null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                Text(L.t("نتینو", "Netino"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (servers.isEmpty()) L.t("کلاینت VPN", "VPN client") else L.t("${localDigits("${servers.size}")} کانفیگ", "${servers.size} configs"),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        FreeListEntry(servers.count { it.subscriptionId == Repository.BUILTIN_SUB_ID })

        // ---- toolbar: search, sort, test all ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchField(query, { query = it }, L.t("جست‌وجو", "Search"), Modifier.weight(1f))
            SortButton(settings.serverSort)
            TestAllButton(search?.takeIf { it.stage == 1 }?.let { it.done to it.total }) {
                if (search != null) Controller.cancelSearch() else Controller.testServers(servers.filter { it.subscriptionId != Warp.SUB })
            }
        }

        // ---- the list ----
        val sections = remember(servers, subs, groups) {
            val bySub = servers.groupBy { it.subscriptionId }
            buildList {
                groups.forEach { g -> add(Triple("g:${g.id}", g.name, Repository.groupServers(g, servers))) }
                bySub[null]?.let { add(Triple("manual", L.t("سرورهای من", "My servers"), it)) }
                bySub[Warp.SUB]?.let { add(Triple("warp", "WARP", it)) }
                subs.forEach { s -> add(Triple(s.id, if (s.builtIn) L.t("رایگان نتینو", "Netino free") else s.name.ifBlank { s.url }, bySub[s.id].orEmpty())) }
            }
        }
        fun sorted(l: List<Server>) = l.filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }.let { f ->
            when (settings.serverSort) {
                ServerSort.PING -> f.sortedBy { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE }
                ServerSort.NAME -> f.sortedBy { it.name.lowercase() }
                ServerSort.DEFAULT -> f
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            if (servers.isEmpty()) item {
                Text(L.t("هنوز سروری نیست. لینک کانفیگ یا اشتراک را کپی کنید و Ctrl+V بزنید.", "No servers yet. Copy a config or subscription link and press Ctrl+V."),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
            sections.forEach { (key, title, list) ->
                val shown = sorted(list)
                if (query.isNotBlank() && shown.isEmpty()) return@forEach
                val collapsed = query.isBlank() && "side:$key" in settings.collapsed
                item(key = "h:$key") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Repository.toggleCollapsed("side:$key") }
                        .heightIn(min = 36.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (collapsed) NIcons.ExpandMore else NIcons.ExpandLess, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(localDigits("${list.size}"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (!collapsed) items(shown, key = { "$key/${it.id}" }) { s ->
                    SideRow(s, selected = s.id == settings.selectedServerId, connected = s.id == connectedId,
                        testing = search?.stage == 1 && s.lastPingMs == -1L, onMessage = onMessage)
                }
            }
        }

        // ---- add, finder ----
        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { Ui.addOpen.value = true }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(NIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.t("افزودن کانفیگ", "Add config"), maxLines = 1)
            }
            FilledTonalButton(onClick = { Ui.finderOpen.value = true }, modifier = Modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(NIcons.Radar, L.t("سرور یاب (Ctrl+K)", "Server finder (Ctrl+K)"), Modifier.size(20.dp))
            }
        }
    }
}

/** Netino's own free list, apart from the user's servers: one click connects to its best. */
@Composable
private fun FreeListEntry(count: Int) {
    if (count == 0) return
    val cs = MaterialTheme.colorScheme
    val search by Controller.search.collectAsState()
    Surface(onClick = {
        Repository.updateSettings { it.copy(fastestScope = "sub:${Repository.BUILTIN_SUB_ID}") }
        Controller.connectFastest()
    }, enabled = search == null, shape = RoundedCornerShape(16.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(NIcons.Bolt, null)
            Column(Modifier.weight(1f)) {
                Text(L.t("کانفیگ‌های رایگان نتینو", "Netino free configs"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(L.t("${localDigits("$count")} کانفیگ · اتصال به بهترین", "$count configs · connect to the best"), style = MaterialTheme.typography.bodySmall)
            }
            Icon(NIcons.ChevronForward, null)
        }
    }
}

@Composable
private fun SortButton(sort: ServerSort) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(NIcons.Sort, L.t("مرتب‌سازی", "Sort"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(open, { open = false }) {
            listOf(ServerSort.PING to L.t("تأخیر", "Delay"), ServerSort.NAME to L.t("نام", "Name"), ServerSort.DEFAULT to L.t("پیش‌فرض", "Default")).forEach { (v, label) ->
                DropdownMenuItem({ Text(label) }, { open = false; Repository.updateSettings { it.copy(serverSort = v) } },
                    leadingIcon = { RadioButton(v == sort, null) })
            }
        }
    }
}

/** "Test all": while it runs, a ring that fills with each result and a stop square in its middle. */
@Composable
private fun TestAllButton(progress: Pair<Int, Int>?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick) {
        if (progress == null) Icon(NIcons.NetworkCheck, L.t("تست همه", "Test all"), tint = cs.onSurfaceVariant)
        else Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics {
            contentDescription = L.t("توقف تست (${progress.first} از ${progress.second})", "Stop the test (${progress.first} of ${progress.second})")
        }) {
            CircularProgressIndicator(progress = { if (progress.second == 0) 0f else progress.first.toFloat() / progress.second },
                modifier = Modifier.size(28.dp), strokeWidth = 3.dp, trackColor = cs.primaryContainer)
            Icon(NIcons.Stop, null, Modifier.size(12.dp), tint = cs.primary)
        }
    }
}

/** A server in the side panel: click selects (and switches to it while connected); right click for more. */
@Composable
private fun SideRow(s: Server, selected: Boolean, connected: Boolean, testing: Boolean, onMessage: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var qr by remember { mutableStateOf(false) }
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem(L.t("اتصال", "Connect")) { Controller.connect(s) },
            ContextMenuItem(L.t("نمایش QR", "Show QR")) { qr = true },
            ContextMenuItem(L.t("کپی لینک", "Copy link")) { Qr.copy(Repository.serverLink(s)); onMessage(L.t("کپی شد", "Copied")) },
            ContextMenuItem(L.t("حذف", "Delete")) { Repository.deleteServer(s.id) },
        )
    }) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (selected) cs.primaryContainer.copy(alpha = 0.7f) else Color.Transparent)
                .clickable { Repository.select(s.id); if (Controller.isActive) Controller.connect(s) }
                .semantics { this.selected = selected }
                .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ServerAvatar(s.protocol, 36)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (connected) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.status.good))
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(s.protocol.label.lowercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            PingPill(s, testing)
        }
    }
    if (qr) QrDialog(s.name, Repository.serverLink(s), L.t("با Netino یا v2rayNG روی گوشی اسکن کنید.", "Scan it with Netino or v2rayNG on the phone.")) { qr = false }
}

// ---------------------------------------------------------------- status bar

/** The live numbers, always visible: state, server, speeds, the session's volume and the connection's quality. */
@Composable
private fun StatusBar() {
    val cs = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    val state by Controller.state.collectAsState()
    val traffic by Controller.traffic.collectAsState()
    val health by Controller.health.collectAsState()
    val tun by Prefs.tunFlow.collectAsState()
    Surface(color = if (isDarkSurface()) cs.surfaceContainerLowest else cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val (dot, label) = when (val s = state) {
                is ConnState.Connected -> st.good to (L.t("متصل", "Connected") + " · " + s.server.name)
                is ConnState.Connecting -> st.warn to L.t("در حال اتصال…", "Connecting…")
                is ConnState.Error -> st.bad to L.t("متصل نیست", "Not connected")
                ConnState.Disconnected -> cs.outline to L.t("متصل نیست", "Not connected")
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (state is ConnState.Connected) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("↓ " + localDigits(formatSpeed(traffic.downBps)), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                        Text("↑ " + localDigits(formatSpeed(traffic.upBps)), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                        Text(localDigits(formatBytes(traffic.sessionRx + traffic.sessionTx)), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                health?.let { h ->
                    val c = when { h.score >= 70 -> st.goodText; h.score >= 40 -> st.warnText; else -> st.badText }
                    Text(L.t("کیفیت ${localDigits("${h.score}")}", "Quality ${h.score}"), style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(if (tun) L.t("تونل کامل", "Full tunnel") else L.t("پراکسی سیستم", "System proxy"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- tunnel status

/**
 * Proof that it works: the address the internet sees, measured from outside through the tunnel, with its
 * country and ISP; expanded, the connection's measured quality and how programs reach Netino.
 */
@Composable
fun TunnelStatusCard() {
    val cs = MaterialTheme.colorScheme
    val exit by Controller.exit.collectAsState()
    val health by Controller.health.collectAsState()
    val tun by Prefs.tunFlow.collectAsState()
    var open by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth(), padding = 0) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(NIcons.Public, null, tint = cs.primary)
            Column(Modifier.weight(1f)) {
                Text(L.t("ترافیک از اینجا بیرون می‌رود", "Traffic leaves from here"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                val e = exit
                if (e == null) Text(L.t("در حال سنجش آی‌پی خروجی…", "Measuring the exit address…"), style = MaterialTheme.typography.bodyMedium)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.countryCode?.let { cc ->
                        Surface(shape = RoundedCornerShape(6.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                            Text(cc.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(e.ip, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                    listOfNotNull(e.city, e.country).joinToString("، ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            health?.let { QualityBars(it.score) }
            Icon(if (open) NIcons.ExpandLess else NIcons.ExpandMore, if (open) L.t("بستن", "Collapse") else L.t("جزئیات", "Details"), tint = cs.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = cs.outlineVariant)
                exit?.isp?.let { Detail(L.t("ارائه‌دهنده", "Provider"), it) }
                health?.let { h ->
                    Detail(L.t("کیفیت", "Quality"), localDigits("${h.score}") + " / ۱۰۰".let { if (Prefs.fa) it else " / 100" })
                    h.latencyMs?.let { Detail(L.t("تأخیر", "Delay"), localDigits("${it.toLong()}") + " ms") }
                    h.jitterMs?.let { Detail(L.t("لرزش", "Jitter"), localDigits("${it.toLong()}") + " ms") }
                    Detail(L.t("بستهٔ گم‌شده", "Loss"), localDigits("${h.lossPct.toInt()}") + "%")
                }
                Detail(L.t("نوع اتصال", "Connection type"), if (tun) L.t("تونل کامل: همهٔ برنامه‌ها", "Full tunnel: every program")
                    else L.t("پراکسی سیستم (HTTP ${Controller.LOCAL_HTTP} · SOCKS ${Controller.LOCAL_SOCKS})", "System proxy (HTTP ${Controller.LOCAL_HTTP} · SOCKS ${Controller.LOCAL_SOCKS})"))
                TextButton(onClick = { Controller.measureExit() }) { Icon(NIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.t("سنجش دوباره", "Measure again")) }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Four bars, filled by the 0–100 score. */
@Composable
private fun QualityBars(score: Int) {
    val st = MaterialTheme.status
    val c = when { score >= 70 -> st.good; score >= 40 -> st.warn; else -> st.bad }
    val filled = ((score + 24) / 25).coerceIn(0, 4)
    Row(Modifier.semantics { contentDescription = L.t("کیفیت $score از ۱۰۰", "Quality $score of 100") }, horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 4) Box(Modifier.width(4.dp).height((6 + i * 4).dp).clip(RoundedCornerShape(1.dp))
            .background(if (i < filled) c else MaterialTheme.colorScheme.outlineVariant))
    }
}

// ---------------------------------------------------------------- server finder

/**
 * The server finder (Ctrl+K): real delay of every server of a scope, then the download speed of the best,
 * scored 0–100 on one scale and sorted by it; one click connects.
 */
@Composable
private fun FinderDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val all by Repository.servers.collectAsState()
    val found by Controller.found.collectAsState()
    val search by Controller.search.collectAsState()
    val options = scopeOptions()
    var scope by remember { mutableStateOf("all") }
    val list = remember(all, scope) { Repository.fastestCandidates(scope).filter { it.subscriptionId != Warp.SUB } }
    val ranked = list.sortedWith(compareByDescending<Server> { found[it.id]?.score ?: -1 }.thenBy { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE })
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(L.t("سرور یاب", "Server finder"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L.t("تأخیر واقعی همهٔ سرورها، بعد سرعت دانلود بهترین‌ها؛ همه در یک امتیاز ۰ تا ۱۰۰.",
                    "The real delay of every server, then the download speed of the best; all in one 0–100 score."),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options, key = { it.first }) { (key, label) ->
                        FilterChip(scope == key, onClick = { scope = key }, label = { Text(label, maxLines = 1) }, shape = RoundedCornerShape(12.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val p = search
                    if (p != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            modifier = Modifier.weight(1f).height(6.dp), trackColor = cs.primaryContainer, gapSize = 0.dp, drawStopIndicator = {})
                        Text(if (p.stage == 2) L.t("سرعت ${localDigits("${p.done}")} از ${localDigits("${p.total}")}", "Speed ${p.done} of ${p.total}")
                            else L.t("تأخیر ${localDigits("${p.done}")} از ${localDigits("${p.total}")}", "Delay ${p.done} of ${p.total}"),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { Controller.cancelSearch() }) { Text(L.t("توقف", "Stop")) }
                    } else {
                        Text(L.t("${localDigits("${list.size}")} سرور", "${list.size} servers"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Button(onClick = { Controller.runFinder(list) }, enabled = list.isNotEmpty()) { Text(L.t("شروع تست", "Start the test")) }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ranked, key = { it.id }) { s ->
                        val f = found[s.id]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Controller.connect(s); onDismiss() }
                            .heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ServerAvatar(s.protocol, 36)
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            PingPill(s)
                            Text(f?.downKBps?.let { localDigits(formatSpeed((it * 1024).toLong())) } ?: "—", style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant, modifier = Modifier.width(90.dp))
                            ScoreChip(f?.score)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("بستن", "Close")) } },
    )
}

@Composable
private fun ScoreChip(score: Int?) {
    val st = MaterialTheme.status
    val (bg, fg) = when {
        score == null -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        score >= 70 -> st.goodContainer to st.goodText
        score >= 40 -> st.warnContainer to st.warnText
        else -> st.badContainer to st.badText
    }
    Box(Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(score?.let { localDigits("$it") } ?: "—", color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}
