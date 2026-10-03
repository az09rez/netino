package com.netino.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted file storage. Every file is sealed with AES-256-GCM using a non-exportable key
 * held in the Android Keystore (hardware-backed where available). Server credentials,
 * subscription URLs and WireGuard private keys never touch disk in plaintext.
 */
class SecureStore(private val context: Context) {

    private val alias = "amnrah_master_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun file(name: String) = File(context.noBackupFilesDir, "$name.enc")

    /** Files that exist but could not be decrypted: never overwritten in this session, so data isn't lost. */
    private val unreadable = mutableSetOf<String>()

    /**
     * Never throws: the Android Keystore occasionally fails transiently (seen on Samsung devices), and a
     * failed save must not crash the app. Retried once; a failure is reported to [onError].
     */
    @Synchronized
    fun write(name: String, plain: String) {
        if (name in unreadable) return
        repeat(2) { attempt ->
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                val tmp = File(file(name).path + ".tmp")
                tmp.writeBytes(sealed)
                tmp.renameTo(file(name))   // atomic replace
                return
            } catch (e: Exception) {
                if (attempt == 1) onError("save $name: ${e.javaClass.simpleName}: ${e.message}")
                else Thread.sleep(50)
            }
        }
    }

    @Synchronized
    fun read(name: String): String? {
        val f = file(name)
        if (!f.exists()) return null
        repeat(3) {
            try {
                val bytes = f.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            } catch (e: Exception) {
                Thread.sleep(80)
            }
        }
        unreadable += name
        onError("read $name failed")
        return null
    }

    var onError: (String) -> Unit = {}

    @Synchronized
    fun wipeAll() {
        unreadable.clear()
        context.noBackupFilesDir.listFiles { f -> f.name.endsWith(".enc") }?.forEach { it.delete() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}
