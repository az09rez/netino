package com.netino.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.desktop.ConnState
import com.netino.desktop.Controller
import com.netino.desktop.L
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server

/** Where "fastest" and the picker look, as on the phone: everything, my servers, a subscription or a group. */
@Composable
internal fun scopeOptions(): List<Pair<String, String>> {
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
    ServerCard(
        caption = if (pool > 1) L.t("حالت خودکار: ${localDigits("$pool")} سرور", "Auto mode: $pool servers") else L.t("سرور فعلی", "Current server"),
        name = selected?.name ?: L.t("هنوز سروری نیست", "No server yet"),
        avatar = { selected?.let { ServerAvatar(it.protocol) } },
        pill = { selected?.let { PingPill(it) } },
        onClick = { picker = true },
        captionColor = if (pool > 1) MaterialTheme.status.goodText else androidx.compose.ui.graphics.Color.Unspecified,
    )
    if (picker) ServerPickerDialog(selected?.id, onDismiss = { picker = false }) { s ->
        picker = false
        Repository.select(s.id)
        // Connected (or connecting): switch to it right away
        if (Controller.isActive) Controller.connect(s)
    }
}

/** Next to "fastest": where it looks (everything, my servers, a subscription or a group). */
@Composable
fun ScopeButton() {
    val settings by Repository.settings.collectAsState()
    val options = scopeOptions()
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.heightIn(min = 52.dp)) {
            Icon(NIcons.ArrowDropDown, L.t("انتخاب محدوده", "Choose where to look"))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { RadioButton(selected = key == settings.fastestScope, onClick = null) },
                    onClick = { menu = false; Repository.updateSettings { it.copy(fastestScope = key) } },
                )
            }
        }
    }
}

/** Servers of a scope chosen with chips, best (or starred) first, with a search box; picking one selects (and switches to) it. */
@Composable
fun ServerPickerDialog(selectedId: String?, onDismiss: () -> Unit, onPick: (Server) -> Unit) {
    val all by Repository.servers.collectAsState()
    val settings by Repository.settings.collectAsState()
    val state by Controller.state.collectAsState()
    val connectedId = (state as? ConnState.Connected)?.server?.id
    val cs = MaterialTheme.colorScheme
    val options = scopeOptions()
    var scope by remember { mutableStateOf(settings.fastestScope.takeIf { s -> options.any { it.first == s } } ?: "all") }
    var query by remember { mutableStateOf("") }
    val favs = settings.favorites
    val list = remember(all, scope, query, favs) {
        Repository.fastestCandidates(scope).filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
            .sortedBy { it.rank() }.sortedBy { if (it.id in favs) 0 else 1 }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(L.t("انتخاب سرور", "Choose a server"), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options, key = { it.first }) { (key, label) ->
                        FilterChip(scope == key, onClick = { scope = key }, label = { Text(label, maxLines = 1) }, shape = RoundedCornerShape(12.dp),
                            leadingIcon = if (scope == key) { { Icon(NIcons.Check, null, Modifier.size(18.dp)) } } else null)
                    }
                }
                SearchField(query, { query = it }, L.t("جست‌وجو در ", "Search in ") + options.first { it.first == scope }.second, Modifier.fillMaxWidth())
                if (list.isEmpty()) Text(L.t("سروری در این بخش نیست", "No servers here"), color = cs.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { s ->
                        val on = s.id == selectedId
                        val starred = s.id in favs
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) cs.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                                .clickable { onPick(s) }.heightIn(min = 60.dp).padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ServerAvatar(s.protocol, 40)
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.protocol.label.lowercase() + " · " + sourceName(s), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                            PingPill(s)
                            Icon(NIcons.CheckCircle, if (s.id == connectedId) L.t("وصل", "Connected") else null, tint = if (s.id == connectedId) MaterialTheme.status.goodText else cs.primary,
                                modifier = Modifier.size(22.dp).alpha(if (on || s.id == connectedId) 1f else 0f))
                            IconButton(onClick = { Repository.toggleFavorite(s.id) }) {
                                Icon(if (starred) NIcons.Star else NIcons.StarOutline,
                                    if (starred) L.t("برداشتن ستاره از ${s.name}", "Unstar ${s.name}") else L.t("ستاره‌دار کردن ${s.name}", "Star ${s.name}"),
                                    tint = if (starred) (if (isDarkSurface()) Color(0xFFFBBF24) else Color(0xFFB7791F)) else cs.onSurfaceVariant)
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

/** Where a server comes from, for the second line of a row. */
@Composable
private fun sourceName(s: Server): String {
    val subs by Repository.subscriptions.collectAsState()
    return when (val id = s.subscriptionId) {
        null -> L.t("سرورهای من", "My servers")
        com.netino.vpn.core.Warp.SUB -> "WARP"
        else -> subs.firstOrNull { it.id == id }?.let { if (it.builtIn) L.t("کانفیگ‌های رایگان نتینو", "Netino free configs") else it.name.ifBlank { it.url } } ?: ""
    }
}

/** Rounded search box (40dp, surfaceContainerHigh). */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(NIcons.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(NIcons.Close, L.t("پاک کردن", "Clear"), Modifier.size(18.dp).clickable { onChange("") }, tint = cs.onSurfaceVariant)
    }
}

/** Label of the current "fastest" scope, for the button. */
@Composable
fun scopeLabel(): String {
    val settings by Repository.settings.collectAsState()
    val options = scopeOptions()
    return options.firstOrNull { it.first == settings.fastestScope }?.second ?: options.first().second
}
