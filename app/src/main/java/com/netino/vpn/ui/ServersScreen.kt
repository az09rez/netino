package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.core.Pinger
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.data.ServerGroup
import com.netino.vpn.data.ServerSort
import com.netino.vpn.data.Subscription
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Real delay first (lowest wins), then quick-ping results, then untested, then failed. */
private fun pingRank(s: Server) = when {
    s.lastPingMs > 0 && s.pingKind == PingKind.REAL -> 0
    s.lastPingMs > 0 -> 1
    s.lastPingMs == -1L -> 2
    else -> 3
}

private val UPDATE_HOURS = listOf(0, 1, 6, 12, 24)

/** One collapsible section of the list. */
private class Section(
    val key: String, val title: String, val servers: List<Server>,
    val sub: Subscription? = null, val group: ServerGroup? = null,
)

@Composable
fun ServersScreen(modifier: Modifier, onPick: (Server) -> Unit, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    val connectedId = (state as? VpnState.Connected)?.server?.id
    val scope = rememberCoroutineScope()

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val sort = settings.serverSort
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var refreshing by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<Server?>(null) }
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }
    var subInterval by remember { mutableStateOf<Subscription?>(null) }
    var groupsFor by remember { mutableStateOf<Server?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ServerGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<ServerGroup?>(null) }

    fun test(real: Boolean, list: List<Server> = Repository.servers.value) {
        testJob?.cancel()
        progress = 0 to list.size
        testJob = scope.launch {
            try {
                Pinger.measureAll(list, Repository.settings.value, real,
                    onProgress = { d, t -> progress = d to t },
                    onResult = { Repository.setPing(it.server.id, it.ms, it.kind); VpnController.reportTestError(it) })
            } finally {
                Repository.saveServers()
                progress = null
            }
        }
    }

    fun List<Server>.shown(): List<Server> = filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
        .let { l ->
            when (sort) {
                ServerSort.DEFAULT -> l
                ServerSort.PING -> l.sortedWith(compareBy<Server>({ pingRank(it) }, { it.lastPingMs }))
                ServerSort.NAME -> l.sortedBy { it.name.lowercase() }
            }
        }

    // Custom groups first, then the user's own servers, then one section per subscription
    val manualTitle = stringResource(R.string.manual_servers)
    val builtinTitle = stringResource(R.string.builtin_sub)
    val sections = groups.map { Section("g:${it.id}", it.name, Repository.groupServers(it, servers), group = it) } +
        Section(com.netino.vpn.core.Warp.SUB, stringResource(R.string.warp_section), servers.filter { it.subscriptionId == com.netino.vpn.core.Warp.SUB }) +
        Section("manual", manualTitle, servers.filter { it.subscriptionId == null }) +
        subs.map { sub -> Section(sub.id, if (sub.builtIn) builtinTitle else sub.name, servers.filter { it.subscriptionId == sub.id }, sub = sub) }

    Box(modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar: the title, or a search box that grows from the icons to where the title was ----
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    searching, Modifier.weight(1f), label = "search",
                    transitionSpec = { (fadeIn() + expandHorizontally(expandFrom = Alignment.End)) togetherWith (fadeOut() + shrinkHorizontally()) },
                ) { open ->
                    if (open) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        OutlinedTextField(
                            query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search)) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(stringResource(R.string.servers), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, stringResource(R.string.search))
                }
                IconButton(onClick = { newGroup = true }) { Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.new_group)) }
                IconButton(onClick = { scope.launch { refreshing++; Repository.refreshAllSubscriptions(); refreshing-- } },
                    enabled = refreshing == 0) { Icon(Icons.Outlined.Refresh, stringResource(R.string.update_subs)) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip(onClick = { test(false) }, label = { Text(stringResource(R.string.test_quick)) }, leadingIcon = { Icon(Icons.Outlined.NetworkPing, null) }) }
                item { AssistChip(onClick = { test(true) }, label = { Text(stringResource(R.string.test_real)) }, leadingIcon = { Icon(Icons.Outlined.Speed, null) }) }
                items(ServerSort.entries) { s ->
                    FilterChip(sort == s, onClick = { Repository.updateSettings { it.copy(serverSort = s) } }, label = {
                        Text(stringResource(when (s) {
                            ServerSort.DEFAULT -> R.string.sort_default; ServerSort.PING -> R.string.sort_ping; ServerSort.NAME -> R.string.sort_name
                        }))
                    })
                }
            }
            progress?.let { (d, t) ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.testing_progress, d, t), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape))
                    }
                    IconButton(onClick = { testJob?.cancel() }) { Icon(Icons.Outlined.Close, stringResource(R.string.stop)) }
                }
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (servers.isEmpty()) item {
                    Text(stringResource(R.string.empty_servers), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (sec in sections) {
                    val shown = sec.servers.shown()
                    if ((sec.key == "manual" || sec.key == com.netino.vpn.core.Warp.SUB) && sec.servers.isEmpty()) continue
                    if (query.isNotBlank() && shown.isEmpty()) continue
                    // Saved in settings, so a closed section stays closed across tabs and restarts
                    val open = query.isNotBlank() || sec.key !in settings.collapsed
                    item(key = "h_${sec.key}") {
                        GroupHeader(
                            title = sec.title, sub = sec.sub, isGroup = sec.group != null, count = sec.servers.size,
                            best = sec.servers.filter { it.lastPingMs > 0 }.minByOrNull { it.lastPingMs },
                            containsConnected = sec.servers.any { it.id == connectedId },
                            expanded = open, refreshing = refreshing > 0 && sec.sub != null,
                            onToggle = { Repository.toggleCollapsed(sec.key) },
                            onTest = if (sec.key == com.netino.vpn.core.Warp.SUB) { {
                                // WARP servers are free to make again: the ones that no longer answer are removed
                                scope.launch {
                                    val before = sec.servers.size
                                    val live = VpnController.checkWarp(sec.servers)
                                    Toast.makeText(ctx, ctx.getString(R.string.warp_checked, live.size, before - live.size), Toast.LENGTH_SHORT).show()
                                }
                                Unit
                            } } else { { test(true, sec.servers) } },
                            onRefresh = sec.sub?.let { s -> { scope.launch { refreshing++; Repository.refreshSubscription(s.id); refreshing-- }; Unit } },
                            onInterval = sec.sub?.let { s -> { subInterval = s } },
                            onRename = sec.group?.let { g -> { renaming = g } },
                            onDelete = sec.group?.let { g -> { groupToDelete = g } }
                                ?: sec.sub?.takeIf { !it.builtIn }?.let { s -> { subToDelete = s } },
                        )
                    }
                    if (open) items(shown, key = { "${sec.key}/${it.id}" }) { s ->
                        ServerItem(
                            s, selected = s.id == settings.selectedServerId, connected = s.id == connectedId,
                            onClick = { onPick(s) },
                            onCopy = { copy(ctx, Repository.serverLink(s)) },
                            onShare = {
                                if (s.wgConf != null) ConfigFiles.shareWireGuard(ctx, s)
                                else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, Repository.serverLink(s)), null))
                            },
                            onDelete = { toDelete = s },
                            onGroups = { groupsFor = s },
                            onRemoveFromGroup = sec.group?.let { g -> { Repository.setInGroup(g.id, s.id, false) } },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text(stringResource(R.string.add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    // ---------------- dialogs ----------------
    toDelete?.let { s ->
        ConfirmDelete(stringResource(R.string.delete_server_q, s.name), onDismiss = { toDelete = null }) { Repository.deleteServer(s.id) }
    }
    subToDelete?.let { sub ->
        ConfirmDelete(stringResource(R.string.delete_sub_q, sub.name), onDismiss = { subToDelete = null }) { Repository.deleteSubscription(sub.id) }
    }
    groupToDelete?.let { g ->
        ConfirmDelete(stringResource(R.string.delete_group_q, g.name), onDismiss = { groupToDelete = null }) { Repository.deleteGroup(g.id) }
    }
    subInterval?.let { sub ->
        val current = subs.firstOrNull { it.id == sub.id }?.updateHours ?: sub.updateHours
        AlertDialog(
            onDismissRequest = { subInterval = null },
            title = { Text(stringResource(R.string.auto_update)) },
            text = {
                Column {
                    UPDATE_HOURS.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .clickable { Repository.setSubscriptionInterval(sub.id, h); subInterval = null }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = h == current, onClick = { Repository.setSubscriptionInterval(sub.id, h); subInterval = null })
                            Text(intervalLabel(h))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { subInterval = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    groupsFor?.let { s -> GroupPicker(s, groups, onDismiss = { groupsFor = null }) }
    if (newGroup) NameDialog(stringResource(R.string.new_group), "", onDismiss = { newGroup = false }) { Repository.createGroup(it) }
    renaming?.let { g -> NameDialog(stringResource(R.string.rename_group), g.name, onDismiss = { renaming = null }) { Repository.renameGroup(g.id, it) } }
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.delete)) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.delete), color = Bad) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
)

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.group_name)) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Tick the groups a server belongs to, or create a new group containing it. */
@Composable
private fun GroupPicker(server: Server, groups: List<ServerGroup>, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_group)) },
        text = {
            Column {
                Text(server.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                groups.forEach { g ->
                    val member = server.id in g.serverIds
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable { Repository.setInGroup(g.id, server.id, !member) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(member, onCheckedChange = { Repository.setInGroup(g.id, server.id, it) })
                        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(stringResource(R.string.new_group)) },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) Repository.createGroup(newName, listOf(server.id))
                onDismiss()
            }) { Text(stringResource(R.string.done)) }
        },
    )
}

