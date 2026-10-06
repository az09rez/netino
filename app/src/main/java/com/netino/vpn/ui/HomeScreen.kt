package com.netino.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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
import com.netino.vpn.service.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    modifier: Modifier,
    onToggle: () -> Unit,
    onPick: (Server) -> Unit,
    onFastest: () -> Unit,
    onAddServer: () -> Unit,
    onWarp: () -> Unit,
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
                Spacer(Modifier.weight(1f))
                UpdateBadge()
            }

            // Shared by both one-tap buttons: the popup the user watches while we search
            var waiting by remember { mutableStateOf<Boolean?>(null) }   // false = quick connect, true = WARP
            waiting?.let { w -> SearchDialog(search, state, warp = w, onClose = { waiting = null }) }
            if (servers.isEmpty()) {
                EmptyState(onAddServer, onWarp = { waiting = true; onWarp() })
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
                        val pool = (state as? VpnState.Connected)?.pool ?: 1
                        Text(if (pool > 1) stringResource(R.string.auto_mode, pool) else stringResource(R.string.current_server),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pool > 1) Good else MaterialTheme.colorScheme.onSurfaceVariant)
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
            // Popup while the user waits (not for background auto-switches)
            FastestButton(search, settings.fastestScope) { waiting = false; onFastest() }
            if (search == null) {
                Spacer(Modifier.height(10.dp))
                WarpButton { waiting = true; onWarp() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (picker) ServerPickerSheet(
        scope = settings.fastestScope,
        selectedId = selected?.id,
        onPick = { picker = false; onPick(it) },
        onDismiss = { picker = false },
    )
}

/** "Super-fast connect": WARP, one tap, no server or config needed. */
@Composable
private fun WarpButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White),
    ) {
        Icon(Icons.Outlined.Cloud, null, Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.warp_home), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(stringResource(R.string.warp_home_sub), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** One line describing the running search (quick connect or WARP). */
@Composable
private fun searchLine(search: SearchProgress?, state: VpnState): String = when (search?.stage) {
    null -> if (state is VpnState.Connecting) stringResource(R.string.status_connecting) else stringResource(R.string.fastest_stage1, 0, 0)
    0 -> stringResource(R.string.fastest_stage0)
    1 -> stringResource(R.string.fastest_stage1, search.done, search.total)
    2 -> stringResource(R.string.fastest_stage3, search.done, search.total, search.name.orEmpty())
    VpnController.STAGE_WARP_CHECK -> stringResource(R.string.warp_stage0, search.total)
    VpnController.STAGE_WARP_CHECK + 1 -> stringResource(R.string.warp_stage1)
    VpnController.STAGE_WARP_CHECK + 2 -> stringResource(R.string.warp_stage2, search.done, search.total)
    VpnController.STAGE_WARP_CHECK + 3 -> stringResource(R.string.warp_stage3)
    else -> stringResource(R.string.fastest_connecting, search.total)
}

private fun searchFraction(search: SearchProgress?): Float {
    val f = if (search == null || search.total == 0) 0f else search.done.toFloat() / search.total
    return when (search?.stage) {
        1 -> f * 0.6f; 2 -> 0.6f + f * 0.3f; 3 -> 0.95f
        VpnController.STAGE_WARP_CHECK -> 0.1f; VpnController.STAGE_WARP_CHECK + 1 -> 0.15f
        VpnController.STAGE_WARP_CHECK + 2 -> 0.2f + f * 0.6f; VpnController.STAGE_WARP_CHECK + 3 -> 0.85f
        else -> 0f
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, onWarp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(170.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        WarpButton(onWarp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.add_first_server)) }
    }
}

@Composable
private fun FastestButton(search: SearchProgress?, scopeKey: String, onFastest: () -> Unit) {
    if (search == null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onFastest, modifier = Modifier.weight(1f).heightIn(min = 64.dp), shape = MaterialTheme.shapes.large) {
                Icon(Icons.Outlined.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fastest_server), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ScopePicker(scopeKey)
        }
        return
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    searchLine(search, VpnState.Disconnected),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { searchFraction(search) },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
            }
            IconButton(onClick = { VpnController.cancelSearch() }) { Icon(Icons.Outlined.Close, stringResource(R.string.stop)) }
        }
    }
}

/**
 * Waiting popup for "quick connect": live stage, progress and the server being checked. Closes by itself
 * once connected (or when the search ends); "Hide" keeps it running in the background, "Stop" cancels it.
 */
@Composable
private fun SearchDialog(search: SearchProgress?, state: VpnState, warp: Boolean, onClose: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(search) { if (search != null) started = true }
    // The search sets its progress a moment after the tap; close only after it has run and ended
    LaunchedEffect(search, state) { if (started && search == null && state !is VpnState.Connecting) onClose() }
    AlertDialog(
        onDismissRequest = {},
        icon = { CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp) },
        title = { Text(stringResource(if (warp) R.string.warp_title else R.string.search_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val line = if (warp && search == null && state !is VpnState.Connecting) stringResource(R.string.warp_stage1)
                    else searchLine(search, state)
                Text(line, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { searchFraction(search) },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.search_hide)) } },
        dismissButton = { TextButton(onClick = { VpnController.cancelSearch(); onClose() }) { Text(stringResource(R.string.stop), color = Bad) } },
    )
}

