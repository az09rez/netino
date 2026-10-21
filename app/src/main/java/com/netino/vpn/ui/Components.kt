package com.netino.vpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.vpn.R
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Protocol
import com.netino.vpn.data.Server

// Shared components of the redesign (docs/design/DESIGN.md, canvas/Components.dc.html).

// ---------------------------------------------------------------- numbers

/** Persian digits (and decimal separator) when the UI is Persian; English keeps Latin digits. */
@Composable
@ReadOnlyComposable
fun localDigits(s: String): String = if (LocalConfiguration.current.locales[0].language == "fa") faDigits(s) else s

fun faDigits(s: String) = buildString(s.length) {
    s.forEach { c -> append(if (c in '0'..'9') '۰' + (c - '0') else if (c == '.') '٫' else c) }
}

/** Dark surfaces (the app's own theme, which can differ from the system's). */
@Composable
@ReadOnlyComposable
fun isDarkSurface() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * A live number (timer, speed): each changed character slides vertically, tabular figures so it doesn't jitter.
 * Always laid out left to right (times and units read that way in both languages).
 */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val motion = LocalMotion.current
    val s = style.copy(fontFeatureSettings = "tnum")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
            text.forEachIndexed { i, c ->
                key(i) {
                    AnimatedContent(c, label = "digit", transitionSpec = {
                        if (motion.instant) EnterTransition.None togetherWith ExitTransition.None
                        else (slideInVertically(motion.spec(Motion.Fast)) { it } + fadeIn(motion.spec(Motion.Fast))) togetherWith
                            (slideOutVertically(motion.spec(Motion.Fast)) { -it } + fadeOut(motion.spec(Motion.Fast)))
                    }) { ch -> Text(ch.toString(), style = s, color = color) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- protocol avatar

private class AvatarColors(val lightBg: Color, val lightFg: Color, val darkBg: Color, val darkFg: Color)

private fun avatarColors(p: Protocol) = when (p) {
    Protocol.VLESS -> AvatarColors(Color(0xFFD6E7FF), Color(0xFF0A3A73), Color(0xFF0D3F78), Color(0xFFD6E7FF))
    Protocol.VMESS -> AvatarColors(Color(0xFFCCF0EE), Color(0xFF0B5550), Color(0xFF0B3A37), Color(0xFFB5EFEA))
    Protocol.TROJAN -> AvatarColors(Color(0xFFEBDDFF), Color(0xFF4A2A8A), Color(0xFF3A2A66), Color(0xFFE3D4FF))
    Protocol.SHADOWSOCKS -> AvatarColors(Color(0xFFFDE2EE), Color(0xFF8A1F4C), Color(0xFF4A1530), Color(0xFFFFC9DF))
    Protocol.HYSTERIA2 -> AvatarColors(Color(0xFFFFE0CC), Color(0xFF8A3B00), Color(0xFF4A2410), Color(0xFFFFD3B5))
    Protocol.WIREGUARD -> AvatarColors(Color(0xFFDDF3C8), Color(0xFF2C5A08), Color(0xFF22420A), Color(0xFFC8F09E))
    Protocol.SOCKS -> AvatarColors(Color(0xFFE6ECF5), Color(0xFF4B5567), Color(0xFF1A2438), Color(0xFF9AA6BA))
}

fun protocolCode(p: Protocol) = when (p) {
    Protocol.VLESS -> "VL"; Protocol.VMESS -> "VM"; Protocol.TROJAN -> "TR"; Protocol.SHADOWSOCKS -> "SS"
    Protocol.HYSTERIA2 -> "H2"; Protocol.WIREGUARD -> "WG"; Protocol.SOCKS -> "SO"
}

/** Rounded square with the protocol's code, in the protocol's colors (44dp in cards, 40dp in rows). */
@Composable
fun ServerAvatar(protocol: Protocol, size: Int = 44) {
    val c = avatarColors(protocol)
    val dark = isDarkSurface()
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(if (dark) c.darkBg else c.lightBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(protocolCode(protocol), color = if (dark) c.darkFg else c.lightFg,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = if (size >= 44) 12.sp else 11.sp), fontWeight = FontWeight.Bold)
    }
}

/** The same square holding an icon (auto mode in the server card). */
@Composable
fun IconAvatar(icon: ImageVector, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
}

// ---------------------------------------------------------------- latency pill

/** Real delay: green ≤ 600 ms, orange ≤ 1500 ms, red above or failed, grey untested (TCP pings use tighter limits). */
@Composable
fun LatencyPill(ms: Long, kind: PingKind = PingKind.REAL, testing: Boolean = false) {
    val motion = LocalMotion.current
    if (testing) {
        // Shimmer placeholder while the test runs (still when loops are off)
        val base = MaterialTheme.colorScheme.onSurfaceVariant
        val shift = if (motion.loops) rememberInfiniteTransition(label = "shimmer")
            .animateFloat(-1f, 2f, infiniteRepeatable(tween(Motion.Shimmer, easing = LinearEasing)), label = "x").value else 0.5f
        Box(Modifier.size(64.dp, 28.dp).clip(CircleShape).background(
            Brush.horizontalGradient(
                0f to base.copy(alpha = 0.18f), (shift - 0.3f).coerceIn(0f, 1f) to base.copy(alpha = 0.18f),
                shift.coerceIn(0f, 1f) to base.copy(alpha = 0.42f), (shift + 0.3f).coerceIn(0f, 1f) to base.copy(alpha = 0.18f),
                1f to base.copy(alpha = 0.18f),
            ),
        ))
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
        ms <= 0L -> stringResource(R.string.ping_failed)
        ms > 1500 -> localDigits("%.1f".format(java.util.Locale.US, ms / 1000.0)) + "s"
        else -> localDigits(ms.toString()) + "ms"
    }
    val bgA by animateColorAsState(bg, motion.spec(200), label = "pillBg")
    val fgA by animateColorAsState(fg, motion.spec(200), label = "pillFg")
    // A new result pops in
    val pop = remember { Animatable(1f) }
    LaunchedEffect(ms) { if (!motion.instant) { pop.snapTo(0.88f); pop.animateTo(1f, motion.spec(200)) } }
    Box(
        Modifier.scale(pop.value).height(28.dp).clip(CircleShape).background(bgA).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fgA, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A server's last result as a latency pill. */
@Composable
fun PingPill(server: Server, testing: Boolean = false) = LatencyPill(server.lastPingMs, server.pingKind, testing)

// ---------------------------------------------------------------- connect button

/** What the big button shows. */
enum class ConnectLook { OFF, CONNECTING, SEARCHING, CONNECTED, ERROR }

/** Sizes: normal 184 / 228 halo, small screens 140 / 176, very large text 124 / 156. */
data class ConnectSize(val button: Int, val halo: Int, val icon: Int) {
    companion object {
        val Normal = ConnectSize(184, 228, 60)
        val Compact = ConnectSize(140, 176, 48)
        val Tiny = ConnectSize(124, 156, 44)
    }
}

/**
 * The connect button: a circle with a halo behind it. Off = grey; connecting / searching = warning container with
 * a warning ring and a halo that grows and fades; connected = brand gradient with a calm green wave; error = error
 * container, error ring, error icon and a short shake. While searching, a 4-part ring around it shows
 * [searchProgress] (0..1 over all steps). Looping animations stop when [LocalMotion] says so.
 */
@Composable
fun ConnectButton(
    look: ConnectLook, size: ConnectSize, label: String, stateLabel: String,
    searchProgress: Float = 0f, enabled: Boolean = true, onClick: () -> Unit,
) {
    val motion = LocalMotion.current
    val st = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val busy = look == ConnectLook.CONNECTING || look == ConnectLook.SEARCHING
    val fill by animateColorAsState(when (look) {
        ConnectLook.OFF -> cs.surfaceContainerHigh
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warnContainer
        ConnectLook.CONNECTED -> BrandBlue
        ConnectLook.ERROR -> st.badContainer
    }, motion.spec(Motion.Medium), label = "fill")
    val ring by animateColorAsState(when (look) {
        ConnectLook.OFF -> cs.surfaceContainerHigh
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warn
        ConnectLook.CONNECTED -> Color.Transparent
        ConnectLook.ERROR -> st.bad
    }, motion.spec(Motion.Medium), label = "ring")
    val iconColor by animateColorAsState(when (look) {
        ConnectLook.OFF -> cs.onSurfaceVariant
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warnText
        ConnectLook.CONNECTED -> Color.White
        ConnectLook.ERROR -> cs.error
    }, motion.spec(Motion.Medium), label = "icon")
    val gradient by animateFloatAsState(if (look == ConnectLook.CONNECTED) 1f else 0f, motion.spec(Motion.Medium), label = "gradient")
    val haloColor = when (look) {
        ConnectLook.CONNECTED -> st.good.copy(alpha = if (isDarkSurface()) 0.16f else 0.16f)
        ConnectLook.CONNECTING, ConnectLook.SEARCHING -> st.warn.copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    // Halo: grows and fades in a loop (connecting 1.6 s, connected 2.4 s after one 0.6 s wave); still without loops
    val loop = motion.loops && (busy || look == ConnectLook.CONNECTED)
    val wave = remember { Animatable(0f) }
    LaunchedEffect(look, loop) {
        if (!loop) { wave.snapTo(0.35f); return@LaunchedEffect }
        if (look == ConnectLook.CONNECTED) { wave.snapTo(0f); wave.animateTo(1f, tween(Motion.Wave, easing = Motion.EmphasizedDecelerate)) }
        val period = if (look == ConnectLook.CONNECTED) Motion.HaloConnected else Motion.HaloConnecting
        while (true) { wave.snapTo(0f); wave.animateTo(1f, tween(period, easing = LinearEasing)) }
    }
    // Press: 0.96 and a tap; state change: icon 0.92 → 1; error: shake ±6dp × 3 and an error tap
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, motion.spec(Motion.Press), label = "press")
    val iconPop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(look) {
        if (motion.instant) return@LaunchedEffect
        iconPop.snapTo(0.92f); iconPop.animateTo(1f, motion.spec(Motion.Medium))
    }
    LaunchedEffect(look) {
        if (look != ConnectLook.ERROR) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        if (!motion.instant) shake.animateTo(0f, keyframes {
            durationMillis = Motion.Slow
            0f at 0; 6f at 50; -6f at 100; 6f at 150; -6f at 200; 6f at 250; 0f at 300
        })
    }
    val searchFrac by animateFloatAsState(searchProgress.coerceIn(0f, 1f), motion.spec(Motion.Slow), label = "search")
    val track = cs.primaryContainer
    val primary = cs.primary

    Box(Modifier.size(size.halo.dp).offset(x = shake.value.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.halo.dp)) {
            if (haloColor != Color.Transparent) {
                // scale 0.9 → 1.12, opacity full → 0 (still: a soft fixed halo)
                val t = wave.value
                val scale = 0.9f + 0.22f * t
                val alpha = if (loop) (1f - t) else 1f
                drawCircle(haloColor.copy(alpha = haloColor.alpha * alpha), radius = this.size.minDimension / 2 * scale)
            }
            if (look == ConnectLook.SEARCHING) {
                val strokePx = 6.dp.toPx()
                val inset = 4.dp.toPx()
                val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                val gap = 8f
                for (i in 0 until 4) {
                    drawArc(track, -90f + i * 90f + gap / 2, 90f - gap, false, Offset(inset, inset), arcSize, style = Stroke(strokePx))
                }
                if (searchFrac > 0f) drawArc(primary, -90f, 360f * searchFrac, false, Offset(inset, inset), arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(size.button.dp).scale(press).clip(CircleShape)
                .background(fill)
                .background(BrandGradient, alpha = gradient)
                .border(BorderStroke(3.dp, ring), CircleShape)
                .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .semantics { contentDescription = label; stateDescription = stateLabel; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (look == ConnectLook.ERROR) Icons.Outlined.ErrorOutline else Icons.Filled.PowerSettingsNew, null,
                Modifier.size(size.icon.dp).scale(iconPop.value), tint = iconColor,
            )
        }
    }
}

// ---------------------------------------------------------------- current-server card

/** The server in use: avatar, caption ("current server" / "auto mode"), name, latency, and a chevron to change it. */
@Composable
fun ServerCard(caption: String, name: String, avatar: @Composable () -> Unit, pill: @Composable () -> Unit, onClick: () -> Unit, captionColor: Color = Color.Unspecified) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            avatar()
            Column(Modifier.weight(1f)) {
                Text(caption, style = MaterialTheme.typography.bodySmall,
                    color = if (captionColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else captionColor, maxLines = 1)
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            pill()
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- server row

fun protocolLine(s: Server): String = buildString {
    append(s.protocol.label)
    s.xray?.security?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it.uppercase()) }
    s.xray?.network?.takeIf { it.isNotBlank() && it != "tcp" }?.let { append(" • ").append(it) }
}

/**
 * A server in a list: avatar, name, "protocol · address", latency pill, a check on the selected one and a menu.
 * The selected row is tinted with primaryContainer.
 */
@Composable
fun ServerItem(
    s: Server, selected: Boolean, connected: Boolean,
    onClick: () -> Unit,
    onCopy: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onQr: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    onGroups: (() -> Unit)? = null, onRemoveFromGroup: (() -> Unit)? = null, testing: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium, container: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    onConnect: (() -> Unit)? = null, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f).compositeOver(container) else container,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerAvatar(s.protocol, 40)
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.isInsecure) {
                        Icon(Icons.Outlined.LockOpen, stringResource(R.string.not_secure), tint = MaterialTheme.status.warnText, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    // Addresses read left to right in both languages
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(subtitle ?: "${s.protocol.label.lowercase()} · ${s.address}:${s.port}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            PingPill(s, testing)
            if (selected || connected) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.connected_badge),
                tint = if (connected) MaterialTheme.status.good else MaterialTheme.colorScheme.primary)
            if (onDelete != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium) {
                    onConnect?.let { DropdownMenuItem({ Text(stringResource(R.string.cd_connect)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, null) }) }
                    onCopy?.let { DropdownMenuItem({ Text(stringResource(R.string.copy_link)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }) }
                    onShare?.let { DropdownMenuItem({ Text(stringResource(R.string.share)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.Share, null) }) }
                    onQr?.let { DropdownMenuItem({ Text(stringResource(R.string.qr_code)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.QrCode2, null) }) }
                    onGroups?.let { DropdownMenuItem({ Text(stringResource(R.string.add_to_group)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }) }
                    onRemoveFromGroup?.let { DropdownMenuItem({ Text(stringResource(R.string.remove_from_group)) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.FolderOff, null) }) }
                    DropdownMenuItem({ Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            } else if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
        }
    }
}

// ---------------------------------------------------------------- banner

enum class BannerKind { WARN, BAD, INFO }

/** A one-line notice with its action (national internet, kill switch, update); slides in and out. */
@Composable
fun Banner(visible: Boolean, kind: BannerKind, icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible,
        enter = expandVertically(motion.spec(Motion.Medium)) + fadeIn(motion.spec(Motion.Medium)),
        exit = shrinkVertically(motion.spec(200)) + fadeOut(motion.spec(200)),
    ) {
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
        Surface(shape = MaterialTheme.shapes.medium, color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null)
                Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 40.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = btnBg, contentColor = btnFg),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                    Text(action, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- progress card

/**
 * A running search: "Step x of y · what's happening", one bar per step (done, current, next) with their names, and
 * Cancel. [fraction] null = the current step has no measurable progress (indeterminate bar).
 */
@Composable
fun ProgressCard(title: String, steps: List<String>, current: Int, fraction: Float?, onCancel: () -> Unit) {
    val motion = LocalMotion.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.TravelExplore, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    val m = Modifier.weight(1f).height(6.dp).clip(CircleShape)
                    if (i == current && fraction == null) LinearProgressIndicator(m, trackColor = MaterialTheme.colorScheme.primaryContainer)
                    else {
                        val target = when { i < current -> 1f; i == current -> fraction ?: 0f; else -> 0f }
                        val p by animateFloatAsState(target, motion.spec(Motion.Slow), label = "step$i")
                        LinearProgressIndicator(progress = { p }, modifier = m, trackColor = MaterialTheme.colorScheme.primaryContainer,
                            gapSize = 0.dp, drawStopIndicator = {})
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, label ->
                    Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), maxLines = 2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- bottom bar

class NavEntry(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Bottom navigation: 28dp icons in a 72×36 pill that grows from the center, 14sp labels. Its text never grows past
 * 130% (large system fonts would push it off screen).
 */
@Composable
fun NetinoNavBar(items: List<NavEntry>, selected: Int, onSelect: (Int) -> Unit) {
    val motion = LocalMotion.current
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f))) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val pill by animateFloatAsState(if (on) 1f else 0f, motion.spec(200), label = "pill")
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                        .selectable(on, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(72.dp, 36.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size((72 * pill).dp, 36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                        Icon(if (on) item.selectedIcon else item.icon, null, Modifier.size(28.dp),
                            tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(item.label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- older shared pieces (settings, stats, tools)

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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

/**
 * Speed chart, always left → right: download as a filled line in [color], upload (if given) as a thin dashed line in
 * [upColor].
 */
@Composable
fun AreaChart(values: List<Long>, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary, up: List<Long>? = null,
              upColor: Color = MaterialTheme.colorScheme.secondary) {
    Canvas(modifier) {
        val all = values + up.orEmpty()
        if (values.size < 2) return@Canvas
        val max = all.max().coerceAtLeast(1).toFloat()
        fun line(v: List<Long>): Path {
            val step = size.width / (v.size - 1)
            fun pt(i: Int) = Offset(i * step, size.height * (1 - v[i] / max) * 0.92f + size.height * 0.04f)
            return Path().apply {
                moveTo(pt(0).x, pt(0).y)
                for (i in 1 until v.size) {
                    val p0 = pt(i - 1); val p1 = pt(i); val mx = (p0.x + p1.x) / 2
                    cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                }
            }
        }
        val l = line(values)
        val area = Path().apply { addPath(l); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(l, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        if (up != null && up.size >= 2) drawPath(line(up), upColor,
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { it(); Spacer(Modifier.width(4.dp)) }
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

/** Main actions (WARP, fastest): at least this tall, growing with large text. */
val ActionHeight = 56.dp
