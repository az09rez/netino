package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.netino.vpn.data.Subscription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netino.vpn.R
import com.netino.vpn.core.Pinger
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Sort { DEFAULT, PING, NAME }

@Composable
fun ServersScreen(modifier: Modifier, onPick: (Server) -> Unit, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val servers by Repository.servers.collectAsStateWithLifecycle()
    val subs by Repository.subscriptions.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val state by VpnController.state.collectAsStateWithLifecycle()
    val connectedId = (state as? VpnState.Connected)?.server?.id
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.DEFAULT) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var refreshing by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<Server?>(null) }
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }
    // Collapsed panels survive rotation / tab switches
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }

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

    fun List<Server>.sorted(): List<Server> = filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
        .let { l ->
            when (sort) {
                Sort.DEFAULT -> l
                Sort.PING -> l.sortedBy { if (it.lastPingMs > 0) it.lastPingMs else Long.MAX_VALUE }
                Sort.NAME -> l.sortedBy { it.name.lowercase() }
            }
        }

    Box(modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.servers), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (subs.isNotEmpty()) IconButton(onClick = {
                    scope.launch { refreshing++; Repository.refreshAllSubscriptions(); refreshing-- }
                }, enabled = refreshing == 0) { Icon(Icons.Outlined.Refresh, stringResource(R.string.update_subs)) }
            }
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip(onClick = { test(false) }, label = { Text(stringResource(R.string.test_quick)) }, leadingIcon = { Icon(Icons.Outlined.NetworkPing, null) }) }
                item { AssistChip(onClick = { test(true) }, label = { Text(stringResource(R.string.test_real)) }, leadingIcon = { Icon(Icons.Outlined.Speed, null) }) }
                items(Sort.entries) { s ->
                    FilterChip(sort == s, onClick = { sort = s }, label = {
                        Text(stringResource(when (s) { Sort.DEFAULT -> R.string.sort_default; Sort.PING -> R.string.sort_ping; Sort.NAME -> R.string.sort_name }))
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
                // Grouped: manual servers first, then one section per subscription
                val groups = listOf<Pair<String?, List<Server>>>(null to servers.filter { it.subscriptionId == null }) +
                    subs.map { it.id to servers.filter { s -> s.subscriptionId == it.id } }
                for ((subId, group) in groups) {
                    val shown = group.sorted()
                    if (shown.isEmpty() && subId == null) continue
                    val sub = subs.firstOrNull { it.id == subId }
                    val key = subId ?: "manual"
                    // While searching, always show matches
                    val open = query.isNotBlank() || key !in collapsed
                    val showHeader = sub != null || subs.isNotEmpty()
                    if (showHeader) item(key = "h_$key") {
                        GroupHeader(
                            title = sub?.name ?: stringResource(R.string.manual_servers),
                            count = group.size,
                            best = group.filter { it.lastPingMs > 0 }.minByOrNull { it.lastPingMs },
                            containsConnected = group.any { it.id == connectedId },
                            expanded = open,
                            refreshing = refreshing > 0,
                            onToggle = { collapsed = if (key in collapsed) collapsed - key else collapsed + key },
                            onTest = { test(true, group) },
                            onRefresh = sub?.let { s -> { scope.launch { refreshing++; Repository.refreshSubscription(s.id); refreshing-- }; Unit } },
                            onDelete = sub?.let { s -> { subToDelete = s } },
                        )
                    }
                    if (open || !showHeader) items(shown, key = { it.id }) { s ->
                        ServerItem(
                            s, selected = s.id == settings.selectedServerId, connected = s.id == connectedId,
                            onClick = { onPick(s) },
                            onCopy = { copy(ctx, Repository.serverLink(s)) },
                            onShare = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, Repository.serverLink(s)), null))
                            },
                            onDelete = { toDelete = s },
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

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_server_q, s.name)) },
            confirmButton = { TextButton(onClick = { Repository.deleteServer(s.id); toDelete = null }) { Text(stringResource(R.string.delete), color = Bad) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    subToDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { subToDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_sub_q, sub.name)) },
            confirmButton = {
                TextButton(onClick = { Repository.deleteSubscription(sub.id); subToDelete = null }) { Text(stringResource(R.string.delete), color = Bad) }
            },
            dismissButton = { TextButton(onClick = { subToDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Collapsible panel header for a subscription (or the manual servers). */
@Composable
private fun GroupHeader(
    title: String, count: Int, best: Server?, containsConnected: Boolean, expanded: Boolean, refreshing: Boolean,
    onToggle: () -> Unit, onTest: () -> Unit, onRefresh: (() -> Unit)?, onDelete: (() -> Unit)?,
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
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (containsConnected) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Good))
                    }
                }
                Text(stringResource(R.string.n_servers, count), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            best?.let { PingPill(it) }
            if (refreshing && onRefresh != null) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.test_real)) }, { menu = false; onTest() },
                        leadingIcon = { Icon(Icons.Outlined.Speed, null) })
                    onRefresh?.let {
                        DropdownMenuItem({ Text(stringResource(R.string.refresh)) }, { menu = false; it() },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                    }
                    onDelete?.let {
                        DropdownMenuItem({ Text(stringResource(R.string.delete), color = Bad) }, { menu = false; it() },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Bad) })
                    }
                }
            }
        }
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
