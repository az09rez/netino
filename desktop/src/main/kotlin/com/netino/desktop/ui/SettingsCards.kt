package com.netino.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.desktop.Lan
import com.netino.desktop.Prefs
import com.netino.desktop.Win
import com.netino.vpn.core.XrayConfigBuilder
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Repository
import com.netino.vpn.data.SplitMode
import java.io.File
import java.security.SecureRandom

/** Programs, sites and IPs that skip the VPN (or, for sites, the only ones that use it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectSettings(onMessage: (String) -> Unit) {
    // ---- programs ----
    var apps by remember { mutableStateOf(Prefs.directApps) }
    fun save(list: List<String>) { apps = list; Prefs.directApps = list; Controller.reapply() }
    Text(L.t("برنامه‌هایی که مستقیم وصل می‌شوند (بانک‌ها، بازی‌های داخلی، …)", "Programs that connect directly (banks, local games, …)"),
        style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        apps.forEach { a ->
            InputChip(selected = false, onClick = { save(apps - a) }, label = { Text(a) },
                trailingIcon = { Icon(NIcons.Close, L.t("حذف", "Remove"), Modifier.size(16.dp)) })
        }
    }
    // The name box and "add" share a line; "from running programs" wraps below when the column is narrow
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var typed by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(typed, { typed = it }, singleLine = true, placeholder = { Text("game.exe") }, modifier = Modifier.widthIn(min = 140.dp, max = 220.dp))
            OutlinedButton(onClick = {
                val name = File(typed.trim()).name
                if (name.isNotEmpty()) save(apps + (if (name.contains('.')) name else "$name.exe"))
                typed = ""
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(L.t("افزودن", "Add"), maxLines = 1) }
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(NIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(L.t("از برنامه‌های در حال اجرا", "From running programs"), maxLines = 1)
            }
            DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 400.dp)) {
                runningPrograms().filter { it !in apps }.forEach { p ->
                    DropdownMenuItem(text = { Text(p) }, onClick = { menu = false; save(apps + p) })
                }
            }
        }
    }
    Text(L.t("در هر دو حالت (پراکسی و تونل کامل) کار می‌کند.", "Works in both modes (proxy and full tunnel)."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Spacer(Modifier.size(8.dp))
    // ---- sites / IPs (the same rules as the phone's split tunnelling) ----
    val s by Repository.settings.collectAsState()
    val split = s.split
    Choice(L.t("سایت‌ها و IPها", "Sites and IPs"), listOf(
        SplitMode.OFF.name to L.t("خاموش", "Off"),
        SplitMode.BYPASS.name to L.t("این‌ها مستقیم", "These direct"),
        SplitMode.ONLY.name to L.t("فقط این‌ها با VPN", "Only these via VPN"),
    ), split.routeMode.name) { m -> Repository.updateSettings { it.copy(split = it.split.copy(routeMode = SplitMode.valueOf(m))) }; Controller.reapply() }
    if (split.routeMode != SplitMode.OFF) {
        var domains by remember(split.domains) { mutableStateOf(split.domains.joinToString("\n")) }
        var ips by remember(split.ips) { mutableStateOf(split.ips.joinToString("\n")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(domains, { domains = it }, label = { Text(L.t("دامنه‌ها (هر خط یکی)", "Domains (one per line)")) },
                placeholder = { Text("example.com\ndomain:ir\ngeosite:category-ir") }, modifier = Modifier.weight(1f).heightIn(min = 120.dp))
            OutlinedTextField(ips, { ips = it }, label = { Text(L.t("IPها (هر خط یکی)", "IPs (one per line)")) },
                placeholder = { Text("1.2.3.4\n10.0.0.0/8\ngeoip:ir") }, modifier = Modifier.weight(1f).heightIn(min = 120.dp))
        }
        TextButton(onClick = {
            val geo = XrayCore.hasGeoFiles
            val d = domains.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val i = ips.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val goodD = d.filter { XrayConfigBuilder.normalizeDomain(it, geo) != null }
            val goodI = i.filter { XrayConfigBuilder.normalizeIp(it, geo) != null }
            Repository.updateSettings { it.copy(split = it.split.copy(domains = goodD, ips = goodI)) }
            Controller.reapply()
            val bad = (d.size - goodD.size) + (i.size - goodI.size)
            onMessage(if (bad == 0) L.t("ذخیره شد", "Saved") else L.t("ذخیره شد؛ $bad مورد نامعتبر کنار گذاشته شد", "Saved; $bad invalid entries left out"))
        }) { Text(L.t("ذخیره", "Save")) }
    }
}

/** Programs running now (their exe names), Windows' own left out. */
private fun runningPrograms(): List<String> = runCatching {
    val windows = (System.getenv("SystemRoot") ?: "C:\\Windows").lowercase()
    ProcessHandle.allProcesses().toList().mapNotNull { it.info().command().orElse(null) }
        .filter { !it.lowercase().startsWith(windows) }
        .map { File(it).name }
        .filter { it.endsWith(".exe", true) && !it.equals("xray.exe", true) }
        .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}.getOrDefault(emptyList())