/** Collapsible panel header for a group, a subscription or the manual servers. */
@Composable
private fun GroupHeader(
    title: String, sub: Subscription?, isGroup: Boolean, count: Int, best: Server?, containsConnected: Boolean, expanded: Boolean,
    refreshing: Boolean, onToggle: () -> Unit, onTest: () -> Unit, onRefresh: (() -> Unit)?, onInterval: (() -> Unit)?,
    onRename: (() -> Unit)?, onDelete: (() -> Unit)?,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            .semantics { stateDescription = if (expanded) "expanded" else "collapsed" },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isGroup) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (containsConnected) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Good))
                    }
                }
                Text(
                    stringResource(R.string.n_servers, count) +
                        (sub?.takeIf { it.updateHours > 0 }?.let { " • " + intervalLabel(it.updateHours) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sub?.takeIf { it.hasUsage }?.let { SubscriptionUsage(it) }
            }
            best?.let { PingPill(it) }
            if (refreshing && onRefresh != null) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.test_real, Icons.Outlined.Speed) { menu = false; onTest() }
                    onRefresh?.let { MenuItem(R.string.refresh, Icons.Outlined.Refresh) { menu = false; it() } }
                    onInterval?.let { MenuItem(R.string.auto_update, Icons.Outlined.Schedule) { menu = false; it() } }
                    onRename?.let { MenuItem(R.string.rename_group, Icons.Outlined.DriveFileRenameOutline) { menu = false; it() } }
                    onDelete?.let {
                        DropdownMenuItem({ Text(stringResource(R.string.delete), color = Bad) }, { menu = false; it() },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Bad) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) =
    DropdownMenuItem({ Text(stringResource(label)) }, onClick, leadingIcon = { Icon(icon, null) })

@Composable
private fun intervalLabel(hours: Int): String =
    if (hours == 0) stringResource(R.string.update_off) else pluralStringResource(R.plurals.every_n_hours, hours, hours)

/** Remaining traffic and days, like v2box: from the panel's subscription-userinfo header. */
@Composable
private fun SubscriptionUsage(sub: Subscription) {
    val parts = mutableListOf<String>()
    var fraction: Float? = null
    if (sub.total > 0) {
        val left = (sub.total - sub.used).coerceAtLeast(0)
        parts += stringResource(R.string.sub_remaining, formatBytes(left), formatBytes(sub.total))
        fraction = (sub.used.toDouble() / sub.total).toFloat().coerceIn(0f, 1f)
    }
    val msLeft = sub.expire * 1000 - System.currentTimeMillis()
    if (sub.expire > 0) {
        val days = (msLeft / 86_400_000L).toInt().coerceAtLeast(0)
        parts += if (msLeft <= 0) stringResource(R.string.sub_expired) else pluralStringResource(R.plurals.sub_days_left, days, days)
    }
    val low = fraction?.let { it >= 0.9f } == true || (sub.expire > 0 && msLeft < 3 * 86_400_000L)
    Text(parts.joinToString(" • "), style = MaterialTheme.typography.bodySmall,
        color = if (low) Bad else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
    fraction?.let { f ->
        LinearProgressIndicator(
            progress = { 1f - f },
            color = if (low) Bad else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp).clip(CircleShape),
        )
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("config", text).apply {
        // Mark as sensitive so Android 13+ doesn't show the secret in the clipboard preview
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}