/**
 * Big round power button. The button itself only changes colour; when connected, only the soft
 * green halo around it slowly grows and fades.
 */
@Composable
private fun ConnectButton(state: VpnState, accent: Color, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val connecting = state is VpnState.Connecting
    val connected = state is VpnState.Connected
    val on = connected || connecting
    val desc = stringResource(if (on) R.string.cd_disconnect else R.string.cd_connect)
    val stateDesc = stringResource(if (on) R.string.state_on else R.string.state_off)
    val pulse = rememberInfiniteTransition(label = "halo")
    val haloScale by pulse.animateFloat(0.86f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "haloScale")
    val haloAlpha by pulse.animateFloat(0.10f, 0.26f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "haloAlpha")

    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        // halo (the only moving part)
        Box(
            Modifier.size(250.dp).scale(if (connected) haloScale else 0.92f).clip(CircleShape)
                .background(accent.copy(alpha = if (connected) haloAlpha else if (connecting) 0.14f else 0.07f)),
        )
        Box(
            Modifier.size(200.dp).clip(CircleShape)
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
            // ring
            Canvas(Modifier.size(196.dp)) {
                val w = 10.dp.toPx()
                drawArc(
                    brush = if (connected) BrandSweep else Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.55f))),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
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
                Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(70.dp), tint = if (connected) Color.White else accent)
            }
        }
    }
}

/** Where "connect to fastest" searches: all servers, the user's own, one subscription or one group. */
@Composable
private fun ScopePicker(scopeKey: String) {
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val options = scopeOptions(subs, groups)
    val current = options.firstOrNull { it.first == scopeKey }?.second ?: options.first().second
    Box {
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.height(58.dp).widthIn(max = 150.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FilterList, stringResource(R.string.fastest_scope), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(current, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text(stringResource(R.string.fastest_scope), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        Repository.updateSettings { it.copy(fastestScope = key) }
                        // The server card and its list now show this scope: pick its best server if the current one isn't in it
                        val pool = Repository.fastestCandidates(key)
                        if (pool.none { it.id == Repository.settings.value.selectedServerId }) {
                            pool.minByOrNull { it.score ?: if (it.lastPingMs > 0) it.lastPingMs * 10 else Long.MAX_VALUE }?.let { Repository.select(it.id) }
                        }
                        open = false
                    },
                    leadingIcon = { RadioButton(selected = key == scopeKey, onClick = null) },
                )
            }
        }
    }
}

/** "Update available" next to the app name; downloads, verifies and installs the new version. */
@Composable
private fun UpdateBadge() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf<Updater.Release?>(null) }
    val (label, release) = when (val u = st) {
        is Updater.State.Available -> stringResource(R.string.update_available) to u.release
        is Updater.State.Downloading -> stringResource(R.string.update_downloading, (u.progress * 100).toInt()) to null
        is Updater.State.Ready -> stringResource(R.string.update_install) to u.release
        is Updater.State.Failed -> stringResource(R.string.update_retry) to u.release
        Updater.State.Idle -> return
    }
    Surface(
        onClick = {
            when (val u = st) {
                is Updater.State.Ready -> Updater.install(ctx, u.file)
                else -> ask = release
            }
        },
        enabled = release != null,
        shape = CircleShape, color = Good.copy(alpha = 0.16f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(16.dp), tint = Good)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Good, maxLines = 1)
        }
    }
    ask?.let { r ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.update_title, r.version)) },
            text = { Text(stringResource(R.string.update_body)) },
            confirmButton = {
                TextButton(onClick = { ask = null; scope.launch { Updater.downloadAndInstall(ctx.applicationContext, r) } }) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.later)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerSheet(scope: String, selectedId: String?, onPick: (Server) -> Unit, onDismiss: () -> Unit) {
    val all by Repository.servers.collectAsStateWithLifecycle()
    // Exactly the servers of the subscription / group chosen next to "connect to fastest"
    val servers = remember(all, scope) { Repository.fastestCandidates(scope) }
    val scopeTitle = scopeLabel(scope)
    val state by VpnController.state.collectAsStateWithLifecycle()
    val connectedId = (state as? VpnState.Connected)?.server?.id
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(stringResource(R.string.choose_server), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(scopeTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            val list = servers.filter { query.isBlank() || it.name.contains(query, true) }
                .sortedWith(compareBy<Server>({ it.score ?: Long.MAX_VALUE }, { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE }))
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { s ->
                    ServerItem(s, selected = s.id == selectedId, connected = s.id == connectedId, onClick = { onPick(s) })
                }
            }
        }
    }
}

@Composable
private fun scopeOptions(subs: List<com.netino.vpn.data.Subscription>, groups: List<com.netino.vpn.data.ServerGroup>) = buildList {
    add("all" to stringResource(R.string.scope_all))
    add("manual" to stringResource(R.string.manual_servers))
    subs.forEach { add("sub:${it.id}" to if (it.builtIn) stringResource(R.string.builtin_sub) else it.name) }
    groups.forEach { add("group:${it.id}" to it.name) }
}

@Composable
private fun scopeLabel(scope: String): String {
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    val options = scopeOptions(subs, groups)
    return options.firstOrNull { it.first == scope }?.second ?: options.first().second
}
