package com.netino.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.desktop.SearchProgress
import com.netino.desktop.Win
import com.netino.vpn.core.NationalNet
import com.netino.vpn.data.Repository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onMessage: (String) -> Unit) {
    val state by Controller.state.collectAsState()
    val search by Controller.search.collectAsState()
    val rescue by Controller.rescue.collectAsState()
    val settings by Repository.settings.collectAsState()
    val update by com.netino.desktop.Updater.state.collectAsState()
    val idle = state is ConnState.Disconnected || state is ConnState.Error
    var netState by remember { mutableStateOf<NationalNet.State?>(null) }
    LaunchedEffect(idle) { if (idle) netState = NationalNet.check() }
    val stuck by Controller.killStuck.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---- notices ----
            val u = update
            val updateText = when (u) {
                is com.netino.desktop.Updater.State.Available -> L.t("نسخهٔ ${localDigits(u.release.version)} آماده است", "Version ${u.release.version} is available")
                is com.netino.desktop.Updater.State.Downloading -> L.t("دانلود نسخهٔ ${localDigits(u.release.version)}… ${localDigits("${(u.progress * 100).toInt()}")}٪",
                    "Downloading ${u.release.version}… ${(u.progress * 100).toInt()}%")
                is com.netino.desktop.Updater.State.Ready -> L.t("نسخهٔ ${localDigits(u.release.version)} دانلود و بررسی شد", "Version ${u.release.version} downloaded and verified")
                else -> null
            }
            val updScope = rememberCoroutineScope()
            Banner(updateText != null, BannerKind.INFO, NIcons.SystemUpdate, updateText.orEmpty(),
                when (u) {
                    is com.netino.desktop.Updater.State.Available -> L.t("به‌روزرسانی", "Update")
                    is com.netino.desktop.Updater.State.Ready -> L.t("نصب", "Install")
                    else -> null
                }) {
                when (u) {
                    is com.netino.desktop.Updater.State.Available -> updScope.launch { com.netino.desktop.Updater.download(u.release) }
                    is com.netino.desktop.Updater.State.Ready -> if (com.netino.desktop.Updater.install(u.file)) { Controller.shutdown(); kotlin.system.exitProcess(0) }
                    else -> {}
                }
            }
            Banner(idle && netState == NationalNet.State.NATIONAL, BannerKind.WARN, NIcons.PublicOff,
                L.t("فقط سایت‌های ایرانی باز می‌شوند: اینترنت ملی", "Only Iranian sites open: national internet"), L.t("اتصال ویژه", "Connect")) {
                Controller.connectNational { if (!it) onMessage(L.t("هیچ راهی وصل نشد", "Nothing got through")) }
            }
            Banner(state !is ConnState.Connected && (stuck || com.netino.desktop.KillSwitch.active), BannerKind.BAD, NIcons.Warning,
                if (stuck) L.t("قطع امن اینترنت را بسته نگه داشته و بدون دسترسی ادمین باز نمی‌شود.", "The kill switch is blocking the internet and needs administrator rights to release.")
                else L.t("قطع امن روشن است: تا وصل شدن دوباره یا زدن «باز کردن»، اینترنت بسته است.", "Kill switch on: the internet is blocked until it reconnects or you release it."),
                if (stuck) L.t("اجرا به‌عنوان ادمین", "Run as administrator") else L.t("باز کردن", "Release"),
            ) { if (stuck) Controller.restartAsAdmin() else Controller.disconnect() }

            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                ConnectPanel(state, search, rescue, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { SideColumn(state, search, rescue, idle, onMessage) }
            } else {
                ConnectPanel(state, search, rescue, Modifier.fillMaxWidth())
                SideColumn(state, search, rescue, idle, onMessage)
            }
        }
    }

    // Asked once, when Netino's service exists: anonymous results of the free list
    val hasApi = remember { com.netino.vpn.core.NetinoApi.base != null }
    if (hasApi && settings.shareResults == null) androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text(L.t("به بهتر شدن لیست رایگان کمک می‌کنید؟", "Help improve the free list?")) },
        text = { Text(L.t("نتینو می‌تواند روزی یک بار بفرستد که کدام کانفیگ‌های رایگان روی شبکهٔ شما کار کردند: برای هر کانفیگ فقط یک کد کوتاه و اینکه وصل شد یا نه؛ نه IP، نه اطلاعات شخصی، نه سایت‌هایی که می‌روید. سرویس فقط از روی اتصال می‌فهمد از کدام اپراتور است، تا کسانی که روی همان اپراتورند اول کانفیگ‌های سالم را بگیرند. در «ابزارها» قابل تغییر است.",
            "Netino can send, once a day, which free configs worked on your network: a short code for each config and whether it connected, nothing else (no IP, personal data or browsing). The service only reads which ISP the connection comes from, so people on the same ISP get working configs first. You can change this in Tools.")) },
        confirmButton = { androidx.compose.material3.Button(onClick = { Repository.updateSettings { it.copy(shareResults = true) } }) { Text(L.t("بله، می‌فرستم", "Yes, share")) } },
        dismissButton = { TextButton(onClick = { Repository.updateSettings { it.copy(shareResults = false) } }) { Text(L.t("نه", "No")) } },
    )
}

