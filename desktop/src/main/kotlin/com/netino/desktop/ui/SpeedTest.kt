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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
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
    val cs = MaterialTheme.colorScheme
    Panel(Modifier.fillMaxWidth(), padding = 0) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NIcons.Speed, null, tint = cs.primary)
                Text(L.t("تست سرعت", "Speed test"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                result?.let { r -> TextButton(onClick = { Qr.copy(r.text()) }) { Icon(NIcons.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(L.t("کپی", "Copy")) } }
                FilledTonalButton(enabled = !running, modifier = Modifier.height(40.dp), onClick = {
                    running = true
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
                        if (r != null) result = r
                        r?.let { Controller.log("Speed test: " + it.text().replace("\n", " • ")) }
                        running = false
                    }
                }) { Text(if (running) stage else if (result != null) L.t("دوباره", "Again") else L.t("شروع", "Start"), fontWeight = FontWeight.SemiBold) }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            val r = result
            Text(
                if (r == null) L.t("تأخیر، دانلود و آپلود از همین سرور", "Delay, download and upload through this server")
                else L.t("آخرین نتیجه: ", "Last result: ") + L.t("تأخیر ", "delay ") + (if (r.ms > 0) localDigits("${r.ms}") + "ms" else "—") +
                    " · ↓ " + localDigits(r.mbps(r.downKbps)) + " · ↑ " + localDigits(r.mbps(r.upKbps)),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
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
