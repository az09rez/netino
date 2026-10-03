package com.netino.vpn.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup file: "NETINO1" | salt(16) | iv(12) | AES-256-GCM(json).
 * The key comes from the password with PBKDF2-HMAC-SHA256 (150 000 rounds), so the file is safe to keep
 * in cloud storage or send to another phone; without the password it can't be read or altered.
 */
object BackupCrypto {

    private val MAGIC = "NETINO1".toByteArray()
    private const val ROUNDS = 150_000

    private fun key(password: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)).encoded,
        "AES",
    )

    fun encrypt(plain: String, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return MAGIC + salt + iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    /** Throws on a wrong password or a damaged / foreign file. */
    fun decrypt(data: ByteArray, password: String): String {
        require(data.size > MAGIC.size + 28 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a Netino backup" }
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = data.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(data, MAGIC.size + 28, data.size - MAGIC.size - 28), Charsets.UTF_8)
    }
}
