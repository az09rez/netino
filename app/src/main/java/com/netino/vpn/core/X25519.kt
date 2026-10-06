package com.netino.vpn.core

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Curve25519 Diffie-Hellman (RFC 7748), for WireGuard / WARP keys. Android only ships X25519 from
 * API 33, the app supports 26+. Keys are made once per WARP account, so a plain BigInteger
 * Montgomery ladder is fast enough.
 */
object X25519 {

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    /** New random private key, clamped. */
    fun privateKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it); clamp(it) }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    private fun clamp(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
    }

    private fun decodeLE(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun encodeLE(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val k = scalar.copyOf(); clamp(k)
        val kn = decodeLE(k)
        val u = point.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = decodeLE(u).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (kn.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).mod(P).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        return encodeLE(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }
}
