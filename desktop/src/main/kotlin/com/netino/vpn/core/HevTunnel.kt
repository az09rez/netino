package com.netino.vpn.core

import java.net.ServerSocket
import java.security.SecureRandom

/** On Windows only the local SOCKS endpoints: traffic reaches the core through the system proxy. */
object HevTunnel {

    /** Per-session SOCKS endpoint (random credentials so no other program can use it). */
    data class Endpoint(val port: Int, val user: String, val pass: String)

    fun newEndpoint(): Endpoint {
        val port = ServerSocket(0).use { it.localPort }
        return Endpoint(port, token(), token())
    }

    private fun token(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = SecureRandom()
        return (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
