package com.netino.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.desktop.L
import com.netino.desktop.Prefs
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Server

// Shared components of the redesign (docs/design/DESIGN.md, canvas/Components.dc.html), Windows side.

/** Persian digits (and decimal separator) when the UI is Persian. */
fun localDigits(s: String): String = if (!Prefs.fa) s else buildString(s.length) {
    s.forEach { c -> append(if (c in '0'..'9') '۰' + (c - '0') else if (c == '.') '٫' else c) }
}

@Composable
@ReadOnlyComposable
fun isDarkSurface() = MaterialTheme.colorScheme.background.luminance() < 0.5f

// ---------------------------------------------------------------- protocol avatar

private class AvatarColors(val lightBg: Color, val lightFg: Color, val darkBg: Color, val darkFg: Color)

private fun avatarColors(p: Protocol) = when (p) {
    Protocol.VLESS -> AvatarColors(Color(0xFFD6E7FF), Color(0xFF0A3A73), Color(0xFF0D3F78), Color(0xFFD6E7FF))
    Protocol.VMESS -> AvatarColors(Color(0xFFCCF0EE), Color(0xFF0B5550), Color(0xFF0B3A37), Color(0xFFB5EFEA))
    Protocol.TROJAN -> AvatarColors(Color(0xFFEBDDFF), Color(0xFF4A2A8A), Color(0xFF3A2A66), Color(0xFFE3D4FF))
    Protocol.SHADOWSOCKS -> AvatarColors(Color(0xFFFDE2EE), Color(0xFF8A1F4C), Color(0xFF4A1530), Color(0xFFFFC9DF))
    Protocol.HYSTERIA2 -> AvatarColors(Color(0xFFFFE0CC), Color(0xFF8A3B00), Color(0xFF4A2410), Color(0xFFFFD3B5))
    Protocol.WIREGUARD -> AvatarColors(Color(0xFFDDF3C8), Color(0xFF2C5A08), Color(0xFF22420A), Color(0xFFC8F09E))
    else -> AvatarColors(Color(0xFFE6ECF5), Color(0xFF4B5567), Color(0xFF1A2438), Color(0xFF9AA6BA))
}

fun protocolCode(p: Protocol) = when (p) {
    Protocol.VLESS -> "VL"; Protocol.VMESS -> "VM"; Protocol.TROJAN -> "TR"; Protocol.SHADOWSOCKS -> "SS"
    Protocol.HYSTERIA2 -> "H2"; Protocol.WIREGUARD -> "WG"; else -> p.label.take(2).uppercase()
}

