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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(L.t("ابزارها", "Tools"))
        Columns(
            { Card(L.t("آی‌پی تمیز کلودفلر", "Clean Cloudflare IP"), NIcons.Cloud) { CleanIp(onMessage) } },
            { Card("WARP", NIcons.Bolt) { WarpTools(onMessage) } },
            { Card(L.t("پشتیبان‌گیری", "Backup"), NIcons.Refresh) { Backup(onMessage) } },
            { Card(L.t("حریم خصوصی و اعلان‌ها", "Privacy and notifications"), NIcons.Notifications) { Privacy() } },
        )
    }
}

@Composable
internal fun CleanIp(onMessage: (String) -> Unit) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.weight(1f).height(6.dp),
                trackColor = MaterialTheme.colorScheme.primaryContainer, gapSize = 0.dp, drawStopIndicator = {})
            Text(localDigits("$d/$t") + L.t(" • ${localDigits("$f")} پیدا شد", " • $f found"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    if (results.isNotEmpty()) Surface(shape = RoundedCornerShape(14.dp),
        color = if (isDarkSurface()) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            results.forEachIndexed { i, h ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        val ids = withContext(Dispatchers.IO) { CleanIpScanner.cloudflareServers(Repository.servers.value) }
                        Repository.updateSettings { if (':' in h.ip) it.copy(cleanIp6 = h.ip) else it.copy(cleanIp = h.ip) }
                        Repository.markCloudflare(ids)
                        Controller.reapply()
                        onMessage(L.t("${h.ip} برای ${ids.size} سرور کلودفلری", "${h.ip} for ${ids.size} Cloudflare servers"))
                        results = emptyList()
                    }
                }.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(h.ip, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    LatencyPill(h.ms, com.netino.vpn.data.PingKind.TCP)
                    androidx.compose.material3.IconButton(onClick = { Qr.copy(h.ip) }, modifier = Modifier.size(36.dp)) {
                        androidx.compose.material3.Icon(NIcons.ContentCopy, L.t("کپی ${h.ip}", "Copy ${h.ip}"), Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (results.isNotEmpty()) Text(L.t("روی یک IP بزنید تا برای سرورهای کلودفلری استفاده شود.", "Click an IP to use it for the Cloudflare servers."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun WarpTools(onMessage: (String) -> Unit) {
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
    Button(enabled = search == null, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
        Controller.connectWarp(fresh = true) { onMessage(if (it) L.t("WARP جدید ساخته و وصل شد", "New WARP made and connected") else L.t("WARP جدید ساخته نشد؛ گزارش را ببینید", "Couldn't make a new WARP; see the log")) }
    }) { androidx.compose.material3.Icon(NIcons.Add, null); Spacer(Modifier.width(6.dp)); Text(L.t("ساختن سرورهای WARP جدید", "Make new WARP servers")) }
}

/** A backup file in the backup folder, newest first. */
private class BackupFile(val file: File) {
    val date: String = java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm").format(java.util.Date(file.lastModified()))
    val size: String = "%.0f KB".format(maxOf(1.0, file.length() / 1024.0))
}

private fun listBackups(dir: String): List<BackupFile> =
    File(dir).listFiles { f -> f.isFile && f.name.endsWith(".nbk", true) }.orEmpty()
        .sortedByDescending { it.lastModified() }.take(5).map(::BackupFile)

/** Asks for a folder (Windows' own folder picker), starting at [start]. */
private fun pickFolder(start: String): String? {
    val c = javax.swing.JFileChooser(File(start).takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = L.t("پوشهٔ پشتیبان‌ها", "Backup folder")
        isAcceptAllFileFilterUsed = false
    }
    return if (c.showDialog(null, L.t("انتخاب این پوشه", "Use this folder")) == javax.swing.JFileChooser.APPROVE_OPTION) c.selectedFile?.path else null
}

/**
 * Encrypted backup. Files go straight into one folder the user can see and change (Documents\Netino by default),
 * named by date, so backing up needs no save dialog and the latest backups can be restored from the list.
 */
@Composable
internal fun Backup(onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val dir by Prefs.backupDirFlow.collectAsState()
    var files by remember(dir) { mutableStateOf(listBackups(dir)) }
    // What the password is for: export, or restoring this file
    var exportAsk by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<File?>(null) }

    Text(L.t("همهٔ سرورها، اشتراک‌ها، گروه‌ها و تنظیمات در یک فایل رمزدار (.nbk). همان فایل پشتیبان نتینوی اندروید است: از گوشی به کامپیوتر و برعکس.",
        "All servers, subscriptions, groups and settings in one password-protected file (.nbk), the same as Netino for Android's backup: phone to PC and back."),
        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

    // Where backups go
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(NIcons.Folder, null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(L.t("محل ذخیره", "Saved to"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(dir, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis)
            }
            TextButton(onClick = {
                runCatching { File(dir).mkdirs(); java.awt.Desktop.getDesktop().open(File(dir)) }
                    .onFailure { onMessage(L.t("پوشه باز نشد", "Couldn't open the folder")) }
            }) { Text(L.t("باز کردن", "Open")) }
            TextButton(onClick = { pickFolder(dir)?.let { Prefs.setBackupDir(it) } }) { Text(L.t("تغییر", "Change")) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilledTonalButton(onClick = { exportAsk = true }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(L.t("پشتیبان‌گیری الان", "Back up now"))
        }
        OutlinedButton(onClick = {
            val d = FileDialog(null as Frame?, L.t("فایل پشتیبان", "Backup file"), FileDialog.LOAD)
            d.directory = dir
            d.file = "*.nbk"
            d.setFilenameFilter { _, n -> n.endsWith(".nbk", true) }
            d.isVisible = true
            d.files.firstOrNull()?.let { restore = it }
        }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(L.t("بازگردانی از فایل…", "Restore from file…"))
        }
    }

    // The latest backups in the folder
    if (files.isNotEmpty()) {
        Text(L.t("پشتیبان‌های اخیر", "Recent backups"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        files.forEach { b ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${b.date}  ·  ${b.size}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = { restore = b.file }) { Text(L.t("بازگردانی", "Restore")) }
            }
        }
    } else {
        Text(L.t("هنوز پشتیبانی در این پوشه نیست.", "No backups in this folder yet."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }

    if (exportAsk) PasswordDialog(confirm = true, onDismiss = { exportAsk = false }) { pw ->
        exportAsk = false
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = File(dir).apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm").format(java.util.Date())
                    var f = File(folder, "netino-backup-$stamp.nbk")
                    var n = 2
                    while (f.exists()) f = File(folder, "netino-backup-$stamp-${n++}.nbk")
                    f.writeBytes(BackupCrypto.encrypt(Repository.exportBackup(), pw))
                    f
                }
            }
            files = listBackups(dir)
            onMessage(r.fold({ L.t("پشتیبان ذخیره شد: ${it.name}", "Backup saved: ${it.name}") },
                { L.t("ذخیره نشد: ${it.message}", "Couldn't save: ${it.message}") }))
        }
    }

    restore?.let { file ->
        PasswordDialog(confirm = false, note = L.t("سرورها و تنظیمات فعلی با محتوای «${file.name}» جایگزین می‌شوند.",
            "Your current servers and settings will be replaced by “${file.name}”."), onDismiss = { restore = null }) { pw ->
            restore = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Repository.importBackup(BackupCrypto.decrypt(file.readBytes(), pw)) } }
                onMessage(r.fold({ L.t("$it سرور بازگردانی شد", "$it servers restored") },
                    { L.t("رمز اشتباه است یا فایل خراب است", "Wrong password or a damaged file") }))
            }
        }
    }
}

@Composable
private fun PasswordDialog(confirm: Boolean, note: String? = null, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val ok = pw.length >= 4 && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.t("رمز فایل پشتیبان", "Backup password")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note ?: L.t("بدون این رمز فایل باز نمی‌شود؛ آن را فراموش نکنید.", "The file can't be opened without it: don't forget it."),
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
internal fun Privacy() {
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
    Toggle(L.t("فعال بودن کلید میانبر", "Keyboard shortcut on"),
        L.t("وصل و قطع از هر جای ویندوز (بعد از اجرای دوبارهٔ Netino اعمال می‌شود)", "Connect / disconnect from anywhere (applies after Netino restarts)"),
        hotkey) { hotkey = it; Prefs.hotkey = it }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(L.t("کلید میانبر اتصال", "Connect shortcut"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Text("Ctrl + Alt + N", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
