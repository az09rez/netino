package com.netino.vpn.core

import android.util.Base64
import com.netino.vpn.data.PingKind
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Netino's own small service (a Cloudflare Worker, tools/worker), shared by the Android and Windows apps:
 *  - the free list on Netino's own domain (a mirror of the GitHub one, which some networks filter), ordered for
 *    the requester's ISP by what worked there for others
 *  - anonymous results: which free configs got through, sent once a day if the user agreed. A config is sent as a
 *    short hash of the link without its name, and the ISP is read by the service from the connection; nothing else
 *  - problem reports: the network report, sent to the developer instead of copied by hand
 * Its address is published next to the free list (api.txt on the configs branch), so it can change without an
 * update of the apps; until it exists everything here quietly does nothing.
 */
object NetinoApi {

    private val DISCOVERY = listOf(
        "https://raw.githubusercontent.com/az09rez/netino/configs/api.txt",
        "https://cdn.jsdelivr.net/gh/az09rez/netino@configs/api.txt",
    )
    private const val KEY_BASE = "api_base"
    private const val KEY_PENDING = "api_pending"
    private const val KEY_SENT = "api_sent"
    private const val REPORT_EVERY_MS = 24 * 3_600_000L

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    /** The service's address (https://...), or null while there is none. */
    val base: String? get() = Repository.readExtra(KEY_BASE)?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") }

    /** The free list from the service (null without one). */
    fun subUrl(): String? = base?.let { "$it/sub" }

    /** Looks up the service's current address (every few hours); quietly keeps the old one if the lookup fails. */
    suspend fun discover() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - (Repository.readExtra("api_checked")?.toLongOrNull() ?: 0) < 6 * 3_600_000L) return@withContext
        Repository.writeExtra("api_checked", now.toString())
        for (u in DISCOVERY) {
            val text = runCatching {
                http.newCall(Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()?.trim() ?: continue
            if (text.isEmpty()) { Repository.writeExtra(KEY_BASE, ""); return@withContext }
            if (text.startsWith("https://") && text.length < 200 && '\n' !in text) { Repository.writeExtra(KEY_BASE, text); return@withContext }
        }
    }

    // ---------------- anonymous results of the free list ----------------

    /**
     * A config's identity without its name (the free list renames its configs on every update): the link up to the
     * name, or for vmess its fields except "ps", sorted. The service computes the same from its list.
     */
    fun configKey(link: String): String {
        val l = link.trim()
        val canonical = if (l.startsWith("vmess://", true)) runCatching {
            val raw = l.substring(8).substringBefore('#')
            val o = Json.parseToJsonElement(String(Base64.decode(raw, Base64.DEFAULT))).jsonObject
            "vmess|" + o.entries.filter { it.key != "ps" }.sortedBy { it.key }
                .joinToString("&") { (k, v) -> "$k=" + ((v as? JsonPrimitive)?.content ?: v.toString()) }
        }.getOrDefault(l) else l.substringBefore('#')
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    /** Real-delay results of free-list configs, kept until the next daily report. */
    fun record(server: Server, ms: Long, kind: PingKind) {
        if (kind != PingKind.REAL || server.subscriptionId != Repository.BUILTIN_SUB_ID) return
        if (Repository.settings.value.shareResults != true || base == null) return
        val key = configKey(server.link.ifBlank { return })
        val pending = pending().toMutableMap()
        // ok count, fail count, best delay
        val (ok, fail, best) = pending[key] ?: Triple(0, 0, 0L)
        pending[key] = if (ms > 0) Triple(ok + 1, fail, if (best == 0L) ms else minOf(best, ms)) else Triple(ok, fail + 1, best)
        // Bounded: the most recent configs win
        Repository.writeExtra(KEY_PENDING, buildJsonObject {
            pending.entries.toList().takeLast(200).forEach { (k, v) -> put(k, "${v.first},${v.second},${v.third}") }
        }.toString())
    }

    private fun pending(): Map<String, Triple<Int, Int, Long>> = runCatching {
        Json.parseToJsonElement(Repository.readExtra(KEY_PENDING) ?: "{}").jsonObject.mapValues { (_, v) ->
            v.jsonPrimitive.content.split(',').let { Triple(it[0].toInt(), it[1].toInt(), it[2].toLong()) }
        }
    }.getOrDefault(emptyMap())

    /**
     * Sends what was collected, at most once a day, directly (the service reads the ISP from the connection, so it
     * must not go through the VPN: the Android app is outside its own tunnel). [net]: "wifi" / "cell" as a hint.
     */
    suspend fun reportResults(net: String) = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext
        if (Repository.settings.value.shareResults != true) return@withContext
        val last = Repository.readExtra(KEY_SENT)?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < REPORT_EVERY_MS) return@withContext
        val p = pending()
        if (p.isEmpty()) return@withContext
        val body = buildJsonObject {
            put("net", net)
            putJsonArray("results") {
                p.forEach { (k, v) -> addJsonObject { put("h", k); put("ok", v.first); put("fail", v.second); put("ms", v.third) } }
            }
        }.toString()
        runCatching {
            http.newCall(Request.Builder().url("$b/results").post(body.toRequestBody("application/json".toMediaType())).build())
                .execute().use { check(it.isSuccessful) { "HTTP ${it.code}" } }
            Repository.writeExtra(KEY_PENDING, "{}")
            Repository.writeExtra(KEY_SENT, System.currentTimeMillis().toString())
        }
    }

    // ---------------- problem reports ----------------

    /** Sends [report] (the network report) to the developer, with an optional note and a way to reach the user. */
    suspend fun sendIssue(report: String, note: String, contact: String, app: String): Result<Unit> = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext Result.failure(IllegalStateException("no service"))
        val body = buildJsonObject {
            put("app", app); put("note", note.take(2000)); put("contact", contact.take(200)); put("report", report.take(60_000))
        }.toString()
        var last: Throwable? = null
        // Directly, then through the tunnel (the service's domain may be filtered while connected elsewhere)
        for (c in listOfNotNull(http, LocalRoutes.tunnel()?.let { LocalRoutes.via(http, it) })) {
            val r = runCatching {
                c.newCall(Request.Builder().url("$b/issue").post(body.toRequestBody("application/json".toMediaType())).build())
                    .execute().use { check(it.isSuccessful) { "HTTP ${it.code}" } }
            }
            if (r.isSuccess) return@withContext r
            last = r.exceptionOrNull()
        }
        Result.failure(last ?: IllegalStateException("failed"))
    }
}
