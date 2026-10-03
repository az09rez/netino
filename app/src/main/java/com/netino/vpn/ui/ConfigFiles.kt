package com.netino.vpn.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.netino.vpn.R
import com.netino.vpn.core.WireGuardCore
import com.netino.vpn.data.LinkParser
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports config files picked in the file manager or shared into the app:
 * WireGuard `.conf` files (one server each, named after the file), `.zip` archives of them
 * (as exported by the WireGuard app), and text files holding share links.
 */
object ConfigFiles {

    data class Outcome(val added: Int, val amnezia: Int, val invalid: Int)

    private const val MAX_BYTES = 512 * 1024
    private const val MAX_ZIP_ENTRIES = 200

    /** Blocking (file I/O): call from a background dispatcher. */
    fun import(ctx: Context, uris: List<Uri>): Outcome {
        val servers = mutableListOf<Server>()
        var amnezia = 0
        var invalid = 0
        for (uri in uris) {
            val name = displayName(ctx, uri)
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) } }.getOrNull()
            if (bytes == null) { invalid++; continue }
            val entries = if (isZip(bytes)) unzip(bytes) else listOf(name to bytes)
            if (entries.isEmpty()) invalid++
            for ((entryName, data) in entries) {
                val text = data.toString(Charsets.UTF_8).removePrefix("﻿")
                if (text.contains("[Interface]", ignoreCase = true)) {
                    when (WireGuardCore.check(text)) {
                        WireGuardCore.Check.OK ->
                            LinkParser.parseWireGuardConf(text, baseName(entryName))?.let { servers += it } ?: invalid++
                        WireGuardCore.Check.AMNEZIA -> amnezia++
                        WireGuardCore.Check.INVALID -> invalid++
                    }
                } else {
                    val parsed = LinkParser.parseMany(text)
                    if (parsed.isEmpty()) invalid++ else servers += parsed
                }
            }
        }
        return Outcome(Repository.addServers(servers), amnezia, invalid)
    }

    fun message(ctx: Context, o: Outcome): String = buildList {
        add(if (o.added > 0) ctx.getString(R.string.n_added, o.added) else ctx.getString(R.string.none_valid))
        if (o.amnezia > 0) add(ctx.getString(R.string.n_amnezia, o.amnezia))
        if (o.invalid > 0 && o.added > 0) add(ctx.getString(R.string.n_invalid, o.invalid))
    }.joinToString(" • ")

    private fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** "de-frankfurt.conf" -> "de-frankfurt" (empty name -> parser falls back to "WireGuard <host>"). */
    private fun baseName(name: String) = name.substringAfterLast('/').substringBeforeLast('.').trim()

    private fun readLimited(input: InputStream): ByteArray? {
        val out = input.readNBytesCompat(MAX_BYTES + 1)
        return out.takeIf { it.size <= MAX_BYTES }
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> = runCatching {
        val out = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (out.size < MAX_ZIP_ENTRIES) {
                val e = zip.nextEntry ?: break
                val n = e.name
                if (!e.isDirectory && (n.endsWith(".conf", true) || n.endsWith(".txt", true))) {
                    zip.readNBytesCompat(MAX_BYTES + 1).takeIf { it.size <= MAX_BYTES }?.let { out += n to it }
                }
            }
        }
        out
    }.getOrDefault(emptyList())
}
