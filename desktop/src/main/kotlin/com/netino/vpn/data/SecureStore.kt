package com.netino.vpn.data

import android.content.Context
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted files (AES-GCM) like on Android. The key is sealed with Windows DPAPI: only this Windows user
 * can open it. Elsewhere (development) the key file is kept as is.
 */
class SecureStore(private val context: Context) {

    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val keyFile get() = File(context.filesDir, "store.key")

    @Volatile private var cached: SecretKeySpec? = null

    private fun key(): SecretKeySpec = cached ?: synchronized(this) {
        cached ?: run {
            val raw = if (keyFile.exists()) unseal(keyFile.readBytes()) else ByteArray(32).also {
                SecureRandom().nextBytes(it)
                keyFile.writeBytes(seal(it))
            }
            SecretKeySpec(raw, "AES").also { cached = it }
        }
    }

    private fun seal(b: ByteArray) = if (windows) Crypt32Util.cryptProtectData(b) else b
    private fun unseal(b: ByteArray) = if (windows) Crypt32Util.cryptUnprotectData(b) else b

    private fun file(name: String) = File(context.noBackupFilesDir, "$name.enc")

    /** Files that exist but could not be decrypted: never overwritten in this session, so data isn't lost. */
    private val unreadable = mutableSetOf<String>()

    @Synchronized
    fun write(name: String, plain: String) {
        if (name in unreadable) return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val tmp = File(file(name).path + ".tmp")
            tmp.writeBytes(sealed)
            // Windows can't rename over an existing file
            java.nio.file.Files.move(tmp.toPath(), file(name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            onError("save $name: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Synchronized
    fun read(name: String): String? {
        val f = file(name)
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            unreadable += name
            onError("read $name failed")
            null
        }
    }

    var onError: (String) -> Unit = {}

    @Synchronized
    fun wipeAll() {
        unreadable.clear()
        context.noBackupFilesDir.listFiles { f -> f.name.endsWith(".enc") }?.forEach { it.delete() }
        keyFile.delete()
        cached = null
    }
}
