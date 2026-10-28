package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.data.DailyUsage
import com.netino.vpn.data.Repository
import com.netino.vpn.service.CrashReporter
import com.netino.vpn.service.VpnController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** The last [n] days, oldest first, with zero for days without traffic. */
private fun lastDays(usage: List<DailyUsage>, n: Int): List<DailyUsage> {
    val byDay = usage.associateBy { it.day }
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
    return List(n) {
        val key = DayKey.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        byDay[key] ?: DailyUsage(key, 0, 0)
    }
}

/** Totals per period, a 30-day chart, the daily list, and (folded away) the event log. */
@Composable
fun StatsScreen(modifier: Modifier) {
    val usage by Repository.usage.collectAsStateWithLifecycle()
    val log by VpnController.log.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val days30 = remember(usage) { lastDays(usage, 30) }
    val month = DayKey.format(Date()).take(7)
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var netReport by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    if (netReport) NetReportDialog(onDismiss = { netReport = false })

    LazyColumn(
        modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.usage_page), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        }
        item {
            val periods = listOf(
                R.string.today to days30.takeLast(1),
                R.string.days_7 to days30.takeLast(7),
                R.string.this_month to usage.filter { it.day.startsWith(month) },
                R.string.last_30 to days30,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                periods.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, list) -> UsageTile(stringResource(label), list.sumOf { it.rx }, list.sumOf { it.tx }, Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { MonthChart(days30) }
        item { DailyList(days30.takeLast(7).reversed()) }
        item {
            // For comparing networks: what worked where (firewall method, WARP route, endpoints, MTU)
            OutlinedButton(onClick = { netReport = true }, modifier = Modifier.fillMaxWidth().heightIn(min = ActionHeight),
                shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.net_report))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.animateContentSize(LocalMotion.current.spec(Motion.Medium))) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { logOpen = !logOpen }, color = Color.Transparent, modifier = Modifier.weight(1f)) {
                            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.live_log), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(if (logOpen) 180f else 0f), tint = cs.onSurfaceVariant)
                            }
                        }
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
                    if (logOpen) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (log.isEmpty()) Text(stringResource(R.string.no_events), color = cs.onSurfaceVariant)
                        log.takeLast(200).asReversed().forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(cs.primary))
                                Spacer(Modifier.width(10.dp))
                                Text(localDigits(timeFmt.format(Date(e.time))), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                Spacer(Modifier.width(10.dp))
                                Text(e.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageTile(label: String, down: Long, up: Long, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cs.onSurfaceVariant)
            Text(localDigits(formatBytes(down + up)), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp), fontWeight = FontWeight.Bold)
            Text("↓ " + localDigits(formatBytes(down)) + " · ↑ " + localDigits(formatBytes(up)),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun uploadColor() = if (isDarkSurface()) Color(0xFF9BE05A) else BrandGreenDeep

/** Thirty stacked bars (download under upload), time running in the reading direction. */
@Composable
private fun MonthChart(days: List<DailyUsage>) {
    val cs = MaterialTheme.colorScheme
    val dl = cs.primary
    val ul = uploadColor()
    val line = cs.outlineVariant
    val max = days.maxOfOrNull { it.rx + it.tx } ?: 0L
    val chartCd = stringResource(R.string.usage_chart_cd, localDigits(formatBytes(max)))
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.last_30), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Legend(dl, stringResource(R.string.download))
                Legend(ul, stringResource(R.string.upload))
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = chartCd }) {
                val top = max.coerceAtLeast(1).toFloat()
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (days.size - 1)) / days.size
                days.forEachIndexed { i, d ->
                    val slot = if (layoutDirection == LayoutDirection.Rtl) days.lastIndex - i else i
                    val x = slot * (w + gap)
                    val hd = size.height * d.rx / top
                    val hu = size.height * d.tx / top
                    bar(dl, x, size.height - hd, w, hd)
                    bar(ul, x, size.height - hd - hu - if (hu > 0) 1f else 0f, w, hu)
                }
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val st = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)
                Text(stringResource(R.string.days_ago, localDigits("30")), style = st, color = cs.onSurfaceVariant)
                Text(stringResource(R.string.days_ago, localDigits("15")), style = st, color = cs.onSurfaceVariant)
                Text(stringResource(R.string.today), style = st, color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun DrawScope.bar(c: Color, x: Float, y: Float, w: Float, h: Float) {
    if (h <= 0f) return
    drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(2.dp.toPx().coerceAtMost(h / 2)))
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Newest first: today, yesterday, then weekday names. */
@Composable
private fun DailyList(days: List<DailyUsage>) {
    val cs = MaterialTheme.colorScheme
    val weekday = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(stringResource(R.string.daily), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
            days.forEachIndexed { i, d ->
                HorizontalDivider(color = cs.outlineVariant)
                val label = when (i) {
                    0 -> today
                    1 -> yesterday
                    else -> runCatching { weekday.format(DayKey.parse(d.day)!!) }.getOrDefault(d.day)
                }
                val split = "↓ " + localDigits(formatBytes(d.rx)) + " · ↑ " + localDigits(formatBytes(d.tx))
                // Large text: download / upload move under the day, so the total keeps its room
                val stacked = LocalDensity.current.fontScale > 1.3f
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                        if (stacked) Text(split, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cs.onSurfaceVariant)
                    }
                    if (!stacked) Text(split, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cs.onSurfaceVariant)
                    Text(localDigits(formatBytes(d.rx + d.tx)), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = 72.dp))
                }
            }
        }
    }
}
