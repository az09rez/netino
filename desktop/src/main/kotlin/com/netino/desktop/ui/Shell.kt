package com.netino.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
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
 * The window's layout: the navigation on the start side (a side menu with labels on a wide window, an icon
 * rail on a medium one, a bottom bar on a narrow one), search everywhere above the page, and the page on its
 * own panel.
 */
object Ui {
    /** 0 home, 1 servers, 2 settings, 3 log, 4 usage, 5 tools (log and tools also sit inside settings; the self-test walks through all). */
    val tab = MutableStateFlow(0)
    val finderOpen = MutableStateFlow(false)
    val addOpen = MutableStateFlow(false)
    /** Search everywhere (Ctrl+K): actions, pages and every setting. */
    val paletteOpen = MutableStateFlow(false)
    /** The settings category on show; search everywhere opens one directly. */
    val settingsCat = MutableStateFlow("conn")

    /** The four sections, the same as on the phone. */
    val TABS = listOf(
        Triple(0, NIcons.Shield, { L.t("خانه", "Home") }),
        Triple(1, NIcons.Dns, { L.t("سرورها", "Servers") }),
        Triple(4, NIcons.BarChart, { L.t("مصرف", "Usage") }),
        Triple(2, NIcons.Settings, { L.t("تنظیمات", "Settings") }),
    )

    fun openSettings(cat: String) { settingsCat.value = cat; tab.value = 2 }
}

private enum class NavSize { MENU, RAIL, BAR }

@Composable
fun AppShell(onMessage: (String) -> Unit, page: @Composable (Int) -> Unit) {
    val tab by Ui.tab.collectAsState()
    val finder by Ui.finderOpen.collectAsState()
    val adding by Ui.addOpen.collectAsState()
    val palette by Ui.paletteOpen.collectAsState()
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(cs.background)) {
        val size = when { maxWidth >= 1024.dp -> NavSize.MENU; maxWidth >= 640.dp -> NavSize.RAIL; else -> NavSize.BAR }
        val content: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                TopBar()
                Surface(shape = if (size == NavSize.BAR) RectangleShape else RoundedCornerShape(topStart = 12.dp),
                    color = if (isDarkSurface()) cs.surfaceContainerLow else cs.surfaceContainerLowest,
                    modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = PAGE_MAX).fillMaxSize()) { page(tab) }
                    }
                }
            }
        }
        if (size == NavSize.BAR) Column(Modifier.fillMaxSize()) {
            content(Modifier.weight(1f).fillMaxWidth())
            BottomNav(tab)
        } else Row(Modifier.fillMaxSize()) {
            SideNav(tab, expanded = size == NavSize.MENU)
            content(Modifier.weight(1f).fillMaxHeight())
        }
    }
    if (palette) CommandPalette(onMessage, onDismiss = { Ui.paletteOpen.value = false })
    if (finder) FinderDialog(onDismiss = { Ui.finderOpen.value = false })
    if (adding) AddDialog(onDismiss = { Ui.addOpen.value = false }, onMessage = onMessage)
}

private val PAGE_MAX = 1280.dp

// ---------------------------------------------------------------- top bar

/** The search field above every page: opens search everywhere. */
@Composable
private fun TopBar() {
    val cs = MaterialTheme.colorScheme
    val hint = L.t("جست‌وجوی تنظیمات و کارها…", "Search settings and actions…")
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = { Ui.paletteOpen.value = true }, shape = CircleShape, color = if (isDarkSurface()) cs.surfaceContainer else cs.surfaceContainerLowest,
            border = BorderStroke(1.dp, cs.outlineVariant),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().fillMaxHeight().semantics { contentDescription = "$hint (Ctrl+K)" }) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NIcons.Search, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Kbd("Ctrl+K")
            }
        }
    }
}

