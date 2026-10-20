package com.netino.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.vpn.core.NetReport
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(onMessage: (String) -> Unit) {
    val log by Controller.logs.collectAsState()
    val list = rememberLazyListState()
    val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    LaunchedEffect(log.size) { if (log.isNotEmpty()) list.scrollToItem(log.size - 1) }
    val hasApi = remember { com.netino.vpn.core.NetinoApi.base != null }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.t("گزارش", "Log"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // The per-network report (same as the Android app's), for sending to support
            OutlinedButton(onClick = {
                val text = NetReport.text(listOf("Netino for Windows ${System.getProperty("jpackage.app-version") ?: "dev"}", "Xray ${com.netino.vpn.core.XrayCore.version}")) + "\n\n" + log.joinToString("\n") { time.format(Date(it.time)) + "  " + it.text }
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                onMessage(L.t("گزارش کپی شد", "Report copied"))
            }) { Text(L.t("کپی گزارش شبکه", "Copy network report")) }
            if (hasApi) Button(onClick = { sending = true }) { Text(L.t("ارسال برای سازنده", "Send to developer")) }
        }
        if (sending) SendReportDialog(
            NetReport.text(listOf("Netino for Windows ${System.getProperty("jpackage.app-version") ?: "dev"}", "Xray ${com.netino.vpn.core.XrayCore.version}", "Mode: " + if (com.netino.desktop.Prefs.tunMode) "full tunnel" else "proxy")) +
                "\n\n" + log.joinToString("\n") { time.format(Date(it.time)) + "  " + it.text },
            onMessage,
        ) { sending = false }
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            items(log) { e ->
                Text(time.format(Date(e.time)) + "  " + e.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

/** The report to the developer (Netino's service forwards it to Telegram), with an optional note and contact. */
@Composable
private fun SendReportDialog(report: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(L.t("ارسال گزارش برای سازندهٔ نتینو", "Send the report to Netino's developer")) },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(note, { note = it }, label = { Text(L.t("چه اتفاقی افتاد؟ (اختیاری)", "What happened? (optional)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(contact, { contact = it }, singleLine = true, label = { Text(L.t("آیدی تلگرام یا ایمیل برای جواب (اختیاری)", "Telegram ID or email for a reply (optional)")) }, modifier = Modifier.fillMaxWidth())
                Text(L.t("در گزارش هیچ کلید یا لینک کانفیگی نیست؛ فقط رفتار شبکه و رویدادهای اتصال.", "The report has no keys or config links: only how the network behaved and connection events."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = com.netino.vpn.core.NetinoApi.sendIssue(report, note, contact, "Windows " + (System.getProperty("jpackage.app-version") ?: "dev"))
                    busy = false
                    if (r.isSuccess) { onMessage(L.t("فرستاده شد. ممنون!", "Sent. Thank you!")); onDismiss() }
                    else onMessage(L.t("فرستاده نشد: ${r.exceptionOrNull()?.message}", "Couldn't send: ${r.exceptionOrNull()?.message}"))
                }
            }) { Text(if (busy) L.t("در حال ارسال…", "Sending…") else L.t("ارسال", "Send")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(L.t("انصراف", "Cancel")) } },
    )
}
