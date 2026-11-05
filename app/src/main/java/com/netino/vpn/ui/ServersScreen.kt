package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
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

/** How a section's header reads: its icon, the line under the title, and how much of the subscription is used. */
private class SectionLook(val icon: ImageVector, val meta: String, val warn: Boolean, val expired: Boolean, val used: Float?)

private val SectionShape = RoundedCornerShape(24.dp)
private val TopShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val BottomShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

@Composable
fun ServersScreen(modifier: Modifier, onPick: (Server) -> Unit, onAdd: () -> Unit, onConnect: (Server) -> Unit = onPick) {
    val ctx = LocalContext.current
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val groups by Repository.groups.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    val connectedId = (state as? VpnState.Connected)?.server?.id
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val sort = settings.serverSort
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var untested by remember { mutableStateOf(emptySet<String>()) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var refreshing by remember { mutableIntStateOf(0) }
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }
    var subInterval by remember { mutableStateOf<Subscription?>(null) }
    var groupsFor by remember { mutableStateOf<Server?>(null) }
    var qrFor by remember { mutableStateOf<Server?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ServerGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<ServerGroup?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var testMenu by remember { mutableStateOf(false) }

    // A deleted server is only hidden for a few seconds, so "undo" can bring it back untouched
    // (deleting for real also unregisters WARP accounts, which can't be undone).
    var pending by remember { mutableStateOf<Server?>(null) }
    fun commitDelete() { pending?.let { Repository.deleteServer(it.id) }; pending = null }
    fun delete(s: Server) { commitDelete(); pending = s }
    LaunchedEffect(pending) { if (pending != null) { delay(5000); commitDelete() } }
    DisposableEffect(Unit) { onDispose { pending?.let { Repository.deleteServer(it.id) } } }

    fun test(real: Boolean, list: List<Server> = Repository.servers.value) {
        testJob?.cancel()
        progress = 0 to list.size
        untested = list.map { it.id }.toSet()
        testJob = scope.launch {
            try {
                Pinger.measureAll(list, Repository.settings.value, real,
                    onProgress = { d, t -> progress = d to t },
                    onResult = {
                        Repository.setPing(it.server.id, it.ms, it.kind); VpnController.reportTestError(it)
                        untested = untested - it.server.id
                    })
            } finally {
                Repository.saveServers()
                progress = null
                untested = emptySet()
            }
        }
    }

    fun List<Server>.shown(): List<Server> = filter { it.id != pending?.id }
        .filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
        .let { l ->
            when (sort) {
                ServerSort.DEFAULT -> l
                ServerSort.PING -> l.sortedWith(compareBy<Server>({ pingRank(it) }, { it.lastPingMs }))
                ServerSort.NAME -> l.sortedBy { it.name.lowercase() }
            }
        }

    // The user's own servers first, then WARP, then one section per subscription, then custom groups
    val manualTitle = stringResource(R.string.manual_servers)
    val builtinTitle = stringResource(R.string.builtin_sub)
    val sections = listOf(Section("manual", manualTitle, servers.filter { it.subscriptionId == null })) +
        Section(com.netino.vpn.core.Warp.SUB, stringResource(R.string.warp_section), servers.filter { it.subscriptionId == com.netino.vpn.core.Warp.SUB }) +
        subs.filter { !it.builtIn }.map { sub -> Section(sub.id, sub.name, servers.filter { it.subscriptionId == sub.id }, sub = sub) } +
        subs.filter { it.builtIn }.map { sub -> Section(sub.id, builtinTitle, servers.filter { it.subscriptionId == sub.id }, sub = sub) } +
        groups.map { Section("g:${it.id}", it.name, Repository.groupServers(it, servers), group = it) }

    Box(modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar: the title, or a search box that grows from the icons to where the title was ----
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
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
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp).focusRequester(focus),
                        )
                    } else {
                        Text(stringResource(R.string.servers), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, stringResource(R.string.search), tint = cs.onSurfaceVariant)
                }
                Box {
                    val sortName = stringResource(sortLabel(sort))
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort_cd, sortName), tint = cs.onSurfaceVariant)
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, shape = MaterialTheme.shapes.medium) {
                        ServerSort.entries.forEach { s ->
                            DropdownMenuItem({ Text(stringResource(sortLabel(s))) }, { sortMenu = false; Repository.updateSettings { it.copy(serverSort = s) } },
                                leadingIcon = { RadioButton(s == sort, onClick = null) })
                        }
                        HorizontalDivider()
                        MenuItem(R.string.new_group, Icons.Outlined.CreateNewFolder) { sortMenu = false; newGroup = true }
                        DropdownMenuItem({ Text(stringResource(R.string.update_subs)) }, enabled = refreshing == 0, onClick = {
                            sortMenu = false
                            scope.launch { refreshing++; Repository.refreshAllSubscriptions(); refreshing-- }
                        }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                    }
                }
                Box {
                    val testing = progress != null
                    IconButton(
                        onClick = { if (testing) testJob?.cancel() else testMenu = true },
                        modifier = Modifier.semantics { stateDescription = if (testing) ctx.getString(R.string.testing_now) else "" },
                        colors = if (testing) IconButtonDefaults.iconButtonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)
                        else IconButtonDefaults.iconButtonColors(contentColor = cs.onSurfaceVariant),
                    ) { Icon(Icons.Outlined.NetworkCheck, stringResource(if (testing) R.string.stop else R.string.test_all)) }
                    DropdownMenu(testMenu, onDismissRequest = { testMenu = false }, shape = MaterialTheme.shapes.medium) {
                        MenuItem(R.string.test_real, Icons.Outlined.Speed) { testMenu = false; test(true) }
                        MenuItem(R.string.test_quick, Icons.Outlined.NetworkPing) { testMenu = false; test(false) }
                    }
                }
                Surface(onClick = onAdd, shape = RoundedCornerShape(16.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
                    modifier = Modifier.padding(start = 4.dp).size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, stringResource(R.string.add_server)) }
                }
            }
            progress?.let { (d, t) ->
                LinearProgressIndicator(
                    progress = { if (t == 0) 0f else d.toFloat() / t },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp)
                        .semantics { contentDescription = ctx.getString(R.string.testing_progress, d, t) },
                    color = cs.primary, trackColor = cs.primaryContainer, gapSize = 0.dp, drawStopIndicator = {},
                )
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (pending != null) 88.dp else 16.dp),
            ) {
                if (servers.isEmpty()) item {
                    Text(stringResource(R.string.empty_servers), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurfaceVariant)
                }
                var first = true
                for (sec in sections) {
                    val shown = sec.servers.shown()
                    if ((sec.key == "manual" || sec.key == com.netino.vpn.core.Warp.SUB) && sec.servers.isEmpty()) continue
                    if (query.isNotBlank() && shown.isEmpty()) continue
                    // Saved in settings, so a closed section stays closed across tabs and restarts
                    val open = query.isNotBlank() || sec.key !in settings.collapsed
                    val withRows = open && shown.isNotEmpty()
                    val top = if (first) 0.dp else 12.dp
                    first = false
                    item(key = "h_${sec.key}") {
                        SectionHeader(
                            sec, look = sectionLook(sec), count = sec.servers.size,
                            containsConnected = sec.servers.any { it.id == connectedId },
                            expanded = open, shape = if (withRows) TopShape else SectionShape,
                            refreshing = refreshing > 0 && sec.sub != null,
                            modifier = Modifier.padding(top = top).animateItem(),
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
                            onRefresh = sec.sub?.let { s -> { scope.launch {
                                refreshing++
                                Repository.refreshSubscription(s.id).onFailure { Toast.makeText(ctx, subscriptionMessage(ctx, it), Toast.LENGTH_LONG).show() }
                                refreshing--
                            }; Unit } },
                            onInterval = sec.sub?.let { s -> { subInterval = s } },
                            onRename = sec.group?.let { g -> { renaming = g } },
                            onDelete = sec.group?.let { g -> { groupToDelete = g } }
                                ?: sec.sub?.takeIf { !it.builtIn }?.let { s -> { subToDelete = s } },
                        )
                    }
                    if (withRows) itemsIndexed(shown, key = { _, s -> "${sec.key}/${s.id}" }) { i, s ->
                        val last = i == shown.lastIndex
                        val rowShape = if (last) BottomShape else RectangleShape
                        SwipeRow(onDelete = { delete(s) }, shape = rowShape, modifier = Modifier.animateItem()) {
                            Column {
                                if (i == 0) HorizontalDivider(color = cs.outlineVariant)
                                ServerItem(
                                    s, selected = s.id == settings.selectedServerId, connected = s.id == connectedId,
                                    onClick = { onPick(s) },
                                    onConnect = { onConnect(s) },
                                    onCopy = { copy(ctx, Repository.serverLink(s)) },
                                    onShare = {
                                        if (s.wgConf != null) ConfigFiles.shareWireGuard(ctx, s)
                                        else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, Repository.serverLink(s)), null))
                                    },
                                    onQr = { qrFor = s },
                                    onDelete = { delete(s) },
                                    onGroups = { groupsFor = s },
                                    onRemoveFromGroup = sec.group?.let { g -> { Repository.setInGroup(g.id, s.id, false) } },
                                    testing = s.id in untested,
                                    shape = rowShape, container = cs.surfaceContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- "deleted · undo" ----
        AnimatedVisibility(pending != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }) {
            var last by remember { mutableStateOf("") }
            pending?.let { last = it.name }
            Surface(shape = RoundedCornerShape(14.dp), color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                Row(Modifier.heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.server_deleted, last), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { pending = null }, colors = ButtonDefaults.textButtonColors(contentColor = cs.inversePrimary)) {
                        Text(stringResource(R.string.undo), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

// ---------------- dialogs ----------------
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
    qrFor?.let { s -> QrDialog(s, onDismiss = { qrFor = null }) }
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

/** A server row that can be swiped toward the start to delete it (with "undo" on the snackbar). */
@Composable
private fun SwipeRow(onDelete: () -> Unit, shape: Shape, modifier: Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state, modifier = modifier.clip(shape),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onError)
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
            }
        },
    ) { content() }
}

@Composable
private fun sectionLook(sec: Section): SectionLook {
    val sub = sec.sub
    val n = localDigits("${sec.servers.size}")
    return when {
        sec.group != null -> SectionLook(Icons.Outlined.Folder, stringResource(R.string.sec_group_meta, n), false, false, null)
        sec.key == "manual" -> SectionLook(Icons.Outlined.Person, stringResource(R.string.n_servers_s, n), false, false, null)
        sec.key == com.netino.vpn.core.Warp.SUB -> SectionLook(Icons.Outlined.Bolt, stringResource(R.string.sec_warp_meta), false, false, null)
        sub == null -> SectionLook(Icons.Outlined.Dns, stringResource(R.string.n_servers_s, n), false, false, null)
        sub.builtIn -> {
            val updated = if (sub.lastUpdated > 0) DateUtils.getRelativeTimeSpanString(sub.lastUpdated, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString() else null
            SectionLook(Icons.Outlined.Lock, listOfNotNull(stringResource(R.string.sec_builtin_meta), stringResource(R.string.n_configs, n),
                updated?.let { stringResource(R.string.sec_updated, localDigits(it)) }).joinToString(" · "), false, false, null)
        }
        else -> {
            val parts = mutableListOf<String>()
            var used: Float? = null
            if (sub.total > 0) {
                parts += stringResource(R.string.sec_used_of, localDigits(formatBytes(sub.used.coerceAtMost(sub.total))), localDigits(formatBytes(sub.total)))
                used = (sub.used.toDouble() / sub.total).toFloat().coerceIn(0f, 1f)
            }
            val msLeft = sub.expire * 1000 - System.currentTimeMillis()
            val expired = (sub.expire > 0 && msLeft <= 0) || (used != null && used >= 1f)
            if (sub.expire > 0 && msLeft > 0) {
                val days = (msLeft / 86_400_000L).toInt()
                parts += pluralStringResource(R.plurals.sub_days_left, days, days).let { localDigits(it) }
            }
            if (expired) { parts.clear(); parts += stringResource(R.string.sec_expired) }
            if (parts.isEmpty()) {
                parts += stringResource(R.string.n_servers_s, n)
                if (sub.updateHours > 0) parts += intervalLabel(sub.updateHours)
            }
            val warn = !expired && ((used ?: 0f) >= 0.8f || (sub.expire > 0 && msLeft < 3 * 86_400_000L))
            SectionLook(if (expired) Icons.Outlined.CloudOff else Icons.Outlined.CloudSync, parts.joinToString(" · "), warn, expired,
                if (expired) 1f else used)
        }
    }
}

/** A section's header: tap to open or close it; its menu tests, refreshes or deletes it. */
@Composable
private fun SectionHeader(
    sec: Section, look: SectionLook, count: Int, containsConnected: Boolean, expanded: Boolean, shape: Shape, refreshing: Boolean,
    modifier: Modifier, onToggle: () -> Unit, onTest: () -> Unit, onRefresh: (() -> Unit)?, onInterval: (() -> Unit)?,
    onRename: (() -> Unit)?, onDelete: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    val tone = when { look.expired -> cs.error; look.warn -> st.warnText; else -> null }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, LocalMotion.current.spec(Motion.Medium), label = "chevron")
    var menu by remember { mutableStateOf(false) }
    val expandedLabel = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    Surface(onClick = onToggle, shape = shape, color = cs.surfaceContainer,
        modifier = modifier.fillMaxWidth().semantics { stateDescription = expandedLabel }) {
        Column {
            Row(Modifier.heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(look.icon, null, Modifier.size(28.dp), tint = tone ?: cs.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sec.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (containsConnected) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(st.good))
                        }
                    }
                    Text(look.meta, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        fontWeight = if (tone != null) FontWeight.SemiBold else FontWeight.Normal, color = tone ?: cs.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (refreshing && onRefresh != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(localDigits("$count"), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cs.onSurfaceVariant)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(rotation), tint = cs.onSurfaceVariant)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.section_options, sec.title), tint = cs.onSurfaceVariant) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium) {
                        MenuItem(R.string.test_real, Icons.Outlined.Speed) { menu = false; onTest() }
                        onRefresh?.let { MenuItem(R.string.refresh, Icons.Outlined.Refresh) { menu = false; it() } }
                        onInterval?.let { MenuItem(R.string.auto_update, Icons.Outlined.Schedule) { menu = false; it() } }
                        onRename?.let { MenuItem(R.string.rename_group, Icons.Outlined.DriveFileRenameOutline) { menu = false; it() } }
                        onDelete?.let {
                            DropdownMenuItem({ Text(stringResource(R.string.delete), color = cs.error) }, { menu = false; it() },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = cs.error) })
                        }
                    }
                }
            }
            look.used?.let { f ->
                LinearProgressIndicator(
                    progress = { f },
                    color = if (look.expired) cs.error else if (look.warn) st.warn else cs.primary,
                    trackColor = cs.outlineVariant, gapSize = 0.dp, drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).height(6.dp).clip(CircleShape),
                )
            }
        }
    }
}

private fun sortLabel(s: ServerSort) = when (s) {
    ServerSort.DEFAULT -> R.string.sort_default; ServerSort.PING -> R.string.sort_ping; ServerSort.NAME -> R.string.sort_name
}

@Composable
private fun MenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) =
    DropdownMenuItem({ Text(stringResource(label)) }, onClick, leadingIcon = { Icon(icon, null) })

@Composable
private fun intervalLabel(hours: Int): String =
    if (hours == 0) stringResource(R.string.update_off) else pluralStringResource(R.plurals.every_n_hours, hours, hours)

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("config", text).apply {
        // Mark as sensitive so Android 13+ doesn't show the secret in the clipboard preview
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}
