package com.netino.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.service.SearchProgress
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    modifier: Modifier,
    onToggle: () -> Unit,
    onPick: (Server) -> Unit,
    onFastest: () -> Unit,
    onAddServer: () -> Unit,
) {
    val state by VpnController.state.collectAsStateWithLifecycle()
    val traffic by VpnController.traffic.collectAsStateWithLifecycle()
    val search by VpnController.search.collectAsStateWithLifecycle()
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val selected = servers.firstOrNull { it.id == settings.selectedServerId } ?: servers.firstOrNull()
    var picker by remember { mutableStateOf(false) }

    val accent by animateColorAsState(
        when (state) {
            is VpnState.Connected -> Good
            is VpnState.Connecting -> Warn
            is VpnState.Error -> Bad
            VpnState.Disconnected -> MaterialTheme.colorScheme.outline
        }, tween(600), label = "accent",
    )

    Box(modifier) {
        // Soft ambient glow behind the button, tinted by connection state
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.36f), radius = size.width * 0.85f),
                radius = size.width * 0.85f, center = Offset(size.width / 2, size.height * 0.36f),
            )
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(38.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }

            if (servers.isEmpty()) {
                EmptyState(onAddServer)
                return@Column
            }

            Spacer(Modifier.weight(0.8f))
            ConnectButton(state, accent, onToggle)
            Spacer(Modifier.height(22.dp))

            // ---- status ----
            val statusText = stringResource(
                when (state) {
                    is VpnState.Connected -> R.string.status_connected
                    is VpnState.Connecting -> R.string.status_connecting
                    is VpnState.Error -> R.string.status_error
                    VpnState.Disconnected -> R.string.status_disconnected
                },
            )
            Text(statusText, style = MaterialTheme.typography.headlineSmall, color = accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val since = (state as? VpnState.Connected)?.since
            if (since != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                Text(formatDuration(now - since), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.tap_to_connect), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- live speed ----
            AnimatedVisibility(state is VpnState.Connected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = 18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile(stringResource(R.string.download), formatSpeed(traffic.downBps), Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ArrowDownward, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        }
                        StatTile(stringResource(R.string.upload), formatSpeed(traffic.upBps), Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ArrowUpward, null, tint = BrandGreenDeep, modifier = Modifier.size(16.dp))
                        }
                    }
                    AreaChart(traffic.history.map { it.first + it.second }, Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))

            // ---- server card ----
            Surface(
                onClick = { picker = true },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    selected?.let { ServerAvatar(it.protocol, 48) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.current_server), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(selected?.name ?: stringResource(R.string.no_server_yet), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    selected?.let { PingPill(it) }
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, stringResource(R.string.change_server), Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            FastestButton(search, onFastest)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (picker) ServerPickerSheet(
        selectedId = selected?.id,
        onPick = { picker = false; onPick(it) },
        onDismiss = { picker = false },
    )
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(170.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.add_first_server)) }
    }
}

@Composable
private fun FastestButton(search: SearchProgress?, onFastest: () -> Unit) {
    if (search == null) {
        FilledTonalButton(onClick = onFastest, modifier = Modifier.fillMaxWidth().height(58.dp), shape = MaterialTheme.shapes.large) {
            Icon(Icons.Outlined.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.fastest_server))
        }
        return
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (search.stage == 1) stringResource(R.string.fastest_stage1, search.done, search.total)
                    else stringResource(R.string.fastest_stage2, search.total),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (search.total == 0) 0f else (search.done.toFloat() / search.total) * 0.5f + if (search.stage == 2) 0.5f else 0f },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
            }
            IconButton(onClick = { VpnController.cancelSearch() }) { Icon(Icons.Outlined.Close, stringResource(R.string.stop)) }
        }
    }
}

/** Big round power button: sweep-gradient ring spins while connecting, glows when connected. */
@Composable
private fun ConnectButton(state: VpnState, accent: Color, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val connecting = state is VpnState.Connecting
    val connected = state is VpnState.Connected
    val t = rememberInfiniteTransition(label = "btn")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(1f, 1.05f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breathe")
    val on = connected || connecting
    val desc = stringResource(if (on) R.string.cd_disconnect else R.string.cd_connect)
    val stateDesc = stringResource(if (on) R.string.state_on else R.string.state_off)

    Box(
        Modifier
            .size(236.dp)
            .scale(if (connected) breathe else 1f)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                role = Role.Switch,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = desc; stateDescription = stateDesc },
        contentAlignment = Alignment.Center,
    ) {
        // outer halo
        Box(Modifier.size(236.dp).clip(CircleShape).background(accent.copy(alpha = if (on) 0.14f else 0.07f)))
        // gradient ring
        Canvas(Modifier.size(196.dp).rotate(if (connecting) spin else 0f)) {
            val w = 10.dp.toPx()
            drawArc(
                brush = if (on) BrandSweep else Brush.linearGradient(listOf(accent.copy(alpha = 0.5f), accent.copy(alpha = 0.5f))),
                startAngle = 0f, sweepAngle = if (connecting) 270f else 360f, useCenter = false,
                style = Stroke(width = w), topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            )
        }
        // core
        Box(
            Modifier.size(156.dp).clip(CircleShape)
                .background(if (connected) BrandGradient else Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHigh))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(70.dp),
                tint = if (connected) Color.White else accent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerSheet(selectedId: String?, onPick: (Server) -> Unit, onDismiss: () -> Unit) {
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    val connectedId = (state as? VpnState.Connected)?.server?.id
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(stringResource(R.string.choose_server), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            val list = servers.filter { query.isBlank() || it.name.contains(query, true) }
                .sortedBy { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { s ->
                    ServerItem(s, selected = s.id == selectedId, connected = s.id == connectedId, onClick = { onPick(s) })
                }
            }
        }
    }
}