private fun searchFraction(p: SearchProgress?): Float {
    p ?: return 0f
    val f = if (p.total > 0) p.done.toFloat() / p.total else 0.3f
    return if (p.stage >= Controller.STAGE_WARP) ((p.stage - Controller.STAGE_WARP - 1) + f) / 3f else (p.stage + f) / 4f
}

/** The big button, what it's doing, live numbers and the connection type (system proxy / full tunnel). */
@Composable
private fun ConnectPanel(state: ConnState, search: SearchProgress?, rescue: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    val traffic by Controller.traffic.collectAsState()
    val look = when {
        search != null -> ConnectLook.SEARCHING
        state is ConnState.Connected -> ConnectLook.CONNECTED
        state is ConnState.Connecting -> ConnectLook.CONNECTING
        state is ConnState.Error -> ConnectLook.ERROR
        else -> ConnectLook.OFF
    }
    val title = when (look) {
        ConnectLook.CONNECTED -> L.t("متصل و امن", "Connected and secure")
        ConnectLook.CONNECTING -> L.t("در حال اتصال…", "Connecting…")
        ConnectLook.SEARCHING -> L.t("در حال جست‌وجو…", "Searching…")
        ConnectLook.ERROR -> L.t("اتصال برقرار نشد", "Couldn't connect")
        ConnectLook.OFF -> L.t("متصل نیست", "Not connected")
    }
    val titleColor = when (look) {
        ConnectLook.CONNECTED -> st.goodText
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warnText
        ConnectLook.ERROR -> st.badText
        ConnectLook.OFF -> cs.onSurface
    }
    Panel(modifier, padding = 24) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectButton(look, if (Controller.isActive) L.t("قطع اتصال", "Disconnect") else L.t("اتصال", "Connect"), title,
                searchFraction(search)) { Controller.toggle() }
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), fontWeight = FontWeight.Bold, color = titleColor,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            when (val s = state) {
                is ConnState.Connected -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(s.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(localDigits(formatDuration(now - s.since)), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp,
                            fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                    }
                }
                else -> Text(
                    when {
                        search != null -> stageText(search, rescue)
                        s is ConnState.Error -> s.message
                        s is ConnState.Connecting -> s.server.name
                        else -> L.t("برای اتصال کلیک کنید", "Click to connect") + if (Prefs.hotkey) " · Ctrl+Alt+N" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (state is ConnState.Connected) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveTile("↓ " + L.t("دانلود", "Download"), formatBits(traffic.downBps), Modifier.weight(1f))
                    LiveTile("↑ " + L.t("آپلود", "Upload"), formatBits(traffic.upBps), Modifier.weight(1f))
                    LiveTile(L.t("حجم", "Volume"), localDigits(formatBytes(traffic.sessionRx + traffic.sessionTx)), Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(NIcons.CheckCircle, null, Modifier.size(18.dp), tint = st.goodText)
                    Text(
                        if (Prefs.tunMode) L.t("تونل کامل: همهٔ برنامه‌ها از نتینو رد می‌شوند", "Full tunnel: every program goes through Netino")
                        else if (Prefs.systemProxy) L.t("پراکسی ویندوز روی نتینو تنظیم شد", "The Windows proxy points at Netino")
                        else L.t("پراکسی: HTTP ${Controller.LOCAL_HTTP} • SOCKS ${Controller.LOCAL_SOCKS}", "Proxy: HTTP ${Controller.LOCAL_HTTP} • SOCKS ${Controller.LOCAL_SOCKS}"),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            ModePicker(Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun LiveTile(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest.let {
        if (isDarkSurface()) MaterialTheme.colorScheme.surfaceContainerHigh else it }, modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold,
                maxLines = 1)
        }
    }
}

/** Bits per second, as speed tests and ISPs say it. */
private fun formatBits(bytesPerSec: Long): String {
    val bits = bytesPerSec * 8.0
    return localDigits(when {
        bits >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.US, bits / 1_000_000)
        bits >= 1_000 -> "%.0f Kbps".format(java.util.Locale.US, bits / 1_000)
        else -> "%.0f bps".format(java.util.Locale.US, bits)
    })
}

/** System proxy or full tunnel, right under the button; the tunnel asks to restart as administrator first. */
@Composable
private fun ModePicker(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    var tun by remember { mutableStateOf(Prefs.tunMode) }
    var askAdmin by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(L.t("نوع اتصال", "Connection type"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(1.dp, cs.outline, RoundedCornerShape(22.dp))) {
            listOf(false to (L.t("پراکسی سیستم", "System proxy") to NIcons.Public), true to (L.t("تونل کامل", "Full tunnel") to NIcons.ShieldPerson))
                .forEach { (value, p) ->
                    val (label, icon) = p
                    val on = tun == value
                    Row(
                        Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) cs.primaryContainer else Color.Transparent)
                            .selectable(on, role = Role.RadioButton) {
                                if (on) return@selectable
                                if (value && !Win.isAdmin && Win.isWindows) askAdmin = true
                                else { tun = value; Prefs.tunMode = value; Controller.reapply() }
                            },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(icon, null, Modifier.size(18.dp), tint = if (on) cs.onPrimaryContainer else cs.onSurface)
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) cs.onPrimaryContainer else cs.onSurface)
                    }
                }
        }
        Text(
            if (tun) L.t("همهٔ برنامه‌ها، حتی بازی‌ها و تلگرام دسکتاپ · با دسترسی ادمین", "Every program, games and desktop apps too · needs administrator")
            else L.t("مرورگرها و بیشتر برنامه‌ها · بدون نیاز به ادمین", "Browsers and most programs · no administrator needed"),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (askAdmin) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askAdmin = false },
        icon = { Icon(NIcons.ShieldPerson, null, tint = cs.primary) },
        title = { Text(L.t("تونل کامل دسترسی ادمین می‌خواهد", "The full tunnel needs administrator rights"), textAlign = TextAlign.Center) },
        text = { Text(L.t("برای اینکه همهٔ برنامه‌ها از نتینو رد شوند، نتینو یک بار دیگر با دسترسی ادمین باز می‌شود. اتصال فعلی چند ثانیه قطع می‌شود.",
            "So that every program goes through Netino, it opens again with administrator rights. The connection drops for a few seconds.")) },
        confirmButton = { androidx.compose.material3.Button(onClick = {
            askAdmin = false
            Prefs.tunMode = true
            if (!Controller.restartAsAdmin()) { Prefs.tunMode = false; tun = false }
        }) { Text(L.t("اجرای دوباره به‌عنوان ادمین", "Restart as administrator")) } },
        dismissButton = { TextButton(onClick = { askAdmin = false }) { Text(L.t("لغو", "Cancel")) } },
    )
}

