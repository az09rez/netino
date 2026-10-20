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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.vpn.core.CleanIpScanner
import com.netino.vpn.core.NetinoApi
import com.netino.vpn.core.Warp
import com.netino.vpn.data.BackupCrypto
import com.netino.vpn.data.Repository
import com.netino.vpn.data.WarpExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The phone's tools on Windows: clean IP scanner, WARP, encrypted backup, privacy and notifications. */
@Composable
fun ToolsScreen(onMessage: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L.t("ابزارها", "Tools"), style = MaterialTheme.typography.titleLarge)
        Card(L.t("آی‌پی تمیز کلودفلر", "Clean Cloudflare IP")) { CleanIp(onMessage) }
        Card("WARP") { WarpTools(onMessage) }
        Card(L.t("پشتیبان‌گیری (با گوشی یکسان)", "Backup (same file as the phone)")) { Backup(onMessage) }
        Card(L.t("حریم خصوصی و اعلان‌ها", "Privacy and notifications")) { Privacy() }
    }
}

@Composable
private fun CleanIp(onMessage: (String) -> Unit) {
    val s by Repository.settings.collectAsState()
    val state by Controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Triple(0, 0, 0)) }
    var results by remember { mutableStateOf<List<CleanIpScanner.Hit>>(emptyList()) }
    var v6 by remember { mutableStateOf(false) }
    Text(L.t("کانفیگ‌های پشت کلودفلر (CDN) به‌جای IP معمول به سریع‌ترین IP کلودفلرِ این شبکه وصل می‌شوند.",
        "Configs behind Cloudflare (CDN) connect to the fastest Cloudflare IP on this network instead of the usual one."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if (s.cleanIp.isBlank() && s.cleanIp6.isBlank()) L.t("الان: استفاده نمی‌شود", "Now: not used")
        else L.t("الان: ", "Now: ") + listOf(s.cleanIp, s.cleanIp6).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium)
    // Through the full tunnel the scan would measure the tunnel, not this network
    val tunnelUp = Prefs.tunMode && state is ConnState.Connected
    Choice(L.t("نوع", "Type"), listOf("4" to "IPv4", "6" to "IPv6"), if (v6) "6" else "4") { v6 = it == "6"; results = emptyList() }
    if (running) {
        val (d, t, f) = progress
        Text(L.t("$d از $t • $f پیدا شد", "$d of $t • $f found"), style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth())
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !tunnelUp, onClick = {
            running = true
            scope.launch {
                results = CleanIpScanner.scan(ipv6 = v6, onProgress = { d, t, f -> progress = Triple(d, t, f) })
                running = false
                if (results.isEmpty()) onMessage(L.t("IP سالمی پیدا نشد", "No working IP found"))
            }
        }) { Text(L.t("جست‌وجو", "Scan")) }
        if (s.cleanIp.isNotBlank() || s.cleanIp6.isNotBlank()) OutlinedButton(onClick = {
            Repository.updateSettings { it.copy(cleanIp = "", cleanIp6 = "") }; Controller.reapply()
        }) { Text(L.t("استفاده نکن", "Stop using")) }
    }
    if (tunnelUp) Text(L.t("در حالت تونل کامل، اول اتصال را قطع کنید.", "In full-tunnel mode, disconnect first."),
        style = MaterialTheme.typography.bodySmall, color = Warn)
    results.forEach { h ->
        Row(Modifier.fillMaxWidth().clickable {
            scope.launch {
                val ids = withContext(Dispatchers.IO) { CleanIpScanner.cloudflareServers(Repository.servers.value) }
                Repository.updateSettings { if (':' in h.ip) it.copy(cleanIp6 = h.ip) else it.copy(cleanIp = h.ip) }
                Repository.markCloudflare(ids)
                Controller.reapply()
                onMessage(L.t("${h.ip} برای ${ids.size} سرور کلودفلری", "${h.ip} for ${ids.size} Cloudflare servers"))
                results = emptyList()
            }
        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h.ip, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${h.ms} ms", color = pingColor(h.ms), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun WarpTools(onMessage: (String) -> Unit) {
    val s by Repository.settings.collectAsState()
    val servers by Repository.servers.collectAsState()
    val search by Controller.search.collectAsState()
    val count = servers.count { it.subscriptionId == Warp.SUB }
    Text(L.t("$count سرور WARP ذخیره شده", "$count WARP servers saved"), style = MaterialTheme.typography.bodyMedium)
    var wiw by remember { mutableStateOf(Prefs.warpInWarp) }
    Choice(L.t("دکمهٔ WARP", "WARP button"), listOf("wiw" to L.t("WARP در WARP", "WARP in WARP"), "plain" to L.t("WARP ساده", "Plain WARP")),
        if (wiw) "wiw" else "plain") { wiw = it == "wiw"; Prefs.warpInWarp = wiw }
    Text(L.t("WARP در WARP سخت‌تر مسدود می‌شود؛ WARP ساده سریع‌تر است.", "WARP in WARP is harder to block; plain WARP is faster."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Choice(L.t("خروج از WARP", "Exit through WARP"), listOf(
        WarpExit.OFF.name to L.t("خاموش", "Off"), WarpExit.SITES.name to L.t("بعضی سایت‌ها", "Some sites"), WarpExit.ALL.name to L.t("همه", "All"),
    ), s.warpExit.name) { v -> Repository.updateSettings { it.copy(warpExit = WarpExit.valueOf(v)) }; Controller.reapply() }
    Text(L.t("ترافیک از داخل سرور با IP کلودفلر بیرون می‌رود (برای سایت‌هایی که IP سرورها را نمی‌پذیرند). به یک سرور WARP ذخیره‌شده نیاز دارد.",
        "Traffic leaves the server with a Cloudflare IP (for sites that refuse server IPs). Needs a saved WARP server."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var license by remember(s.warpLicense) { mutableStateOf(s.warpLicense) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(license, { license = it }, singleLine = true, label = { Text(L.t("کلید WARP+ (اختیاری)", "WARP+ key (optional)")) }, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { Repository.updateSettings { it.copy(warpLicense = license.trim()) }; onMessage(L.t("برای حساب‌های جدید استفاده می‌شود", "Used for new accounts")) }) {
            Text(L.t("ذخیره", "Save"))
        }
    }
    OutlinedButton(enabled = search == null, onClick = {
        Controller.connectWarp(fresh = true) { onMessage(if (it) L.t("WARP جدید ساخته و وصل شد", "New WARP made and connected") else L.t("WARP جدید ساخته نشد؛ گزارش را ببینید", "Couldn't make a new WARP; see the log")) }
    }) { Text(L.t("ساختن سرورهای WARP جدید", "Make new WARP servers")) }
}

@Composable
private fun Backup(onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(0) }   // 1 export, 2 import
    Text(L.t("همهٔ سرورها، اشتراک‌ها، گروه‌ها و تنظیمات در یک فایل رمزدار (.nbk). همان فایل پشتیبان نتینوی اندروید است: از گوشی به کامپیوتر و برعکس.",
        "All servers, subscriptions, groups and settings in one password-protected file (.nbk), the same as Netino for Android's backup: phone to PC and back."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { ask = 1 }) { Text(L.t("ساختن پشتیبان", "Back up")) }
        OutlinedButton(onClick = { ask = 2 }) { Text(L.t("بازگردانی", "Restore")) }
    }
    if (ask != 0) PasswordDialog(confirm = ask == 1, onDismiss = { ask = 0 }) { pw ->
        val export = ask == 1
        ask = 0
        val d = FileDialog(null as Frame?, if (export) L.t("ذخیرهٔ پشتیبان", "Save backup") else L.t("فایل پشتیبان", "Backup file"),
            if (export) FileDialog.SAVE else FileDialog.LOAD)
        if (export) d.file = "netino-backup.nbk" else d.setFilenameFilter { _, n -> n.endsWith(".nbk", true) }
        d.isVisible = true
        val f = d.files.firstOrNull() ?: return@PasswordDialog
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (export) { File(f.path.let { if (it.endsWith(".nbk", true)) it else "$it.nbk" }).writeBytes(BackupCrypto.encrypt(Repository.exportBackup(), pw)); -1 }
                    else Repository.importBackup(BackupCrypto.decrypt(f.readBytes(), pw))
                }
            }
            onMessage(when {
                r.isFailure && export -> L.t("ذخیره نشد: ${r.exceptionOrNull()?.message}", "Couldn't save: ${r.exceptionOrNull()?.message}")
                r.isFailure -> L.t("رمز اشتباه است یا فایل خراب است", "Wrong password or a damaged file")
                export -> L.t("پشتیبان ذخیره شد", "Backup saved")
                else -> L.t("${r.getOrNull()} سرور بازگردانی شد", "${r.getOrNull()} servers restored")
            })
        }
    }
}

@Composable
private fun PasswordDialog(confirm: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val ok = pw.length >= 4 && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.t("رمز فایل پشتیبان", "Backup password")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.t("بدون این رمز فایل باز نمی‌شود؛ آن را فراموش نکنید.", "The file can't be opened without it: don't forget it."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(L.t("رمز", "Password")) }, visualTransformation = PasswordVisualTransformation())
                if (confirm) OutlinedTextField(pw2, { pw2 = it }, singleLine = true, label = { Text(L.t("تکرار رمز", "Password again")) }, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onOk(pw) }) { Text(L.t("ادامه", "Continue")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("انصراف", "Cancel")) } },
    )
}

