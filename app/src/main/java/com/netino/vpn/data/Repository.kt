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

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _usage = MutableStateFlow<List<DailyUsage>>(emptyList())
    val usage: StateFlow<List<DailyUsage>> = _usage.asStateFlow()

    fun init(context: Context) {
        store = SecureStore(context.applicationContext)
        store.read("servers")?.let { _servers.value = runCatching { json.decodeFromString<List<Server>>(it) }.getOrDefault(emptyList()) }
        store.read("subs")?.let { _subs.value = runCatching { json.decodeFromString<List<Subscription>>(it) }.getOrDefault(emptyList()) }
        store.read("settings")?.let { _settings.value = runCatching { json.decodeFromString<AppSettings>(it) }.getOrDefault(AppSettings()) }
        store.read("usage")?.let { _usage.value = runCatching { json.decodeFromString<List<DailyUsage>>(it) }.getOrDefault(emptyList()) }
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

    fun deleteServer(id: String) { _servers.update { l -> l.filterNot { it.id == id } }; saveServers() }

    fun select(id: String) = updateSettings { it.copy(selectedServerId = id) }

    /** In-memory only (called many times in parallel during a test); call [saveServers] once afterwards. */
    fun setPing(id: String, ms: Long, kind: PingKind) {
        _servers.update { l -> l.map { if (it.id == id) it.copy(lastPingMs = ms, pingKind = kind) else it } }
    }

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

    suspend fun addSubscription(name: String, url: String): Result<Int> {
        if (!url.startsWith("https://")) return Result.failure(IllegalArgumentException("https only"))
        val sub = Subscription(name = name.ifBlank { url.substringAfter("://").substringBefore('/') }, url = url)
        _subs.update { it + sub }
        store.write("subs", json.encodeToString(_subs.value))
        return refreshSubscription(sub.id).onFailure { deleteSubscription(sub.id) }
    }

    suspend fun refreshSubscription(id: String): Result<Int> = withContext(Dispatchers.IO) {
        val sub = _subs.value.firstOrNull { it.id == id } ?: return@withContext Result.failure(NoSuchElementException())
        runCatching {
            // Generic client UA so panels return the standard base64 link list. No device info is sent.
            val req = Request.Builder().url(sub.url).header("User-Agent", "v2rayNG/1.10").build()
            val body = http.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.body?.string().orEmpty()
            }
            val parsed = LinkParser.parseMany(body, sub.id)
            check(parsed.isNotEmpty()) { "empty" }
            _servers.update { l -> l.filterNot { it.subscriptionId == sub.id } + parsed }
            saveServers()
            _subs.update { l -> l.map { if (it.id == id) it.copy(lastUpdated = System.currentTimeMillis()) else it } }
            store.write("subs", json.encodeToString(_subs.value))
            if (selectedServer() == null || _servers.value.none { it.id == _settings.value.selectedServerId }) {
                select(parsed.first().id)
            }
            parsed.size
        }
    }

    suspend fun refreshAllSubscriptions() = _subs.value.forEach { refreshSubscription(it.id) }

    fun deleteSubscription(id: String) {
        _subs.update { l -> l.filterNot { it.id == id } }
        _servers.update { l -> l.filterNot { it.subscriptionId == id } }
        store.write("subs", json.encodeToString(_subs.value))
        saveServers()
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
        _servers.value = emptyList(); _subs.value = emptyList()
        _settings.value = AppSettings(); _usage.value = emptyList()
    }
}
