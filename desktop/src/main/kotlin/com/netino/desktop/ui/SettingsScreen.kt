package com.netino.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.netino.desktop.Autostart
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.desktop.Updater
import com.netino.desktop.Win
import com.netino.vpn.core.GeoFiles
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.FragmentMode
import com.netino.vpn.data.Repository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(onMessage: (String) -> Unit) {
    val s by Repository.settings.collectAsState()
    val theme by Prefs.theme.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(L.t("تنظیمات", "Settings"))
        Card(L.t("ویندوز", "Windows"), NIcons.Desktop) {
            val tun by Prefs.tunFlow.collectAsState()
            Text(L.t("نوع اتصال", "Connection type"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(!tun, L.t("پراکسی سیستم", "System proxy"), L.t("مرورگرها و بیشتر برنامه‌ها. ساده و بدون دسترسی ادمین.",
                    "Browsers and most programs. Simple, no administrator rights."), admin = false, Modifier.weight(1f)) { Controller.setTunnel(false) }
                ModeCard(tun, L.t("تونل کامل (TUN)", "Full tunnel (TUN)"), L.t("همهٔ برنامه‌ها، حتی بازی‌ها و تلگرام دسکتاپ.",
                    "Every program, games and desktop apps too."), admin = true, Modifier.weight(1f)) { Controller.setTunnel(true) }
            }
            if (tun && !Win.isAdmin) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.status.warnContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(NIcons.ShieldPerson, null, tint = MaterialTheme.status.warnText)
                        Text(L.t("نتینو بدون دسترسی ادمین اجرا شده است.", "Netino is running without administrator rights."),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            if (!Controller.restartAsAdmin()) onMessage(L.t("اجرا با دسترسی ادمین انجام نشد (اجازهٔ ویندوز داده نشد)", "Couldn't restart as administrator (Windows' prompt was declined)"))
                        }) { Text(L.t("اجرای دوباره به‌عنوان ادمین", "Restart as administrator")) }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = if (isDarkSurface()) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (tun) {
                        var kill by remember { mutableStateOf(Prefs.killSwitch) }
                        Toggle(L.t("قطع امن (Kill Switch)", "Kill switch"),
                            L.t("اگر تونل قطع شود، اینترنت تا اتصال دوباره یا زدن «قطع» بسته می‌ماند؛ چیزی بیرون از VPN نمی‌رود.",
                                "If the tunnel drops, the internet stays blocked until it reconnects or you disconnect: nothing leaves outside the VPN."),
                            kill) { kill = it; Prefs.killSwitch = it; Controller.reapply() }
                    }
                    if (!tun) {
                        var proxy by remember { mutableStateOf(Prefs.systemProxy) }
                        Toggle(L.t("تنظیم پراکسی ویندوز", "Set the Windows proxy"),
                            L.t("خاموش: فقط برنامه‌هایی که دستی روی پراکسی ${Controller.LOCAL_HTTP} (HTTP) یا ${Controller.LOCAL_SOCKS} (SOCKS5) تنظیم شده‌اند.",
                                "Off: only programs set by hand to ${Controller.LOCAL_HTTP} (HTTP) or ${Controller.LOCAL_SOCKS} (SOCKS5)."),
                            proxy) { proxy = it; Prefs.systemProxy = it; Controller.reapply() }
                    }
                    if (Autostart.available) {
                        var auto by remember { mutableStateOf(Autostart.enabled) }
                        Toggle(L.t("اجرا با روشن شدن ویندوز", "Start with Windows"), L.t("در سینی سیستم (کنار ساعت) باز می‌شود", "Opens in the system tray"), auto) {
                            Autostart.enabled = it; auto = Autostart.enabled
                        }
                    }
                    var onStart by remember { mutableStateOf(Prefs.connectOnStart) }
                    Toggle(L.t("اتصال خودکار هنگام اجرا", "Connect when Netino starts"), null, onStart) { onStart = it; Prefs.connectOnStart = it }
                    var tray by remember { mutableStateOf(Prefs.closeToTray) }
                    Toggle(L.t("بستن پنجره = رفتن به سینی سیستم", "Closing the window keeps Netino in the tray"),
                        L.t("خاموش: بستن پنجره اتصال را قطع می‌کند و برنامه بسته می‌شود", "Off: closing the window disconnects and exits"), tray) { tray = it; Prefs.closeToTray = it }
                }
            }
        }
        Columns(
            {
        Card(L.t("مسیر مستقیم (بدون VPN)", "Direct (outside the VPN)"), NIcons.AltRoute) { DirectSettings(onMessage) }
            },
            {
        Card(L.t("اشتراک با گوشی و دستگاه‌های دیگر", "Share with your phone and other devices"), NIcons.Lan) { LanSettings() }
            },
            {
        Card(L.t("ظاهر", "Appearance"), NIcons.Settings) {
            Choice(L.t("زبان", "Language"), listOf("fa" to "فارسی", "en" to "English"), if (Prefs.fa) "fa" else "en") { Prefs.setLanguage(it == "fa") }
            Choice(L.t("پوسته", "Theme"), listOf("system" to L.t("سیستم", "System"), "light" to L.t("روشن", "Light"), "dark" to L.t("تیره", "Dark")), theme) { Prefs.setThemeMode(it) }
        }
            },
            {
        Card(L.t("اتصال هوشمند", "Smart connection"), NIcons.Bolt) {
            Toggle(L.t("تعویض خودکار سرور", "Switch servers automatically"),
                L.t("وقتی اتصال جواب ندهد، سرور بهتری پیدا می‌کند", "When the connection stops answering, finds a better server"), s.autoSwitch) { v ->
                Repository.updateSettings { it.copy(autoSwitch = v) }
            }
            Choice(L.t("فرگمنت (تکه‌تکه کردن TLS)", "TLS fragmentation"),
                listOf(FragmentMode.OFF.name to L.t("خاموش", "Off"), FragmentMode.AUTO.name to L.t("خودکار", "Auto"), FragmentMode.ALWAYS.name to L.t("همیشه", "Always")),
                s.fragmentMode.name) { v -> Repository.updateSettings { it.copy(fragmentMode = FragmentMode.valueOf(v)) }; Controller.reapply() }
            Toggle(L.t("سایت‌های ایرانی مستقیم", "Iranian sites direct"), L.t("سایت‌های .ir و ایرانی بدون VPN باز می‌شوند", ".ir and Iranian sites open without the VPN"), s.iranDirect) { v ->
                Repository.updateSettings { it.copy(iranDirect = v) }; Controller.reapply()
            }
            Toggle(L.t("مسدود کردن تبلیغات", "Block ads"), null, s.blockAds) { v -> Repository.updateSettings { it.copy(blockAds = v) }; Controller.reapply() }
        }
            },
            {
        Card(L.t("فهرست‌های ایران (geo)", "Iran lists (geo)"), NIcons.PublicOff) {
            var progress by remember { mutableStateOf<Float?>(null) }
            Text(
                if (s.geoUpdatedAt > 0) L.t("آخرین به‌روزرسانی: ", "Last updated: ") + DateFormat.getDateTimeInstance().format(Date(s.geoUpdatedAt))
                else L.t("هنوز دانلود نشده‌اند (در اولین اجرا خودکار دانلود می‌شوند). برای «سایت‌های ایرانی مستقیم» و حالت اینترنت ملی لازم‌اند.",
                    "Not downloaded yet (fetched automatically on first start). Needed for \"Iranian sites direct\" and national internet mode."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val p = progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            else OutlinedButton(onClick = {
                progress = 0f
                scope.launch {
                    GeoFiles.update { f -> progress = f }
                        .onSuccess { Repository.updateSettings { it.copy(geoUpdatedAt = System.currentTimeMillis()) }; onMessage(L.t("فهرست‌ها به‌روز شد", "Lists updated")) }
                        .onFailure { onMessage(L.t("دانلود نشد: ${it.message}", "Download failed: ${it.message}")) }
                    progress = null
                }
            }) { Text(L.t("دانلود / به‌روزرسانی", "Download / update")) }
        }
            },
            {
        Card(L.t("به‌روزرسانی و درباره", "Updates and about"), NIcons.SystemUpdate) {
            Text("Netino ${Updater.version.takeIf { it != "0" } ?: "dev"} • Xray ${XrayCore.version}" + if (Win.isAdmin) " • admin" else "",
                style = MaterialTheme.typography.bodyMedium)
            UpdateRow(onMessage)
        }
            },
        )
    }
}