/** Rounded square with the protocol's code, in the protocol's colors (44dp in cards, 40dp in rows). */
@Composable
fun ServerAvatar(protocol: Protocol, size: Int = 44) {
    val c = avatarColors(protocol)
    val dark = isDarkSurface()
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(if (dark) c.darkBg else c.lightBg),
        contentAlignment = Alignment.Center) {
        Text(protocolCode(protocol), color = if (dark) c.darkFg else c.lightFg,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = if (size >= 44) 12.sp else 11.sp), fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- latency pill

/** Real delay: green ≤ 600 ms, orange ≤ 1500 ms, red above or failed, grey untested (TCP pings use tighter limits). */
@Composable
fun LatencyPill(ms: Long, kind: PingKind = PingKind.REAL, testing: Boolean = false) {
    val motion = LocalMotion.current
    if (testing) {
        val base = MaterialTheme.colorScheme.onSurfaceVariant
        val shift = if (motion.loops) rememberInfiniteTransition()
            .animateFloat(-1f, 2f, infiniteRepeatable(tween(Motion.Shimmer, easing = LinearEasing))).value else 0.5f
        Box(Modifier.size(64.dp, 28.dp).clip(CircleShape).background(Brush.horizontalGradient(
            0f to base.copy(alpha = 0.18f), (shift - 0.3f).coerceIn(0f, 1f) to base.copy(alpha = 0.18f),
            shift.coerceIn(0f, 1f) to base.copy(alpha = 0.42f), (shift + 0.3f).coerceIn(0f, 1f) to base.copy(alpha = 0.18f),
            1f to base.copy(alpha = 0.18f),
        )))
        return
    }
    val st = MaterialTheme.status
    val (good, ok) = if (kind == PingKind.REAL || kind == PingKind.NONE) 600L to 1500L else 200L to 500L
    val (bg, fg) = when {
        ms == -1L -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        ms <= 0L -> st.badContainer to st.badText
        ms <= good -> st.goodContainer to st.goodText
        ms <= ok -> st.warnContainer to st.warnText
        else -> st.badContainer to st.badText
    }
    val text = when {
        ms == -1L -> "—"
        ms <= 0L -> L.t("ناموفق", "failed")
        ms > 1500 -> localDigits("%.1f".format(java.util.Locale.US, ms / 1000.0)) + "s"
        else -> localDigits(ms.toString()) + "ms"
    }
    val bgA by animateColorAsState(bg, motion.spec(200))
    val fgA by animateColorAsState(fg, motion.spec(200))
    val pop = remember { Animatable(1f) }
    LaunchedEffect(ms) { if (!motion.instant) { pop.snapTo(0.88f); pop.animateTo(1f, motion.spec(200)) } }
    Box(Modifier.scale(pop.value).height(28.dp).clip(CircleShape).background(bgA).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fgA, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun PingPill(server: Server, testing: Boolean = false) = LatencyPill(server.lastPingMs, server.pingKind, testing)

// ---------------------------------------------------------------- connect button

enum class ConnectLook { OFF, CONNECTING, SEARCHING, CONNECTED, ERROR }

/**
 * The connect button (156dp in a 196dp halo): off = grey; connecting / searching = warning container and ring with a
 * halo that grows and fades; connected = brand gradient with a calm green wave; error = error colors and a short
 * shake. While searching, a 5-part ring shows [searchProgress] (0..1 over all steps).
 */
@Composable
fun ConnectButton(look: ConnectLook, label: String, stateLabel: String, searchProgress: Float = 0f, onClick: () -> Unit) {
    val motion = LocalMotion.current
    val st = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val busy = look == ConnectLook.CONNECTING || look == ConnectLook.SEARCHING
    val fill by animateColorAsState(when (look) {
        ConnectLook.OFF -> cs.surfaceContainerHigh
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warnContainer
        ConnectLook.CONNECTED -> BrandBlue
        ConnectLook.ERROR -> st.badContainer
    }, motion.spec(Motion.Medium))
    val ring by animateColorAsState(when (look) {
        ConnectLook.OFF -> cs.surfaceContainerHigh
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warn
        ConnectLook.CONNECTED -> Color.Transparent
        ConnectLook.ERROR -> st.bad
    }, motion.spec(Motion.Medium))
    val iconColor by animateColorAsState(when (look) {
        ConnectLook.OFF -> cs.onSurfaceVariant
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warnText
        ConnectLook.CONNECTED -> Color.White
        ConnectLook.ERROR -> cs.error
    }, motion.spec(Motion.Medium))
    val gradient by animateFloatAsState(if (look == ConnectLook.CONNECTED) 1f else 0f, motion.spec(Motion.Medium))
    val haloColor = when (look) {
        ConnectLook.CONNECTED -> st.good.copy(alpha = 0.16f)
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warn.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    val loop = motion.loops && (busy || look == ConnectLook.CONNECTED)
    val wave = remember { Animatable(0f) }
    LaunchedEffect(look, loop) {
        if (!loop) { wave.snapTo(0.35f); return@LaunchedEffect }
        if (look == ConnectLook.CONNECTED) { wave.snapTo(0f); wave.animateTo(1f, tween(Motion.Wave, easing = Motion.EmphasizedDecelerate)) }
        val period = if (look == ConnectLook.CONNECTED) Motion.HaloConnected else Motion.HaloConnecting
        while (true) { wave.snapTo(0f); wave.animateTo(1f, tween(period, easing = LinearEasing)) }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, motion.spec(Motion.Press))
    val iconPop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(look) { if (!motion.instant) { iconPop.snapTo(0.92f); iconPop.animateTo(1f, motion.spec(Motion.Medium)) } }
    LaunchedEffect(look) {
        if (look != ConnectLook.ERROR || motion.instant) return@LaunchedEffect
        shake.animateTo(0f, keyframes { durationMillis = Motion.Slow; 0f at 0; 6f at 50; -6f at 100; 6f at 150; -6f at 200; 6f at 250; 0f at 300 })
    }
    val searchFrac by animateFloatAsState(searchProgress.coerceIn(0f, 1f), motion.spec(Motion.Slow))
    val track = cs.primaryContainer
    val primary = cs.primary

    Box(Modifier.size(196.dp).offset(x = shake.value.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(196.dp)) {
            if (haloColor != Color.Transparent) {
                val t = wave.value
                val alpha = if (loop) (1f - t) else 1f
                drawCircle(haloColor.copy(alpha = haloColor.alpha * alpha), radius = size.minDimension / 2 * (0.9f + 0.22f * t))
            }
            if (look == ConnectLook.SEARCHING) {
                val stroke = 6.dp.toPx()
                val inset = 4.dp.toPx()
                val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
                val parts = 5
                val sweep = 360f / parts
                for (i in 0 until parts) drawArc(track, -90f + i * sweep + 3f, sweep - 6f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (searchFrac > 0f) drawArc(primary, -90f, 360f * searchFrac, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(156.dp).scale(press).clip(CircleShape).background(fill).background(BrandGradient, alpha = gradient)
                .border(BorderStroke(3.dp, ring), CircleShape)
                .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label; stateDescription = stateLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (look == ConnectLook.ERROR) NIcons.ErrorOutline else NIcons.PowerFilled, null,
                Modifier.size(56.dp).scale(iconPop.value), tint = iconColor)
        }
    }
}

// ---------------------------------------------------------------- cards

/** A rounded card on surfaceContainer (24dp corners). */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(padding.dp), content = content)
    }
}

/** The server in use: avatar, caption, name, latency, and a chevron to change it. */
@Composable
fun ServerCard(caption: String, name: String, avatar: @Composable () -> Unit, pill: @Composable () -> Unit, onClick: () -> Unit,
               captionColor: Color = Color.Unspecified) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            avatar()
            Column(Modifier.weight(1f)) {
                Text(caption, style = MaterialTheme.typography.bodySmall,
                    color = if (captionColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else captionColor, maxLines = 1)
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            pill()
            Icon(NIcons.ChevronForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Steps of a search as bars (the current one partly filled), a title line and "cancel". */
@Composable
fun ProgressCard(title: String, steps: Int, current: Int, fraction: Float?, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Panel(Modifier.fillMaxWidth(), padding = 0) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NIcons.TravelExplore, null, tint = cs.primary)
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onCancel) { Text(L.t("لغو", "Cancel")) }
            }
            Row(Modifier.semantics { contentDescription = title }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until steps) {
                    val f = when { i < current -> 1f; i == current -> fraction ?: 0.3f; else -> 0f }
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(cs.primaryContainer)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(cs.primary))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- banner

enum class BannerKind { WARN, BAD, INFO }

/** A one-line notice with its action (update, national internet, kill switch); slides in and out. */
@Composable
fun Banner(visible: Boolean, kind: BannerKind, icon: ImageVector, text: String, action: String?, onAction: () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(visible, enter = expandVertically(motion.spec(Motion.Medium)) + fadeIn(motion.spec(Motion.Medium)),
        exit = shrinkVertically(motion.spec(200)) + fadeOut(motion.spec(200))) {
        val st = MaterialTheme.status
        val cs = MaterialTheme.colorScheme
        val dark = isDarkSurface()
        val (bg, fg) = when (kind) {
            BannerKind.WARN -> st.warnContainer to if (dark) st.warnText else Color(0xFF5C3700)
            BannerKind.BAD -> cs.errorContainer to cs.onErrorContainer
            BannerKind.INFO -> cs.primaryContainer to cs.onPrimaryContainer
        }
        val (btnBg, btnFg) = when (kind) {
            BannerKind.WARN -> st.warn to Color(0xFF2B1A00)
            BannerKind.BAD -> cs.error to cs.onError
            BannerKind.INFO -> cs.primary to cs.onPrimary
        }
        Surface(shape = RoundedCornerShape(18.dp), color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null)
                Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (action != null) androidx.compose.material3.Button(onClick = onAction, modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = btnBg, contentColor = btnFg)) {
                    Text(action, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- navigation rail

class NavEntry(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** The window's side rail: logo, then 64×36 pills with 28dp icons; labels below unless the window is narrow. */
@Composable
fun NetinoRail(items: List<NavEntry>, selected: Int, narrow: Boolean, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    Column(Modifier.width(if (narrow) 72.dp else 88.dp).fillMaxHeight().background(cs.surfaceContainerLowest.takeIf { isDarkSurface() } ?: cs.surfaceContainer)
        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(painterResource("netino.png"), "Netino", Modifier.padding(bottom = 12.dp).size(40.dp).clip(RoundedCornerShape(10.dp)))
        items.forEachIndexed { i, e ->
            val on = i == selected
            val pill by animateColorAsState(if (on) cs.primaryContainer else Color.Transparent, motion.spec(Motion.Medium))
            Column(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on; if (narrow) contentDescription = e.label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(64.dp, 36.dp).clip(CircleShape).background(pill), contentAlignment = Alignment.Center) {
                    Icon(if (on) e.selectedIcon else e.icon, null, Modifier.size(28.dp), tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant)
                }
                if (!narrow) Text(e.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Page title (24sp semibold). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp), fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { heading() })
