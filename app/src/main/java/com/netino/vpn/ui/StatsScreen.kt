package com.netino.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.data.Repository
import com.netino.vpn.service.VpnController
import java.text.SimpleDateFormat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.netino.vpn.service.CrashReporter
import java.util.Date
import java.util.Locale

/** Live traffic, session totals, last 7 days and the real-time event log. */
@Composable
fun StatsScreen(modifier: Modifier) {
    val t by VpnController.traffic.collectAsStateWithLifecycle()
    val usage by Repository.usage.collectAsStateWithLifecycle()
    val log by VpnController.log.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val todayUsage = usage.firstOrNull { it.day == today }
    val month = usage.sumOf { it.rx + it.tx }
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    LazyColumn(
        modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.down_speed), formatSpeed(t.downBps), Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ArrowDownward, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                }
                StatTile(stringResource(R.string.up_speed), formatSpeed(t.upBps), Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ArrowUpward, null, tint = BrandGreenDeep, modifier = Modifier.size(16.dp))
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.last_60s)) {
                AreaChart(t.history.map { it.first }, Modifier.fillMaxWidth().height(100.dp), BrandBlue)
                AreaChart(t.history.map { it.second }, Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp), BrandGreen)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.this_session), formatBytes(t.sessionRx + t.sessionTx), Modifier.weight(1f))
                StatTile(stringResource(R.string.today), formatBytes((todayUsage?.rx ?: 0) + (todayUsage?.tx ?: 0)), Modifier.weight(1f))
                StatTile(stringResource(R.string.days_30), formatBytes(month), Modifier.weight(1f))
            }
        }
        item {
            SectionCard(stringResource(R.string.days_7)) {
                val last = usage.takeLast(7)
                val primary = MaterialTheme.colorScheme.primary
                val track = MaterialTheme.colorScheme.surfaceContainerHighest
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val max = (last.maxOfOrNull { it.rx + it.tx } ?: 0L).coerceAtLeast(1).toFloat()
                    val w = size.width / 7
                    for (i in 0 until 7) {
                        val x = i * w + w * 0.22f
                        drawRoundRect(track, Offset(x, 0f), Size(w * 0.56f, size.height), CornerRadius(10f))
                        last.getOrNull(i)?.let { d ->
                            val h = size.height * ((d.rx + d.tx) / max)
                            drawRoundRect(primary, Offset(x, size.height - h), Size(w * 0.56f, h), CornerRadius(10f))
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Plain-text report (device, version, events) for sending to support; no config links are included
                TextButton(onClick = {
                    val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    val text = CrashReporter.deviceLine() + "\n" + log.joinToString("\n") { "${full.format(Date(it.time))}  ${it.text}" }
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Netino report", text))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy_report))
                }
            }
        }
        if (log.isEmpty()) item {
            Text(stringResource(R.string.no_events), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        items(log.reversed()) { e ->
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(10.dp))
                Text(timeFmt.format(Date(e.time)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
