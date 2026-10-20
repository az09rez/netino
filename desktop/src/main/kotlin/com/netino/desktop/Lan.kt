package com.netino.desktop

import com.netino.vpn.core.XrayCore
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Base64

/** Sharing the connection with phones and other computers on the same network. */
object Lan {
    /** This computer's addresses on the local network (Wi-Fi, Ethernet, its own hotspot), not the tunnel's. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.displayName.contains("Wintun", true) }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
            .map { it.hostAddress }
            .filter { it != KillSwitch.TUN_V4 }
            .distinct()
    }.getOrDefault(emptyList())

    /** A SOCKS share link (v2rayNG / Netino on the phone import it, and the phone's whole traffic goes through this PC). */
    fun socksLink(address: String): String {
        val s = com.netino.vpn.data.Repository.settings.value
        val auth = if (s.lanAuth && s.lanUser.isNotBlank()) Base64.getEncoder().encodeToString("${s.lanUser}:${s.lanPass}".toByteArray()) + "@" else ""
        return "socks://$auth$address:${Controller.LAN_SOCKS}#Netino%20PC"
    }

    /**
     * Lets other devices reach Xray's shared ports through Windows Firewall (local network only). Needs administrator
     * rights; without them Windows asks the user itself the first time Xray listens.
     */
    fun allowInbound() {
        if (!Win.isWindows || !Win.isAdmin) return
        runCatching {
            Win.powershell("""
                if (-not (Get-NetFirewallRule -Group 'Netino LAN' -ErrorAction SilentlyContinue)) {
                  New-NetFirewallRule -Group 'Netino LAN' -DisplayName 'Netino: share with the local network' -Direction Inbound -Action Allow -Program ${Win.psQuote(XrayCore.exePath)} -RemoteAddress LocalSubnet -Profile Any | Out-Null
                }
            """.trimIndent())
        }.onFailure { Controller.log("LAN firewall rule: ${it.message}") }
    }
}