/** Update status with the one button that fits it. */
@Composable
fun UpdateRow(onMessage: (String) -> Unit) {
    val st by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    when (val u = st) {
        is Updater.State.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.t("نسخهٔ ${u.release.version} آماده است", "Version ${u.release.version} is available"), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { Updater.download(u.release) } }) { Text(L.t("دانلود و نصب", "Download and install")) }
        }
        is Updater.State.Downloading -> Column {
            Text(L.t("دانلود نسخهٔ ${u.release.version}… ${(u.progress * 100).toInt()}٪", "Downloading ${u.release.version}… ${(u.progress * 100).toInt()}%"))
            LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
        }
        is Updater.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (Updater.portable) L.t("فایل جدید کنار پوشهٔ برنامه ذخیره شد: ${u.file.name}", "Saved next to the app's folder: ${u.file.name}")
                else L.t("نسخهٔ ${u.release.version} دانلود و بررسی شد", "Version ${u.release.version} downloaded and verified"),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                if (Updater.install(u.file)) { com.netino.desktop.Controller.shutdown(); kotlin.system.exitProcess(0) }
            }) { Text(if (Updater.portable) L.t("نمایش فایل", "Show file") else L.t("نصب و اجرای دوباره", "Install and restart")) }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (u) {
                Updater.State.Checking -> L.t("در حال بررسی…", "Checking…")
                Updater.State.UpToDate -> L.t("آخرین نسخه را دارید", "You have the latest version")
                is Updater.State.Failed -> L.t("بررسی نشد: ${u.message}", "Check failed: ${u.message}")
                else -> ""
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { Updater.check(force = true) } }, enabled = u != Updater.State.Checking) {
                Text(L.t("بررسی به‌روزرسانی", "Check for updates"))
            }
        }
    }
}

