package com.netino.vpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Single source of truth. All persistence goes through [SecureStore] (encrypted). */
object Repository {

    private lateinit var store: SecureStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subs.asStateFlow()

    private val _groups = MutableStateFlow<List<ServerGroup>>(emptyList())
    val groups: StateFlow<List<ServerGroup>> = _groups.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _usage = MutableStateFlow<List<DailyUsage>>(emptyList())
    val usage: StateFlow<List<DailyUsage>> = _usage.asStateFlow()

    /** Receives non-fatal storage errors (shown in the live report). */
    var onStorageError: (String) -> Unit = {}

    fun init(context: Context) {
        store = SecureStore(context.applicationContext).also { it.onError = { msg -> onStorageError(msg) } }
        store.read("servers")?.let { _servers.value = runCatching { json.decodeFromString<List<Server>>(it) }.getOrDefault(emptyList()) }
        store.read("subs")?.let { _subs.value = runCatching { json.decodeFromString<List<Subscription>>(it) }.getOrDefault(emptyList()) }
        store.read("settings")?.let { _settings.value = runCatching { json.decodeFromString<AppSettings>(it) }.getOrDefault(AppSettings()) }
        store.read("usage")?.let { _usage.value = runCatching { json.decodeFromString<List<DailyUsage>>(it) }.getOrDefault(emptyList()) }
        store.read("groups")?.let { _groups.value = runCatching { json.decodeFromString<List<ServerGroup>>(it) }.getOrDefault(emptyList()) }
        ensureBuiltIn()
    }

    // ---------- servers ----------
    fun selectedServer(): Server? = _servers.value.firstOrNull { it.id == _settings.value.selectedServerId }
        ?: _servers.value.firstOrNull()

    fun addServers(list: List<Server>): Int {
        if (list.isEmpty()) return 0
        _servers.update { it + list }
        if (_settings.value.selectedServerId == null) select(list.first().id)
        saveServers()
        return list.size
    }

    fun deleteServer(id: String) {
        _servers.update { l -> l.filterNot { it.id == id } }
        saveServers()
        if (_groups.value.any { id in it.serverIds }) {
            _groups.update { gs -> gs.map { it.copy(serverIds = it.serverIds - id) } }
            saveGroups()
        }
    }

    fun select(id: String) = updateSettings { it.copy(selectedServerId = id) }

    /** In-memory only (called many times in parallel during a test); call [saveServers] once afterwards. */
    fun setPing(id: String, ms: Long, kind: PingKind) {
        _servers.update { l ->
            l.map {
                if (it.id != id) it
                else it.copy(
                    lastPingMs = ms, pingKind = kind,
                    history = if (kind == PingKind.REAL) (it.history + (if (ms > 0) ms.toInt() else -1)).takeLast(HISTORY) else it.history,
                )
            }
        }
    }

    private const val HISTORY = 10

    fun serverLink(s: Server): String = s.link.ifBlank { s.wgConf.orEmpty() }

    fun saveServers() = store.write("servers", json.encodeToString(_servers.value))

