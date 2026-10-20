package com.netino.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.netino.desktop.L
import com.netino.vpn.data.DailyUsage
import com.netino.vpn.data.Repository
import java.time.LocalDate

/** Data used through Netino: today, this week, this month, and the last 30 days day by day. */
@Composable
fun StatsScreen() {
    val usage by Repository.usage.collectAsState()
    val byDay = remember(usage) { usage.associateBy { it.day } }
    val today = LocalDate.now()
    fun sum(days: Int) = (0 until days).sumOf { byDay[today.minusDays(it.toLong()).toString()]?.let { u -> u.rx + u.tx } ?: 0L }
    val month = remember(usage) { usage.filter { it.day.startsWith(today.toString().take(7)) }.sumOf { it.rx + it.tx } }
    val last30 = remember(usage) { (29 downTo 0).map { byDay[today.minusDays(it.toLong()).toString()] ?: DailyUsage(today.minusDays(it.toLong()).toString(), 0, 0) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L.t("آمار مصرف", "Data usage"), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(L.t("امروز", "Today"), formatBytes(sum(1)))
            Tile(L.t("۷ روز اخیر", "Last 7 days"), formatBytes(sum(7)))
            Tile(L.t("این ماه", "This month"), formatBytes(month))
            Tile(L.t("۳۰ روز اخیر", "Last 30 days"), formatBytes(sum(30)))
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(L.t("۳۰ روز اخیر (دانلود + آپلود)", "Last 30 days (download + upload)"), style = MaterialTheme.typography.titleSmall)
                val bar = MaterialTheme.colorScheme.primary
                val up = MaterialTheme.colorScheme.secondary
                val track = MaterialTheme.colorScheme.surfaceContainerHigh
                Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp)) {
                    val max = last30.maxOf { it.rx + it.tx }.coerceAtLeast(1)
                    val slot = size.width / last30.size
                    val w = slot * 0.62f
                    last30.forEachIndexed { i, d ->
                        val x = i * slot + (slot - w) / 2
                        drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(4f))
                        val hRx = size.height * d.rx / max
                        val hTx = size.height * d.tx / max
                        drawRoundRect(bar, Offset(x, size.height - hRx - hTx), Size(w, hRx + hTx), CornerRadius(4f))
                        drawRoundRect(up, Offset(x, size.height - hTx), Size(w, hTx), CornerRadius(4f))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(last30.first().day.substring(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(L.t("امروز", "Today"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(L.t("رنگ اصلی: دانلود • رنگ دوم: آپلود", "Main colour: download • second colour: upload"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // Day by day, newest first, days with traffic only
        last30.reversed().filter { it.rx + it.tx > 0 }.forEach { d ->
            Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(d.day, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("↓ ${formatBytes(d.rx)}   ↑ ${formatBytes(d.tx)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
