package com.netino.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server

/** Where "fastest" and the picker look, as on the phone: everything, my servers, a subscription or a group. */
@Composable
private fun scopeOptions(): List<Pair<String, String>> {
    val subs by Repository.subscriptions.collectAsState()
    val groups by Repository.groups.collectAsState()
    return buildList {
        add("all" to L.t("همهٔ سرورها", "All servers"))
        add("manual" to L.t("سرورهای من", "My servers"))
        subs.forEach { add("sub:${it.id}" to if (it.builtIn) L.t("کانفیگ‌های رایگان نتینو", "Netino free configs") else it.name.ifBlank { it.url }) }
        groups.forEach { add("group:${it.id}" to it.name) }
    }
}

private fun Server.rank() = score ?: if (lastPingMs > 0) lastPingMs * 10 else Long.MAX_VALUE

/** Home: the current server (or auto mode), its delay, and the list to pick another from. */
@Composable
fun ServerCard() {
    val state by Controller.state.collectAsState()
    val servers by Repository.servers.collectAsState()
    val settings by Repository.settings.collectAsState()
    val selected = remember(servers, settings.selectedServerId) { Repository.selectedServer() }
    var picker by remember { mutableStateOf(false) }
    val pool = (state as? ConnState.Connected)?.pool ?: 1
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(480.dp).clickable { picker = true },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NIcons.Dns, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (pool > 1) L.t("حالت خودکار: $pool سرور", "Auto mode: $pool servers") else L.t("سرور فعلی", "Current server"),
                    style = MaterialTheme.typography.bodySmall, color = if (pool > 1) Good else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.name ?: L.t("هنوز سروری نیست", "No server yet"), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            selected?.takeIf { it.lastPingMs != -1L }?.let { PingText(it) }
            Spacer(Modifier.width(6.dp))
            Text(L.t("تغییر", "Change"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (picker) ServerPickerDialog(selected?.id, onDismiss = { picker = false }) { s ->
        picker = false
        Repository.select(s.id)
        // Connected (or connecting): switch to it right away
        if (Controller.isActive) Controller.connect(s)
    }
}

@Composable
private fun PingText(s: Server) {
    Text(if (s.lastPingMs > 0) "${s.lastPingMs} ms" else L.t("ناموفق", "failed"),
        style = MaterialTheme.typography.labelMedium, color = pingColor(s.lastPingMs, s.pingKind))
}

/** The servers of the chosen scope, best first, with a search box; picking one selects (and switches to) it. */
@Composable
fun ServerPickerDialog(selectedId: String?, onDismiss: () -> Unit, onPick: (Server) -> Unit) {
    val all by Repository.servers.collectAsState()
    val settings by Repository.settings.collectAsState()
    val state by Controller.state.collectAsState()
    val connectedId = (state as? ConnState.Connected)?.server?.id
    val options = scopeOptions()
    val scope = settings.fastestScope.takeIf { s -> options.any { it.first == s } } ?: "all"
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = remember(all, scope, query) {
        Repository.fastestCandidates(scope).filter { query.isBlank() || it.name.contains(query, true) }.sortedBy { it.rank() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.t("انتخاب سرور", "Choose a server")) },
        text = {
            Column(Modifier.width(560.dp)) {
                // The same scope "fastest" uses
                Box {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.clickable { menu = true }) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(options.first { it.first == scope }.second, style = MaterialTheme.typography.labelLarge)
                            Icon(NIcons.ArrowDropDown, null)
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        options.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { RadioButton(selected = key == scope, onClick = null) },
                                onClick = {
                                    menu = false
                                    Repository.updateSettings { it.copy(fastestScope = key) }
                                    // The current server isn't in the new scope: select its best one
                                    val pool = Repository.fastestCandidates(key)
                                    if (pool.none { it.id == Repository.settings.value.selectedServerId }) pool.minByOrNull { it.rank() }?.let { Repository.select(it.id) }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(L.t("جست‌وجو", "Search")) },
                    leadingIcon = { Icon(NIcons.Search, null) }, shape = CircleShape, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) Text(L.t("سروری در این بخش نیست", "No servers here"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.id }) { s ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = when { s.id == connectedId -> MaterialTheme.colorScheme.secondaryContainer; s.id == selectedId -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainer },
                            modifier = Modifier.fillMaxWidth().clickable { onPick(s) },
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.protocol.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (s.lastPingMs != -1L) PingText(s)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("بستن", "Close")) } },
    )
}

/** Label of the current "fastest" scope, for the button. */
@Composable
fun scopeLabel(): String {
    val settings by Repository.settings.collectAsState()
    val options = scopeOptions()
    return options.firstOrNull { it.first == settings.fastestScope }?.second ?: options.first().second
}
