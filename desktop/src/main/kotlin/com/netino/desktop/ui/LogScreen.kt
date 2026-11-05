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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
    val hasApi = remember { com.netino.vpn.core.NetinoApi.base != null }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageTitle(L.t("گزارش", "Log"), Modifier.weight(1f))
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
        // Filter: everything, problems only, or connection events; plus a search box
        var filter by remember { mutableStateOf(0) }
        var query by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(L.t("همه", "All"), L.t("خطاها", "Problems"), L.t("اتصال", "Connection")).forEachIndexed { i, label ->
                androidx.compose.material3.FilterChip(filter == i, onClick = { filter = i }, label = { Text(label) },
                    leadingIcon = if (filter == i) { { androidx.compose.material3.Icon(NIcons.Check, null, Modifier.size(18.dp)) } } else null)
            }
            Spacer(Modifier.weight(1f))
            SearchField(query, { query = it }, L.t("جست‌وجو در گزارش", "Search the log"), Modifier.width(240.dp))
        }
        val shown = log.filter { e ->
            (query.isBlank() || e.text.contains(query, true)) && when (filter) {
                1 -> isProblem(e.text)
                2 -> CONNECTION_WORDS.any { e.text.contains(it, true) }
                else -> true
            }
        }
        // Newest at the bottom: follow it
        LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) Text(L.t("چیزی پیدا نشد", "Nothing found"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(shown) { e ->
                    Text(time.format(Date(e.time)) + "  " + e.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isProblem(e.text)) MaterialTheme.status.badText else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private val PROBLEM_WORDS = listOf("error", "failed", "✗", "خطا", "ناموفق", "نشد", "stopped", "didn't", "not answering", "جواب نداد", "جواب نمی‌دهد")
private val CONNECTION_WORDS = listOf("connect", "وصل", "قطع", "اتصال", "switch", "عوض", "network", "شبکه", "woke", "بیدار", "tunnel", "تونل", "proxy", "پراکسی")

private fun isProblem(text: String) = PROBLEM_WORDS.any { text.contains(it, true) }

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
