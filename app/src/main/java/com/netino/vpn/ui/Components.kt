package com.netino.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netino.vpn.R
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Server

/** Rounded avatar with the protocol initials, tinted per protocol family. */
@Composable
fun ServerAvatar(protocol: Protocol, size: Int = 44) {
    val color = when (protocol) {
        Protocol.WIREGUARD -> Color(0xFF8B5CF6)
        Protocol.VLESS -> BrandBlue
        Protocol.VMESS -> Color(0xFF0EA5E9)
        Protocol.TROJAN -> Color(0xFFEF4444)
        Protocol.SHADOWSOCKS -> Color(0xFF14B8A6)
        Protocol.HYSTERIA2 -> Color(0xFFF97316)
        Protocol.SOCKS -> Color(0xFF64748B)
    }
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when (protocol) { Protocol.WIREGUARD -> "WG"; Protocol.SHADOWSOCKS -> "SS"; Protocol.HYSTERIA2 -> "HY"; else -> protocol.label.take(2).uppercase() },
            color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun PingPill(server: Server) {
    val ms = server.lastPingMs
    val c = pingColor(ms, server.pingKind)
    val text = when (ms) {
        -1L -> "—"
        -2L -> stringResource(R.string.ping_failed)
        else -> "$ms ms"
    }
    Row(
        Modifier.clip(CircleShape).background(c.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c, style = MaterialTheme.typography.labelMedium)
    }
}

fun protocolLine(s: Server): String = buildString {
    append(s.protocol.label)
    s.xray?.security?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it.uppercase()) }
    s.xray?.network?.takeIf { it.isNotBlank() && it != "tcp" }?.let { append(" • ").append(it) }
}

@Composable
fun ServerItem(
    s: Server, selected: Boolean, connected: Boolean,
    onClick: () -> Unit,
    onCopy: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(s.protocol)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (connected) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.CheckCircle, stringResource(R.string.connected_badge), tint = Good, modifier = Modifier.size(18.dp))
                    }
                }
                Text(protocolLine(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            PingPill(s)
            if (onDelete != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    onCopy?.let { DropdownMenuItem({ Text(stringResource(R.string.copy_link)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }) }
                    onShare?.let { DropdownMenuItem({ Text(stringResource(R.string.share)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.Share, null) }) }
                    DropdownMenuItem({ Text(stringResource(R.string.delete), color = Bad) }, { menu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Bad) })
                }
            } else Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(6.dp))
            }
            content()
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, desc: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Smooth area chart (used for live speed). Always drawn left→right regardless of text direction. */
@Composable
fun AreaChart(values: List<Long>, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1).toFloat()
        val step = size.width / (values.size - 1)
        fun pt(i: Int) = Offset(i * step, size.height * (1 - values[i] / max) * 0.92f + size.height * 0.04f)
        val line = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) {
                val p0 = pt(i - 1); val p1 = pt(i); val mx = (p0.x + p1.x) / 2
                cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
            }
        }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { it(); Spacer(Modifier.width(6.dp)) }
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
