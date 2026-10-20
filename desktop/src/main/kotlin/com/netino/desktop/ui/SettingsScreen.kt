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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L.t("تنظیمات", "Settings"), style = MaterialTheme.typography.titleLarge)

        Card(L.t("ظاهر", "Appearance")) {
            Choice(L.t("زبان", "Language"), listOf("fa" to "فارسی", "en" to "English"), if (Prefs.fa) "fa" else "en") { Prefs.setLanguage(it == "fa") }
            Choice(L.t("پوسته", "Theme"), listOf("system" to L.t("سیستم", "System"), "light" to L.t("روشن", "Light"), "dark" to L.t("تیره", "Dark")), theme) { Prefs.setThemeMode(it) }
        }

        Card(L.t("ویندوز", "Windows")) {
            var tun by remember { mutableStateOf(Prefs.tunMode) }
            Choice(L.t("نوع اتصال", "Connection mode"),
                listOf("proxy" to L.t("پراکسی سیستم", "System proxy"), "tun" to L.t("تونل کامل (TUN)", "Full tunnel (TUN)")),
                if (tun) "tun" else "proxy") { tun = it == "tun"; Prefs.tunMode = tun; Controller.reapply() }
            Text(
                if (tun) L.t("همهٔ برنامه‌ها (بازی‌ها، تلگرام دسکتاپ، خط فرمان و …) از تونل رد می‌شوند. به دسترسی ادمین نیاز دارد.",
                    "Every program (games, desktop apps, the command line, …) goes through the tunnel. Needs administrator rights.")
                else L.t("مرورگرها و برنامه‌هایی که پراکسی ویندوز را می‌خوانند از Netino رد می‌شوند. دسترسی ادمین لازم نیست.",
                    "Browsers and programs that follow the Windows proxy go through Netino. No administrator rights needed."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tun && !Win.isAdmin) {
                Surface(shape = MaterialTheme.shapes.medium, color = Warn.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(L.t("Netino بدون دسترسی ادمین اجرا شده است.", "Netino is running without administrator rights."),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            if (!Controller.restartAsAdmin()) onMessage(L.t("اجرا با دسترسی ادمین انجام نشد", "Couldn't restart as administrator"))
                        }) { Text(L.t("اجرای دوباره به‌عنوان ادمین", "Restart as administrator")) }
                    }
                }
            }
            if (!tun) {
                var proxy by remember { mutableStateOf(Prefs.systemProxy) }
                Toggle(L.t("تنظیم پراکسی ویندوز", "Set the Windows proxy"),
                    L.t("خاموش: فقط برنامه‌هایی که دستی روی پراکسی ${s.lanHttpPort} (HTTP) یا ${s.lanSocksPort} (SOCKS5) تنظیم شده‌اند.",
                        "Off: only programs set by hand to ${s.lanHttpPort} (HTTP) or ${s.lanSocksPort} (SOCKS5)."),
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

        Card(L.t("اتصال هوشمند", "Smart connection")) {
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

        Card(L.t("فهرست‌های ایران (geo)", "Iran lists (geo)")) {
            var progress by remember { mutableStateOf<Float?>(null) }
            Text(
                if (s.geoUpdatedAt > 0) L.t("آخرین به‌روزرسانی: ", "Last updated: ") + DateFormat.getDateTimeInstance().format(Date(s.geoUpdatedAt))
                else L.t("فهرست‌های همراه Xray استفاده می‌شوند. فهرست‌های مخصوص ایران (Chocolate4U) دقیق‌ترند.",
                    "Using the lists that come with Xray. The Iran-specific lists (Chocolate4U) are more accurate."),
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

        Card(L.t("به‌روزرسانی و درباره", "Updates and about")) {
            Text("Netino ${Updater.version.takeIf { it != "0" } ?: "dev"} • Xray ${XrayCore.version}" + if (Win.isAdmin) " • admin" else "",
                style = MaterialTheme.typography.bodyMedium)
            UpdateRow(onMessage)
        }
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

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) -> FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) }) }
        }
    }
}