@Composable
private fun Privacy() {
    val s by Repository.settings.collectAsState()
    val hasApi = remember { NetinoApi.base != null }
    Toggle(L.t("کمک به بهتر شدن لیست رایگان", "Help improve the free list"),
        if (hasApi) L.t("ناشناس و روزی یک بار: کدام کانفیگ‌های رایگان روی اپراتور شما کار کردند (بدون IP و اطلاعات شخصی).",
            "Anonymously, once a day: which free configs worked on your ISP (no IP or personal data).")
        else L.t("سرویس نتینو هنوز راه‌اندازی نشده است.", "Netino's service isn't set up yet."),
        s.shareResults == true) { v -> Repository.updateSettings { it.copy(shareResults = v) } }
    var notes by remember { mutableStateOf(Prefs.notifications) }
    Toggle(L.t("اعلان‌های ویندوز", "Windows notifications"),
        L.t("قطع شدن اتصال، عوض شدن سرور، تمام شدن اشتراک، نسخهٔ جدید", "Connection dropped, server switched, subscription running out, new version"),
        notes) { notes = it; Prefs.notifications = it }
    var hotkey by remember { mutableStateOf(Prefs.hotkey) }
    Toggle(L.t("کلید میانبر Ctrl+Alt+N", "Ctrl+Alt+N shortcut"),
        L.t("وصل و قطع از هر جای ویندوز (بعد از اجرای دوبارهٔ Netino اعمال می‌شود)", "Connect / disconnect from anywhere (applies after Netino restarts)"),
        hotkey) { hotkey = it; Prefs.hotkey = it }
}
