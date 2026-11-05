package com.netino.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
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

/** A settings category: its place in the list, and the words that find it (both languages) from search everywhere. */
internal class SettingsCat(val id: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: () -> String, val desc: () -> String, val keys: String)

internal val SETTINGS_CATS = listOf(
    SettingsCat("look", NIcons.Desktop, { L.t("ظاهر و دسترس‌پذیری", "Appearance and accessibility") }, { L.t("زبان، پوسته، اندازهٔ متن و حرکت", "Language, theme, text size and motion") },
        both("ظاهر زبان فارسی انگلیسی پوسته تیره روشن اندازه متن بزرگ خوانایی حرکت انیمیشن دسترس‌پذیری", "appearance language Persian English theme dark light text size larger reading motion animation accessibility")),
    SettingsCat("conn", NIcons.Lan, { L.t("اتصال", "Connection") }, { L.t("نوع اتصال، رفتار در ویندوز و اتصال هوشمند", "Connection type, how it behaves on Windows, smart connection") },
        both("اتصال نوع پراکسی سیستم تونل کامل ادمین قطع امن اجرا با ویندوز اتصال خودکار سینی اتصال دوباره تعویض خودکار سرور سایت‌های ایرانی تبلیغات",
            "connection type system proxy full tunnel TUN administrator kill switch start with windows connect on start tray reconnect switch servers automatically Iranian sites ads")),
    SettingsCat("anti", NIcons.ShieldPerson, { L.t("ضد فیلترینگ و WARP", "Anti-filtering and WARP") }, { L.t("وقتی اتصال سخت است اینجا را ببینید", "Look here when connecting is hard") },
        both("ضد فیلترینگ فرگمنت تکه آی‌پی تمیز کلودفلر WARP پلاس کلید ساختن", "anti-filtering fragment TLS clean IP Cloudflare WARP plus key create")),
    SettingsCat("route", NIcons.AltRoute, { L.t("مسیر مستقیم", "Direct route") }, { L.t("برنامه‌ها و سایت‌هایی که بدون VPN باز شوند", "Programs and sites that skip the VPN") },
        both("مسیر مستقیم برنامه‌ها سایت‌ها دامنه آی‌پی بدون VPN فهرست ایران geo", "direct route programs apps sites domains IPs outside the VPN split Iran lists geo")),
    SettingsCat("share", NIcons.Share, { L.t("اشتراک با دستگاه‌ها", "Share with devices") }, { L.t("گوشی و لپ‌تاپ دیگر را هم وصل کنید", "Connect your phone and other devices too") },
        both("اشتراک گوشی دستگاه شبکه محلی وای‌فای QR رمز HTTP SOCKS ${Controller.LAN_HTTP} ${Controller.LAN_SOCKS}", "share phone devices local network Wi-Fi QR password HTTP SOCKS ${Controller.LAN_HTTP} ${Controller.LAN_SOCKS}")),
    SettingsCat("privacy", NIcons.Lock, { L.t("حریم خصوصی و اعلان‌ها", "Privacy and notifications") }, { L.t("چه چیزی فرستاده می‌شود و چه وقت خبر بدهیم", "What is sent and when we tell you") },
        both("حریم خصوصی لیست رایگان کمک اعلان ویندوز کلید میانبر", "privacy free list help notifications windows shortcut hotkey")),
    SettingsCat("backup", NIcons.Folder, { L.t("پشتیبان‌گیری", "Backup") }, { L.t("سرورها و تنظیمات در یک فایل رمزدار", "Servers and settings in one encrypted file") },
        both("پشتیبان بازگردانی فایل رمز nbk", "backup restore file password nbk")),
    SettingsCat("debug", NIcons.ReceiptLong, { L.t("عیب‌یابی و گزارش", "Troubleshooting and log") }, { L.t("وقتی چیزی کار نمی‌کند", "When something doesn't work") },
        both("عیب‌یابی گزارش لاگ خطا کپی ارسال سازنده", "troubleshooting log errors copy report send developer")),
    SettingsCat("about", NIcons.SystemUpdate, { L.t("درباره و به‌روزرسانی", "About and updates") }, { L.t("نسخه و به‌روزرسانی", "Version and updates") },
        both("به‌روزرسانی درباره نسخه Xray", "updates about version Xray")),
)