/** A connection type: radio, name, what it covers, and whether it needs administrator rights. */
@Composable
private fun ModeCard(on: Boolean, title: String, hint: String, admin: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(2.dp, if (on) cs.primary else cs.outline),
        color = if (on) (if (isDarkSurface()) cs.surfaceContainerHigh else cs.surfaceContainerLowest) else Color.Transparent,
        modifier = modifier.selectable(on, role = Role.RadioButton, onClick = onClick)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.RadioButton(on, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (admin) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(NIcons.ShieldPerson, null, Modifier.size(18.dp), tint = MaterialTheme.status.warnText)
                    Text(L.t("نیاز به دسترسی ادمین", "Needs administrator rights"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.status.warnText)
                }
            }
        }
    }
}

/** A settings card: 24dp corners on surfaceContainer, an icon and an 18sp title, then its rows. */
@Composable
internal fun Card(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, null, tint = cs.primary)
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
internal fun Toggle(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A label, then the options as one outlined pill split in equal parts (the chosen one filled, with a check). */
@Composable
internal fun Choice(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, cs.outline, RoundedCornerShape(20.dp))) {
            options.forEachIndexed { i, (key, label) ->
                val on = key == selected
                if (i > 0) Box(Modifier.width(1.dp).height(40.dp).background(cs.outline))
                Box(Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) cs.primaryContainer else Color.Transparent)
                    .selectable(on, role = Role.RadioButton) { onSelect(key) }, contentAlignment = Alignment.Center) {
                    Text((if (on) "✓ " else "") + label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) cs.onPrimaryContainer else cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** Cards in two columns when the window is wide enough, one otherwise. */
@Composable
internal fun Columns(vararg cards: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 720.dp) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { cards.forEach { it() } }
        else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (col in 0..1) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                cards.forEachIndexed { i, c -> if (i % 2 == col) c() }
            }
        }
    }
}