/** A keyboard key, as it's written on the keyboard (always left to right). */
@Composable
internal fun Kbd(keys: String) {
    val cs = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(shape = RoundedCornerShape(5.dp), color = Color.Transparent, border = BorderStroke(1.dp, cs.outline)) {
            Text(keys, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

// ---------------------------------------------------------------- navigation

/** Wide window: logo and name, the sections with labels, and the connection's state at the bottom. Medium: icons over short labels. */
@Composable
private fun SideNav(tab: Int, expanded: Boolean) {
    val cs = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    Column(Modifier.width(if (expanded) 232.dp else 80.dp).fillMaxHeight().padding(horizontal = if (expanded) 12.dp else 8.dp, vertical = 8.dp),
        horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = if (expanded) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource("netino.png"), L.t("نتینو", "Netino"), Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            if (expanded) Text(L.t("نتینو", "Netino"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Ui.TABS.forEachIndexed { n, (i, icon, label) ->
            val on = i == tab
            val bg by animateColorAsState(if (on) cs.primaryContainer else Color.Transparent, motion.spec(Motion.Fast))
            val fg = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
            val item = Modifier.clip(RoundedCornerShape(12.dp)).background(bg).selectable(on, role = Role.Tab) { Ui.tab.value = i }
                .semantics { contentDescription = label() + " (Ctrl+${n + 1})" }
            if (expanded) Row(item.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(26.dp), tint = fg)
                Text(label(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 1)
            } else Column(item.width(64.dp).heightIn(min = 60.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                Icon(icon, null, Modifier.size(26.dp), tint = fg)
                Text(label(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        NavStatus(expanded)
    }
}

/** Narrow window: the sections in a bar along the bottom. */
@Composable
private fun BottomNav(tab: Int) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.background)) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Ui.TABS.forEach { (i, icon, label) ->
                val on = i == tab
                val fg = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
                Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).selectable(on, role = Role.Tab) { Ui.tab.value = i }
                    .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Box(Modifier.size(56.dp, 30.dp).clip(CircleShape).background(if (on) cs.primaryContainer else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(24.dp), tint = fg)
                    }
                    Text(label(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = if (on) cs.onSurface else cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The connection's state under the sections (a dot alone on the rail); goes to the home page. */
@Composable
private fun NavStatus(expanded: Boolean) {
    val cs = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    val state by Controller.state.collectAsState()
    val (dot, label, sub) = when (val s = state) {
        is ConnState.Connected -> Triple(st.good, L.t("متصل و امن", "Connected"), s.server.name)
        is ConnState.Connecting -> Triple(st.warn, L.t("در حال اتصال…", "Connecting…"), s.server.name)
        is ConnState.Error -> Triple(st.bad, L.t("اتصال برقرار نشد", "Couldn't connect"), null)
        ConnState.Disconnected -> Triple(cs.outline, L.t("متصل نیست", "Not connected"), null)
    }
    Surface(onClick = { Ui.tab.value = 0 }, shape = RoundedCornerShape(14.dp), color = cs.surfaceContainer,
        modifier = (if (expanded) Modifier.fillMaxWidth() else Modifier.size(56.dp))
            .semantics { contentDescription = label + (sub?.let { " · $it" } ?: ""); liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.spacedBy(10.dp) else Arrangement.Center) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            if (expanded) Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---------------------------------------------------------------- search everywhere

private class PaletteItem(val icon: ImageVector, val label: String, val where: String, val keys: String, val shortcut: String? = null, val run: () -> Unit)

/** Every action, page and setting, found by any word in either language; arrows and Enter pick one. */
private fun paletteItems(onMessage: (String) -> Unit): List<PaletteItem> {
    val action = L.t("کار", "Action")
    val page = L.t("صفحه", "Page")
    val settings = L.t("تنظیمات", "Settings")
    val nothing = L.t("هیچ راهی وصل نشد", "Nothing got through")
    return listOf(
        PaletteItem(NIcons.PowerSettingsNew, L.t("اتصال یا قطع", "Connect or disconnect"), action, "connect disconnect اتصال قطع وصل", "Ctrl+Enter") { Controller.toggle() },
        PaletteItem(NIcons.Speed, L.t("اتصال به سریع‌ترین", "Connect to fastest"), action, "fastest best سریع بهترین") {
            Controller.connectFastest { onMessage(L.t("هیچ سروری جواب نداد", "No server answered")) }
        },
        PaletteItem(NIcons.Bolt, L.t("اتصال فوق‌سریع (WARP)", "Super-fast connect (WARP)"), action, "warp cloudflare کلودفلر") {
            Controller.connectWarp { if (!it) onMessage(L.t("WARP وصل نشد", "WARP didn't connect")) }
        },
        PaletteItem(NIcons.PublicOff, L.t("اتصال در حالت اینترنت ملی", "Connect in national internet mode"), action, "national internet shutdown اینترنت ملی قطعی") {
            Controller.connectNational { if (!it) onMessage(nothing) }
        },
        PaletteItem(NIcons.Warning, L.t("حالت اضطراری: همهٔ راه‌ها", "Emergency mode: try every way"), action, "emergency rescue nothing connects اضطراری وصل نمی‌شود") {
            Controller.connectEmergency { if (!it) onMessage(nothing) }
        },
        PaletteItem(NIcons.Radar, L.t("سرور یاب: امتیاز همهٔ سرورها", "Server finder: score every server"), action, "finder score find سرور یاب امتیاز") { Ui.finderOpen.value = true },
        PaletteItem(NIcons.Add, L.t("افزودن سرور یا اشتراک", "Add a server or subscription"), action, "add paste clipboard qr import افزودن کلیپ‌بورد چسباندن", "Ctrl+N") { Ui.addOpen.value = true },
        PaletteItem(NIcons.NetworkCheck, L.t("تست همهٔ سرورها", "Test all servers"), action, "test ping delay تست تأخیر پینگ") {
            Controller.testServers(Repository.servers.value.filter { it.subscriptionId != Warp.SUB })
        },
        PaletteItem(NIcons.Shield, L.t("خانه", "Home"), page, "home خانه", "Ctrl+1") { Ui.tab.value = 0 },
        PaletteItem(NIcons.Dns, L.t("سرورها", "Servers"), page, "servers subscriptions groups سرورها اشتراک گروه", "Ctrl+2") { Ui.tab.value = 1 },
        PaletteItem(NIcons.BarChart, L.t("مصرف", "Usage"), page, "usage data volume مصرف حجم", "Ctrl+3") { Ui.tab.value = 4 },
    ) + SETTINGS_CATS.map { c -> PaletteItem(c.icon, c.title(), settings, c.keys, if (c.id == "conn") "Ctrl+," else null) { Ui.openSettings(c.id) } }
}

@Composable
private fun CommandPalette(onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var active by remember { mutableIntStateOf(0) }
    val items = remember { paletteItems(onMessage) }
    val found = items.filter { matches(query, it.label + " " + it.where + " " + it.keys) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun pick(i: Int) { found.getOrNull(i)?.let { onDismiss(); it.run() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        confirmButton = {},
        text = {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it; active = 0 }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text(L.t("جست‌وجوی تنظیمات و کارها…", "Search settings and actions…")) },
                    leadingIcon = { Icon(NIcons.Search, null) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                            Key.DirectionDown -> { active = (active + 1).coerceAtMost((found.size - 1).coerceAtLeast(0)); true }
                            Key.DirectionUp -> { active = (active - 1).coerceAtLeast(0); true }
                            Key.Enter, Key.NumPadEnter -> { pick(active); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    })
                if (found.isEmpty()) Text(L.t("چیزی پیدا نشد", "Nothing found"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(found, key = { _, it -> it.label + it.where }) { i, item ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (i == active) cs.surfaceContainerHigh else Color.Transparent)
                            .clickable { pick(i) }.semantics { selected = i == active }.heightIn(min = 52.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(item.icon, null, tint = cs.primary)
                            Column(Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.where, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            item.shortcut?.let { Kbd(it) }
                        }
                    }
                }
                Text(L.t("↑↓ جابه‌جایی · Enter باز کردن · Esc بستن", "↑↓ move · Enter open · Esc close"), style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant)
            }
        },
    )
}

/** "Test all": while it runs, a ring that fills with each result and a stop square in its middle. */
@Composable
internal fun TestAllButton(progress: Pair<Int, Int>?, onClick: () -> Unit) {
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