/** Sharing this computer's connection with phones and other computers on the same network. */
@Composable
fun LanSettings() {
    var on by remember { mutableStateOf(Prefs.shareLan) }
    Toggle(L.t("اشتراک اتصال در شبکهٔ محلی", "Share the connection on the local network"),
        L.t("گوشی و دستگاه‌های دیگرِ همین وای‌فای از اتصال این کامپیوتر استفاده می‌کنند. فقط از شبکهٔ محلی قابل دسترسی است.",
            "Phones and other devices on the same Wi-Fi use this computer's connection. Reachable from the local network only."),
        on) { on = it; Prefs.shareLan = it; Controller.reapply() }
    if (!on) return
    val s by Repository.settings.collectAsState()
    val auth = s.lanAuth && s.lanUser.isNotBlank()
    Toggle(L.t("رمز عبور برای اتصال", "Require a password"),
        L.t("بدون رمز، هر دستگاهی در همین شبکه می‌تواند وصل شود. با رمز، تنظیم پراکسی وای‌فای گوشی کار نمی‌کند و باید از QR استفاده شود.",
            "Without one, any device on this network can connect. With one, the phone's Wi-Fi proxy setting won't work: use the QR code."),
        auth) { v ->
        Repository.updateSettings {
            if (!v) it.copy(lanAuth = false)
            else it.copy(lanAuth = true, lanUser = it.lanUser.ifBlank { token(8) }, lanPass = it.lanPass.ifBlank { token(12) })
        }
        Controller.reapply()
    }
    val addresses = remember { Lan.addresses() }
    if (addresses.isEmpty()) {
        Text(L.t("این کامپیوتر به شبکهٔ محلی وصل نیست.", "This computer isn't on a local network."), color = MaterialTheme.colorScheme.error)
        return
    }
    var qr by remember { mutableStateOf<String?>(null) }
    addresses.forEach { ip ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HTTP  $ip:${Controller.LAN_HTTP}   •   SOCKS5  $ip:${Controller.LAN_SOCKS}", style = MaterialTheme.typography.bodyMedium)
                if (auth) Text(L.t("نام کاربری: ${s.lanUser}   رمز: ${s.lanPass}", "User: ${s.lanUser}   Password: ${s.lanPass}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { qr = ip }) { Icon(NIcons.QrCode2, null); Spacer(Modifier.width(6.dp)); Text(L.t("QR برای گوشی", "QR for the phone")) }
        }
    }
    Text(
        L.t("روش ۱ (کل گوشی): QR را در Netino یا v2rayNG اسکن کنید و وصل شوید.\nروش ۲ (فقط مرورگر و برنامه‌هایی که پراکسی را می‌خوانند): تنظیمات وای‌فای گوشی ← پراکسی ← دستی ← آدرس بالا و پورت ${Controller.LAN_HTTP}." +
            if (Win.isAdmin) "" else "\nاگر ویندوز برای Xray اجازهٔ دسترسی شبکه خواست، «Allow» را بزنید.",
            "Way 1 (the whole phone): scan the QR code in Netino or v2rayNG and connect.\nWay 2 (browser and apps that follow the proxy): phone's Wi-Fi settings → Proxy → Manual → the address above, port ${Controller.LAN_HTTP}." +
            if (Win.isAdmin) "" else "\nIf Windows asks whether Xray may use the network, press \"Allow\"."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    qr?.let { ip ->
        QrDialog(L.t("اتصال گوشی از راه این کامپیوتر", "Connect the phone through this computer"), Lan.socksLink(ip),
            L.t("در Netino یا v2rayNG اسکن کنید. Netino روی کامپیوتر باید وصل بماند.", "Scan it in Netino or v2rayNG. Netino on this computer has to stay connected.")) { qr = null }
    }
}

private fun token(n: Int): String {
    val chars = "abcdefghijkmnpqrstuvwxyz23456789"
    val r = SecureRandom()
    return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
}
