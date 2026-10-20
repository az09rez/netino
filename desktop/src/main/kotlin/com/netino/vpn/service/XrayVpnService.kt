package com.netino.vpn.service

import com.netino.vpn.core.HevTunnel

/** What the shared code reads from the Android VPN service; set by the desktop Controller. */
object XrayVpnService {
    /** The running connection's probe inbound (for the app's own requests through the tunnel). */
    @Volatile var probe: HevTunnel.Endpoint? = null
    /** WireGuard keys the connection uses (Cloudflare allows one live session per key). */
    @Volatile var activeKeys: Set<String> = emptySet()
    @Volatile var isRunning: Boolean = false
}
