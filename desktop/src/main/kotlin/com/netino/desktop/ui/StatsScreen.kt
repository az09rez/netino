package com.netino.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.vpn.data.DailyUsage
import com.netino.vpn.data.Repository
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Data used through Netino: totals per period, a 30-day chart, and the last days one by one. */
@Composable
fun StatsScreen() {
    val usage by Repository.usage.collectAsState()
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val last30 = remember(usage) {
        val byDay = usage.associateBy { it.day }
        (29 downTo 0).map { byDay[today.minusDays(it.toLong()).toString()] ?: DailyUsage(today.minusDays(it.toLong()).toString(), 0, 0) }
    }
    val month = remember(usage) { usage.filter { it.day.startsWith(today.toString().take(7)) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(L.t("مصرف", "Usage"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(L.t("امروز", "Today") to last30.takeLast(1), L.t("۷ روز اخیر", "Last 7 days") to last30.takeLast(7),
                L.t("این ماه", "This month") to month, L.t("۳۰ روز اخیر", "Last 30 days") to last30).forEach { (label, list) ->
                Tile(label, list.sumOf { it.rx }, list.sumOf { it.tx }, Modifier.weight(1f))
            }
        }
        Columns(
            { Chart(last30) },
            { Daily(last30.takeLast(7).reversed(), today) },
        )
    }
}

@Composable
private fun Tile(label: String, down: Long, up: Long, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Text(localDigits(formatBytes(down + up)), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp), fontWeight = FontWeight.Bold)
            Text("↓ " + localDigits(formatBytes(down)) + " · ↑ " + localDigits(formatBytes(up)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Thirty stacked bars (download under upload), time running in the reading direction. */
@Composable
private fun Chart(days: List<DailyUsage>) {
    val cs = MaterialTheme.colorScheme
    val dl = cs.primary
    val ul = if (isDarkSurface()) Color(0xFF9BE05A) else BrandGreenDeep
    val line = cs.outlineVariant
    val max = days.maxOfOrNull { it.rx + it.tx } ?: 0L
    val cd = L.t("نمودار مصرف ۳۰ روز اخیر؛ بیشترین مصرف ", "Usage over the last 30 days; highest day ") + localDigits(formatBytes(max))
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L.t("۳۰ روز اخیر", "Last 30 days"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Legend(dl, L.t("دانلود", "Download"))
                Legend(ul, L.t("آپلود", "Upload"))
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = cd }) {
                val top = max.coerceAtLeast(1).toFloat()
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (days.size - 1)) / days.size
                val r = CornerRadius(2.dp.toPx())
                days.forEachIndexed { i, d ->
                    val slot = if (layoutDirection == LayoutDirection.Rtl) days.lastIndex - i else i
                    val x = slot * (w + gap)
                    val hd = size.height * d.rx / top
                    val hu = size.height * d.tx / top
                    if (hd > 0) drawRoundRect(dl, Offset(x, size.height - hd), Size(w, hd), r)
                    if (hu > 0) drawRoundRect(ul, Offset(x, size.height - hd - hu - 1f), Size(w, hu), r)
                }
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(L.t("۳۰ روز پیش", "30 days ago"), L.t("۱۵ روز پیش", "15 days ago"), L.t("امروز", "Today")).forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Newest first: today, yesterday, then weekday names. */
@Composable
private fun Daily(days: List<DailyUsage>, today: LocalDate) {
    val cs = MaterialTheme.colorScheme
    val locale = if (Prefs.fa) Locale.forLanguageTag("fa") else Locale.ENGLISH
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(L.t("روزانه", "Daily"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
            days.forEachIndexed { i, d ->
                HorizontalDivider(color = cs.outlineVariant)
                val label = when (i) {
                    0 -> L.t("امروز", "Today")
                    1 -> L.t("دیروز", "Yesterday")
                    else -> runCatching { LocalDate.parse(d.day).dayOfWeek.getDisplayName(TextStyle.FULL, locale) }.getOrDefault(d.day)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("↓ " + localDigits(formatBytes(d.rx)) + " · ↑ " + localDigits(formatBytes(d.tx)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(localDigits(formatBytes(d.rx + d.tx)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
                }
            }
        }
    }
}