/** Current server, the search or speed test, and the other ways to connect. */
@Composable
private fun SideColumn(state: ConnState, search: SearchProgress?, rescue: Int, idle: Boolean, onMessage: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ServerCard()
    search?.let { p ->
        val warp = p.stage >= Controller.STAGE_WARP
        val steps = if (warp) 3 else 4
        val cur = if (warp) p.stage - Controller.STAGE_WARP - 1 else p.stage
        ProgressCard(stageText(p, rescue), steps, cur.coerceIn(0, steps - 1), if (p.total > 0) p.done.toFloat() / p.total else null) { Controller.cancelSearch() }
    }
    if (state is ConnState.Connected) SpeedTestCard()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilledTonalButton(
            onClick = { Controller.connectFastest { onMessage(L.t("هیچ سروری جواب نداد", "No server answered")) } }, enabled = search == null,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            Icon(NIcons.Speed, null); Spacer(Modifier.width(6.dp))
            Text(L.t("سریع‌ترین", "Fastest") + " • " + scopeLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
        ScopeButton()
        androidx.compose.material3.Button(
            onClick = { Controller.connectWarp { if (!it) onMessage(L.t("WARP وصل نشد", "WARP didn't connect")) } }, enabled = search == null,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.heightIn(min = 52.dp),
        ) { Icon(NIcons.Bolt, null); Spacer(Modifier.width(6.dp)); Text("WARP", fontWeight = FontWeight.SemiBold) }
    }
    OutlinedButton(
        onClick = { Controller.connectNational { if (!it) onMessage(L.t("هیچ راهی وصل نشد", "Nothing got through")) } }, enabled = search == null,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, cs.outline), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Icon(NIcons.PublicOff, null); Spacer(Modifier.width(6.dp)); Text(L.t("اینترنت ملی", "National internet"), fontWeight = FontWeight.SemiBold) }
    // Last resort, once the usual buttons haven't worked
    if (search == null && idle) TextButton(onClick = {
        Controller.connectEmergency { if (!it) onMessage(L.t("هیچ راهی وصل نشد؛ گزارش را برای سازنده بفرستید", "Nothing got through; send the report to the developer")) }
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Text(L.t("هیچ‌چیز وصل نمی‌شود؟ حالت اضطراری", "Nothing connects? Emergency mode"), textDecoration = TextDecoration.Underline)
    }
}

/** The full tunnel was asked for without administrator rights. */
@Composable
fun AdminDialog(onMessage: (String) -> Unit) {
    val ask by Controller.needsAdmin.collectAsState()
    if (!ask) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { Controller.dismissAdmin() },
        title = { Text(L.t("تونل کامل دسترسی ادمین می‌خواهد", "The full tunnel needs administrator rights")) },
        text = { Text(L.t("برای ساختن کارت شبکهٔ مجازی، Netino باید با دسترسی ادمین اجرا شود. Netino دوباره باز می‌شود و ویندوز اجازه می‌خواهد. یا در تنظیمات حالت «پراکسی سیستم» را انتخاب کنید.",
            "To create its network adapter Netino must run as administrator. It will start again and Windows will ask. Or choose \"System proxy\" in Settings.")) },
        confirmButton = { androidx.compose.material3.Button(onClick = {
            if (!Controller.restartAsAdmin()) onMessage(L.t("اجرا با دسترسی ادمین انجام نشد", "Couldn't restart as administrator"))
        }) { Text(L.t("اجرای دوباره به‌عنوان ادمین", "Restart as administrator")) } },
        dismissButton = { TextButton(onClick = { Controller.dismissAdmin() }) { Text(L.t("انصراف", "Cancel")) } },
    )
}

private fun stageText(p: SearchProgress, rescue: Int): String {
    val steps = Controller.rescueSteps()
    val step = if (rescue > 0) L.t("مرحلهٔ $rescue از $steps • ", "Step $rescue of $steps • ") else ""
    return step + when (p.stage) {
        0 -> L.t("بررسی فایروال این شبکه…", "Checking this network's firewall…")
        1 -> L.t("تست سرورها ${p.done}/${p.total}", "Testing servers ${p.done}/${p.total}")
        2 -> L.t("تست سرعت ${p.done}/${p.total}", "Speed test ${p.done}/${p.total}") + (p.name?.let { " • $it" } ?: "")
        3 -> L.t("اتصال…", "Connecting…")
        Controller.STAGE_WARP + 1 -> L.t("ساخت حساب WARP…", "Making a WARP account…")
        Controller.STAGE_WARP + 2 -> L.t("جست‌وجوی نقطهٔ اتصال WARP ${p.done}/${p.total}", "Scanning WARP endpoints ${p.done}/${p.total}")
        Controller.STAGE_WARP + 3 -> L.t("بررسی WARP در WARP…", "Checking WARP in WARP…")
        else -> L.t("لطفاً صبر کنید…", "Please wait…")
    }
}