@Composable
fun SettingsScreen(onMessage: (String) -> Unit) {
    val cat by Ui.settingsCat.collectAsState()
    var query by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageTitle(L.t("تنظیمات", "Settings"), Modifier.weight(1f))
                // Finds a setting by any word on it (titles, descriptions, port numbers), in either language and spelling
                SearchField(query, { query = it }, L.t("جست‌وجو در تنظیمات", "Search settings"), Modifier.widthIn(max = 300.dp).weight(1f, fill = false))
            }
            if (wide) Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(248.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SETTINGS_CATS.forEach { c -> CategoryItem(c, on = query.isBlank() && c.id == cat, wide = true) { query = ""; Ui.settingsCat.value = c.id } }
                }
                androidx.compose.material3.VerticalDivider(color = cs.outlineVariant)
                CategoryContent(cat, query, onMessage, Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SETTINGS_CATS.forEach { c -> CategoryItem(c, on = query.isBlank() && c.id == cat, wide = false) { query = ""; Ui.settingsCat.value = c.id } }
                }
                CategoryContent(cat, query, onMessage, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** A category in the list (wide window) or as a chip in a row that scrolls (narrow). */
@Composable
private fun CategoryItem(c: SettingsCat, on: Boolean, wide: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (on) cs.onPrimaryContainer else cs.onSurface
    if (wide) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) cs.primaryContainer else Color.Transparent)
        .selectable(on, role = Role.Tab, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(c.icon, null, Modifier.size(22.dp), tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant)
        Text(c.title(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else Surface(shape = RoundedCornerShape(18.dp), color = if (on) cs.primaryContainer else Color.Transparent,
        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, cs.outline),
        modifier = Modifier.selectable(on, role = Role.Tab, onClick = onClick)) {
        Text(c.title(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = fg,
            maxLines = 1, modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** The chosen category's cards; while searching, every card that matches, from all categories. */
@Composable
private fun CategoryContent(cat: String, query: String, onMessage: (String) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    // The log fills the pane (it scrolls by itself)
    if (query.isBlank() && cat == "debug") { Box(modifier) { LogScreen(onMessage) }; return }
    val cards = settingCards(onMessage)
    val shown = if (query.isBlank()) cards.filter { it.cat == cat } else cards.filter { matches(query, it.keys) }
    val c = SETTINGS_CATS.first { it.id == cat }
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (query.isBlank()) c.title() else L.t("نتیجه‌های جست‌وجو", "Search results"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(if (query.isBlank()) c.desc() else L.t("همین‌جا تغییرشان دهید", "Change them right here"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (shown.isEmpty()) Text(L.t("تنظیمی پیدا نشد", "No setting found"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        shown.forEach { Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) { it.content() } }
    }
}

private class SettingCard(val cat: String, val keys: String, val content: @Composable () -> Unit)

private fun settingCards(onMessage: (String) -> Unit): List<SettingCard> = listOf(
    SettingCard("look", both("ظاهر زبان فارسی انگلیسی پوسته تیره روشن اندازه متن بزرگ خوانایی حرکت انیمیشن", "appearance language Persian English theme dark light text size larger reading motion animation")) {
        Card(L.t("ظاهر و دسترس‌پذیری", "Appearance and accessibility"), NIcons.Desktop) {
            val theme by Prefs.theme.collectAsState()
            val scale by Prefs.textScaleFlow.collectAsState()
            val reduce by Prefs.reduceMotionFlow.collectAsState()
            Choice(L.t("زبان", "Language"), listOf("fa" to "فارسی", "en" to "English"), if (Prefs.fa) "fa" else "en") { Prefs.setLanguage(it == "fa") }
            Choice(L.t("پوسته", "Theme"), listOf("system" to L.t("سیستم", "System"), "light" to L.t("روشن", "Light"), "dark" to L.t("تیره", "Dark")), theme) { Prefs.setThemeMode(it) }
            Choice(L.t("اندازهٔ متن", "Text size"), listOf("1.0" to L.t("عادی", "Normal"), "1.15" to L.t("بزرگ", "Large"), "1.3" to L.t("خیلی بزرگ", "Extra large")),
                when { scale >= 1.25f -> "1.3"; scale >= 1.1f -> "1.15"; else -> "1.0" }) { Prefs.setTextScale(it.toFloat()) }
            Toggle(L.t("کاهش حرکت", "Reduce motion"), L.t("هاله‌ها و انیمیشن‌های همیشگی خاموش و تغییرها فوری می‌شوند", "Halos and looping animations stop, and changes are instant"),
                reduce) { Prefs.setReduceMotion(it) }
        }
    },
    SettingCard("conn", both("ویندوز نوع اتصال پراکسی سیستم تونل کامل ادمین قطع امن kill switch اجرا با روشن شدن ویندوز اتصال خودکار سینی اتصال دوباره", "Windows connection type system proxy full tunnel TUN administrator kill switch start with windows connect on start tray reconnect")) {
        WindowsCard(onMessage)
    },
    SettingCard("conn", both("اتصال هوشمند تعویض خودکار سرور سایت‌های ایرانی تبلیغات واکنش", "smart connection switch servers automatically Iranian sites ads failover")) {
        val s by Repository.settings.collectAsState()
        Card(L.t("اتصال هوشمند", "Smart connection"), NIcons.Bolt) {
            Toggle(L.t("تعویض خودکار سرور", "Switch servers automatically"),
                L.t("کیفیت اتصال (تأخیر، لرزش، بستهٔ گم‌شده) مدام سنجیده می‌شود؛ اگر افت کند به سرور بهتری که از قبل سنجیده شده می‌رود",
                    "The connection's quality (delay, jitter, loss) is measured all the time; if it drops, Netino moves to a better server measured ahead of time"), s.autoSwitch) { v ->
                Repository.updateSettings { it.copy(autoSwitch = v) }
            }
            if (s.autoSwitch) {
                var mode by remember { mutableStateOf(Prefs.failoverMode) }
                Choice(L.t("واکنش تعویض خودکار", "How readily it switches"), listOf(
                    "calm" to L.t("محافظه‌کار", "Calm"), "balanced" to L.t("متعادل", "Balanced"), "quick" to L.t("سریع", "Quick"),
                ), mode) { mode = it; Controller.setFailoverMode(it) }
            }
            Toggle(L.t("سایت‌های ایرانی مستقیم", "Iranian sites direct"), L.t("سایت‌های .ir و ایرانی بدون VPN باز می‌شوند", ".ir and Iranian sites open without the VPN"), s.iranDirect) { v ->
                Repository.updateSettings { it.copy(iranDirect = v) }; Controller.reapply()
            }
            Toggle(L.t("مسدود کردن تبلیغات", "Block ads"), null, s.blockAds) { v -> Repository.updateSettings { it.copy(blockAds = v) }; Controller.reapply() }
        }
    },
    SettingCard("anti", both("فرگمنت تکه‌تکه TLS فیلترینگ", "fragment fragmentation TLS filtering")) {
        val s by Repository.settings.collectAsState()
        Card(L.t("فرگمنت (تکه‌تکه کردن TLS)", "TLS fragmentation"), NIcons.ShieldPerson) {
            Text(L.t("بستهٔ اول تکه‌تکه فرستاده می‌شود تا فیلتر آن را نشناسد. «خودکار» فقط روی شبکه‌هایی که لازم دارند روشن می‌شود.",
                "The first packet goes out in pieces so the filter can't recognize it. \"Auto\" turns it on only on networks that need it."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice("", listOf(FragmentMode.OFF.name to L.t("خاموش", "Off"), FragmentMode.AUTO.name to L.t("خودکار", "Auto"), FragmentMode.ALWAYS.name to L.t("همیشه", "Always")),
                s.fragmentMode.name) { v -> Repository.updateSettings { it.copy(fragmentMode = FragmentMode.valueOf(v)) }; Controller.reapply() }
        }
    },
    SettingCard("anti", both("آی‌پی تمیز کلودفلر جست‌وجو اسکن", "clean IP Cloudflare scan")) { Card(L.t("آی‌پی تمیز کلودفلر", "Clean Cloudflare IP"), NIcons.Cloud) { CleanIp(onMessage) } },
    SettingCard("anti", both("WARP پلاس کلید ساختن خروج", "WARP plus key create exit")) { Card("WARP", NIcons.Bolt) { WarpTools(onMessage) } },
    SettingCard("route", both("مسیر مستقیم برنامه‌ها سایت‌ها دامنه آی‌پی بدون VPN", "direct programs apps sites domains IPs outside the VPN split")) {
        Card(L.t("مسیر مستقیم (بدون VPN)", "Direct (outside the VPN)"), NIcons.AltRoute) { DirectSettings(onMessage) }
    },
    SettingCard("route", both("فهرست ایران geo دانلود به‌روزرسانی", "Iran lists geo download update")) { GeoCard(onMessage) },
    SettingCard("share", both("اشتراک گوشی دستگاه شبکه محلی وای‌فای QR رمز HTTP SOCKS ${Controller.LAN_HTTP} ${Controller.LAN_SOCKS}", "share phone devices local network Wi-Fi QR password HTTP SOCKS ${Controller.LAN_HTTP} ${Controller.LAN_SOCKS}")) {
        Card(L.t("اشتراک با گوشی و دستگاه‌های دیگر", "Share with your phone and other devices"), NIcons.Lan) { LanSettings() }
    },
    SettingCard("privacy", both("حریم خصوصی لیست رایگان کمک اعلان ویندوز کلید میانبر", "privacy free list help notifications windows shortcut hotkey")) {
        Card(L.t("حریم خصوصی و اعلان‌ها", "Privacy and notifications"), NIcons.Notifications) { Privacy() }
    },
    SettingCard("backup", both("پشتیبان بازگردانی فایل رمز nbk", "backup restore file password nbk")) { Card(L.t("پشتیبان‌گیری", "Backup"), NIcons.Folder) { Backup(onMessage) } },
    SettingCard("debug", both("عیب‌یابی گزارش لاگ خطا کپی ارسال سازنده", "troubleshooting log errors copy report send developer")) {
        Card(L.t("عیب‌یابی و گزارش", "Troubleshooting and log"), NIcons.ReceiptLong) {
            Text(L.t("رویدادهای اتصال، کپی گزارش شبکه و ارسال برای سازنده.", "Connection events, copy the network report, send it to the developer."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { Ui.openSettings("debug") }) { Text(L.t("باز کردن گزارش", "Open the log")) }
        }
    },
    SettingCard("about", both("به‌روزرسانی درباره نسخه Xray", "updates about version Xray")) {
        Card(L.t("به‌روزرسانی و درباره", "Updates and about"), NIcons.SystemUpdate) {
            Text("Netino ${Updater.version.takeIf { it != "0" } ?: "dev"} • Xray ${XrayCore.version}" + if (Win.isAdmin) " • admin" else "",
                style = MaterialTheme.typography.bodyMedium)
            UpdateRow(onMessage)
        }
    },
)

/** Connection type (proxy or full tunnel) and how Netino behaves on Windows. */
@Composable
private fun WindowsCard(onMessage: (String) -> Unit) {
    Card(L.t("ویندوز", "Windows"), NIcons.Desktop) {
            val tun by Prefs.tunFlow.collectAsState()
            Text(L.t("نوع اتصال", "Connection type"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val pair = maxWidth >= 480.dp
                val cards: @Composable (Modifier) -> Unit = { m ->
                    ModeCard(!tun, L.t("پراکسی سیستم", "System proxy"), L.t("مرورگرها و بیشتر برنامه‌ها. ساده و بدون دسترسی ادمین.",
                        "Browsers and most programs. Simple, no administrator rights."), admin = false, m) { Controller.setTunnel(false) }
                    ModeCard(tun, L.t("تونل کامل (TUN)", "Full tunnel (TUN)"), L.t("همهٔ برنامه‌ها، حتی بازی‌ها و تلگرام دسکتاپ.",
                        "Every program, games and desktop apps too."), admin = true, m) { Controller.setTunnel(true) }
                }
                if (pair) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.fillMaxWidth()) }
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
                    var reconnect by remember { mutableStateOf(Prefs.autoReconnect) }
                    Toggle(L.t("اتصال دوباره بعد از قطع ناگهانی", "Reconnect after a drop"),
                        L.t("تا ۵ بار با فاصلهٔ بیشتر، اول همان سرورها و بعد سریع‌ترین", "Up to 5 tries with growing waits: the same servers first, then the fastest"),
                        reconnect) { reconnect = it; Prefs.autoReconnect = it }
                    var onStart by remember { mutableStateOf(Prefs.connectOnStart) }
                    Toggle(L.t("اتصال خودکار هنگام اجرا", "Connect when Netino starts"), null, onStart) { onStart = it; Prefs.connectOnStart = it }
                    var tray by remember { mutableStateOf(Prefs.closeToTray) }
                    Toggle(L.t("بستن پنجره = رفتن به سینی سیستم", "Closing the window keeps Netino in the tray"),
                        L.t("خاموش: بستن پنجره اتصال را قطع می‌کند و برنامه بسته می‌شود", "Off: closing the window disconnects and exits"), tray) { tray = it; Prefs.closeToTray = it }
                }
            }
    }
}

/** The Iran lists (geo files): when they were fetched, and fetching them again. */
@Composable
private fun GeoCard(onMessage: (String) -> Unit) {
    val s by Repository.settings.collectAsState()
    val scope = rememberCoroutineScope()
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

/** Folds the spellings people mix (Arabic ي ك, Persian or Latin digits, case) so a search finds either. */
internal fun foldSearch(s: String): String = buildString(s.length) {
    s.lowercase().forEach { c ->
        append(when (c) {
            'ي', 'ى' -> 'ی'; 'ك' -> 'ک'; 'ة' -> 'ه'; '\u200c' -> ' '
            in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠')
            else -> c
        })
    }
}

/** A card's words in both languages: a search finds it whichever language the user types in. */
internal fun both(fa: String, en: String) = "$fa $en"

/** Whether every word of [query] appears in [keywords] (an empty query matches everything). */
internal fun matches(query: String, keywords: String): Boolean {
    if (query.isBlank()) return true
    val k = foldSearch(keywords)
    return foldSearch(query).split(' ').filter { it.isNotBlank() }.all { it in k }
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
