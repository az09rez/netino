package com.netino.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.vpn.core.SpeedProbe
import com.netino.vpn.data.Repository
import com.netino.vpn.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One result: delay, download and upload through the current server (its own probe inbound, so the real path). */
private data class Speed(val server: String, val ms: Long, val downKbps: Long, val upKbps: Long, val at: Long) {
    fun mbps(kbps: Long) = "%.1f Mbps".format(kbps * 8 / 1000.0)
    fun text() = "Netino speed test • $server\n" +
        "Ping: ${if (ms > 0) "$ms ms" else "-"}   Download: ${mbps(downKbps)}   Upload: ${mbps(upKbps)}\n" +
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))
}

@Composable
fun SpeedTestCard() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Speed?>(null) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(480.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(NIcons.Speed, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(L.t("تست سرعت", "Speed test"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !running, onClick = {
                    running = true; result = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            val ep = XrayVpnService.probe ?: return@withContext null
                            val server = (Controller.state.value as? ConnState.Connected)?.server?.name.orEmpty()
                            stage = L.t("تأخیر…", "Ping…")
                            val ms = SpeedProbe.delay(ep.port, ep.user, ep.pass, Repository.settings.value.testUrl, tries = 3)
                            stage = L.t("دانلود…", "Download…")
                            val down = SpeedProbe.run(ep.port, ep.user, ep.pass, timeoutMs = 10_000)
                            stage = L.t("آپلود…", "Upload…")
                            val up = SpeedProbe.upload(ep.port, ep.user, ep.pass)
                            Speed(server, ms, down.kbps, up.kbps, System.currentTimeMillis())
                        }
                        result = r
                        r?.let { Controller.log("Speed test: " + it.text().replace("\n", " • ")) }
                        running = false
                    }
                }) { Text(if (running) stage else L.t("شروع", "Start")) }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            result?.let { r ->
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Value(L.t("تأخیر", "Ping"), if (r.ms > 0) "${r.ms} ms" else "-")
                    Value(L.t("دانلود", "Download"), r.mbps(r.downKbps))
                    Value(L.t("آپلود", "Upload"), r.mbps(r.upKbps))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { Qr.copy(r.text()) }) { Text(L.t("کپی نتیجه", "Copy result")) }
                }
            }
        }
    }
}

@Composable
private fun Value(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