    // ---------- subscriptions ----------
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .build()
    }

    /**
     * Netino's own list, produced by .github/workflows/configs.yml (re-tested every 2 h, new configs daily).
     * Mirrors are tried in order in case one host is filtered.
     */
    const val BUILTIN_SUB_ID = "netino-builtin"
    private val BUILTIN_URLS = listOf(
        "https://raw.githubusercontent.com/az09rez/netino/configs/sub.txt",
        "https://cdn.jsdelivr.net/gh/az09rez/netino@configs/sub.txt",
    )

    private fun ensureBuiltIn() {
        if (_subs.value.any { it.id == BUILTIN_SUB_ID }) return
        _subs.update { listOf(Subscription(id = BUILTIN_SUB_ID, name = "Netino", url = BUILTIN_URLS.first(), updateHours = 1, builtIn = true)) + it }
        saveSubs()
    }

    private fun saveSubs() = store.write("subs", json.encodeToString(_subs.value))

    suspend fun addSubscription(name: String, url: String): Result<Int> {
        if (!url.startsWith("https://")) return Result.failure(IllegalArgumentException("https only"))
        val sub = Subscription(name = name.ifBlank { url.substringAfter("://").substringBefore('/') }, url = url)
        _subs.update { it + sub }
        saveSubs()
        return refreshSubscription(sub.id).onFailure { deleteSubscription(sub.id) }
    }

    private class Fetched(val body: String, val userInfo: String?, val title: String?)

    private fun fetch(url: String): Fetched {
        // Generic client UA so panels return the standard base64 link list. No device info is sent.
        val req = Request.Builder().url(url).header("User-Agent", "v2rayNG/1.10").build()
        return http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            Fetched(r.body?.string().orEmpty(), r.header("subscription-userinfo"), r.header("profile-title"))
        }
    }

    /** `upload=1; download=2; total=3; expire=4` -> map. */
    private fun parseUserInfo(h: String): Map<String, Long> = h.split(';').mapNotNull { part ->
        val k = part.substringBefore('=').trim().lowercase()
        part.substringAfter('=', "").trim().toDoubleOrNull()?.let { k to it.toLong() }
    }.toMap()

    private fun profileTitle(h: String?): String? {
        val t = h?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.startsWith("base64:")) LinkParser.decodeBase64(t.removePrefix("base64:"))?.trim() else t
    }

    suspend fun refreshSubscription(id: String): Result<Int> = withContext(Dispatchers.IO) {
        val sub = _subs.value.firstOrNull { it.id == id } ?: return@withContext Result.failure(NoSuchElementException())
        runCatching {
            val urls = if (sub.builtIn) BUILTIN_URLS else listOf(sub.url)
            var error: Throwable? = null
            var fetched: Fetched? = null
            for (u in urls) {
                fetched = runCatching { fetch(u) }.onFailure { error = it }.getOrNull()
                if (fetched != null) break
            }
            val f = fetched ?: throw (error ?: IllegalStateException("fetch failed"))
            val parsed = LinkParser.parseMany(f.body, sub.id)
            check(parsed.isNotEmpty()) { "empty" }
            // Same link as before keeps its id and last ping, so sorting and the selection survive an update
            val old = _servers.value.filter { it.subscriptionId == sub.id }.groupBy { serverLink(it) }
                .mapValues { it.value.toMutableList() }.toMutableMap()
            val merged = parsed.map { p ->
                old[serverLink(p)]?.removeFirstOrNull()?.let { o -> p.copy(id = o.id, lastPingMs = o.lastPingMs, pingKind = o.pingKind, history = o.history) } ?: p
            }
            _servers.update { l -> l.filterNot { it.subscriptionId == sub.id } + merged }
            saveServers()
            val info = f.userInfo?.let(::parseUserInfo).orEmpty()
            _subs.update { l ->
                l.map {
                    if (it.id != id) it
                    else it.copy(
                        lastUpdated = System.currentTimeMillis(),
                        upload = info["upload"] ?: 0, download = info["download"] ?: 0,
                        total = info["total"] ?: 0, expire = info["expire"] ?: 0,
                        // A panel title replaces only the automatic host-name label
                        name = profileTitle(f.title)?.takeIf { _ -> !it.builtIn && it.name == it.url.substringAfter("://").substringBefore('/') } ?: it.name,
                    )
                }
            }
            saveSubs()
            if (_servers.value.none { it.id == _settings.value.selectedServerId }) select(merged.first().id)
            merged.size
        }
    }

    suspend fun refreshAllSubscriptions() = _subs.value.forEach { refreshSubscription(it.id) }

    /** Subscriptions whose auto-update period has elapsed (used by the background worker and app start). */
    suspend fun refreshDueSubscriptions() = _subs.value.filter { it.isDue() }.forEach { refreshSubscription(it.id) }

    fun setSubscriptionInterval(id: String, hours: Int) {
        _subs.update { l -> l.map { if (it.id == id) it.copy(updateHours = hours) else it } }
        saveSubs()
    }

    fun deleteSubscription(id: String) {
        if (_subs.value.any { it.id == id && it.builtIn }) return
        _subs.update { l -> l.filterNot { it.id == id } }
        _servers.update { l -> l.filterNot { it.subscriptionId == id } }
        saveSubs()
        saveServers()
    }

    // ---------- custom groups ----------
    private fun saveGroups() = store.write("groups", json.encodeToString(_groups.value))

    fun createGroup(name: String, serverIds: List<String> = emptyList()): ServerGroup {
        val g = ServerGroup(name = name.trim(), serverIds = serverIds.distinct())
        _groups.update { it + g }
        saveGroups()
        return g
    }

    fun renameGroup(id: String, name: String) {
        _groups.update { gs -> gs.map { if (it.id == id) it.copy(name = name.trim()) else it } }
        saveGroups()
    }

    fun deleteGroup(id: String) {
        _groups.update { gs -> gs.filterNot { it.id == id } }
        saveGroups()
    }

    fun setInGroup(groupId: String, serverId: String, member: Boolean) {
        _groups.update { gs ->
            gs.map { g ->
                if (g.id != groupId) g
                else g.copy(serverIds = if (member) (g.serverIds + serverId).distinct() else g.serverIds - serverId)
            }
        }
        saveGroups()
    }

    /** Servers of a group, in the group's order (ids of deleted servers are skipped). */
    fun groupServers(g: ServerGroup, all: List<Server> = _servers.value): List<Server> {
        val byId = all.associateBy { it.id }
        return g.serverIds.mapNotNull { byId[it] }
    }

    /** Display name of a scope key ("all", "manual", "sub:<id>", "group:<id>"), or null for the built-in labels. */
    fun scopeName(scope: String): String? = when {
        scope.startsWith("sub:") -> _subs.value.firstOrNull { it.id == scope.removePrefix("sub:") }?.takeIf { !it.builtIn }?.name
        scope.startsWith("group:") -> _groups.value.firstOrNull { it.id == scope.removePrefix("group:") }?.name
        else -> null
    }

    /** Candidates for "connect to fastest" according to [AppSettings.fastestScope]. */
    fun fastestCandidates(scope: String = _settings.value.fastestScope): List<Server> {
        val all = _servers.value
        return when {
            scope == "manual" -> all.filter { it.subscriptionId == null }
            scope.startsWith("sub:") -> all.filter { it.subscriptionId == scope.removePrefix("sub:") }
            scope.startsWith("group:") -> _groups.value.firstOrNull { it.id == scope.removePrefix("group:") }?.let { groupServers(it, all) }
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: all
    }

    fun toggleCollapsed(key: String) = updateSettings {
        it.copy(collapsed = if (key in it.collapsed) it.collapsed - key else it.collapsed + key)
    }

    // ---------- settings ----------
    fun updateSettings(block: (AppSettings) -> AppSettings) {
        _settings.update(block)
        store.write("settings", json.encodeToString(_settings.value))
    }

    // ---------- usage ----------
    fun addUsage(day: String, rx: Long, tx: Long) {
        _usage.update { list ->
            val existing = list.firstOrNull { it.day == day }
            val updated = if (existing == null) list + DailyUsage(day, rx, tx)
            else list.map { if (it.day == day) it.copy(rx = it.rx + rx, tx = it.tx + tx) else it }
            updated.takeLast(31)
        }
    }

    fun persistUsage() = store.write("usage", json.encodeToString(_usage.value))

    /** Panic button: delete every config, key and statistic. */
    fun wipeEverything() {
        store.wipeAll()
        _servers.value = emptyList(); _subs.value = emptyList(); _groups.value = emptyList()
        _settings.value = AppSettings(); _usage.value = emptyList()
        ensureBuiltIn()
    }
}
