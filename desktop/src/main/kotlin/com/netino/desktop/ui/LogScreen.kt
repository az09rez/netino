package com.netino.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.t("گزارش", "Log"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // The per-network report (same as the Android app's), for sending to support
            OutlinedButton(onClick = {
                val text = NetReport.text(listOf("Netino for Windows ${System.getProperty("jpackage.app-version") ?: "dev"}", "Xray ${com.netino.vpn.core.XrayCore.version}")) + "\n\n" + log.joinToString("\n") { time.format(Date(it.time)) + "  " + it.text }
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                onMessage(L.t("گزارش کپی شد", "Report copied"))
            }) { Text(L.t("کپی گزارش شبکه", "Copy network report")) }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            items(log) { e ->
                Text(time.format(Date(e.time)) + "  " + e.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
