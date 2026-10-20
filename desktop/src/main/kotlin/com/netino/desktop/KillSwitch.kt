package com.netino.desktop

import com.netino.vpn.core.XrayCore

/**
 * Kill switch for the full tunnel: Windows Firewall blocks every connection out except Xray's own, Netino's,
 * those from the tunnel's address (what programs send into the tunnel) and the local network. If the tunnel
 * drops, nothing leaves outside it until Netino reconnects or the user disconnects. The firewall's previous
 * default is saved and put back on disconnect, and at the next start if Netino was killed meanwhile.
 */
object KillSwitch {
    private const val GROUP = "Netino Kill Switch"
    /** The tunnel's own addresses (its "gateway" in the TUN settings): stable across reconnects, unlike the adapter. */
    const val TUN_V4 = "172.19.0.1"
    const val TUN_V6 = "fdfe:dcba:9876::1"

    val active get() = Store.read("killswitch") != null

    /** Needs administrator rights (the full tunnel has them). */
    fun enable() {
        if (!Win.isWindows || active) return
        val q = Win::psQuote
        val out = Win.powershell("""
            ${'$'}g = ${q(GROUP)}
            Get-NetFirewallRule -Group ${'$'}g -ErrorAction SilentlyContinue | Remove-NetFirewallRule
            New-NetFirewallRule -Group ${'$'}g -DisplayName 'Netino: Xray' -Direction Outbound -Action Allow -Program ${q(XrayCore.exePath)} | Out-Null
            ${Win.selfExe?.let { "New-NetFirewallRule -Group \$g -DisplayName 'Netino' -Direction Outbound -Action Allow -Program ${q(it)} | Out-Null" }.orEmpty()}
            New-NetFirewallRule -Group ${'$'}g -DisplayName 'Netino: tunnel' -Direction Outbound -Action Allow -LocalAddress $TUN_V4,$TUN_V6 | Out-Null
            New-NetFirewallRule -Group ${'$'}g -DisplayName 'Netino: local network' -Direction Outbound -Action Allow -RemoteAddress LocalSubnet,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,fe80::/10,ff00::/8,224.0.0.0/4 | Out-Null
            New-NetFirewallRule -Group ${'$'}g -DisplayName 'Netino: DHCP' -Direction Outbound -Action Allow -Protocol UDP -LocalPort 68,546 | Out-Null
            Get-NetFirewallProfile | ForEach-Object { "SAVED " + ${'$'}_.Name + "=" + ${'$'}_.DefaultOutboundAction + "=" + ${'$'}_.Enabled }
        """.trimIndent())
        val saved = out.lines().filter { it.startsWith("SAVED ") }.map { it.removePrefix("SAVED ").trim() }
        // Saved before the switch, so a crash between the two still restores
        Store.write("killswitch", saved.joinToString("\n"))
        Win.powershell("Set-NetFirewallProfile -All -DefaultOutboundAction Block")
        if (saved.any { it.endsWith("=False") }) Controller.log(L.t("فایروال ویندوز روی بعضی شبکه‌ها خاموش است؛ قطع امن آنجا کار نمی‌کند",
            "Windows Firewall is off for some networks; the kill switch can't work there"))
    }

    /** Puts the firewall back as it was. */
    fun disable() {
        if (!Win.isWindows) return
        val saved = Store.read("killswitch") ?: return
        val restore = saved.lines().mapNotNull { line ->
            val parts = line.split('=')
            if (parts.size < 2) null
            else "Set-NetFirewallProfile -Name ${Win.psQuote(parts[0])} -DefaultOutboundAction ${Win.psQuote(parts[1])}"
        }.ifEmpty { listOf("Set-NetFirewallProfile -All -DefaultOutboundAction NotConfigured") }
        Win.powershell((restore + "Get-NetFirewallRule -Group ${Win.psQuote(GROUP)} -ErrorAction SilentlyContinue | Remove-NetFirewallRule").joinToString("\n"))
        Store.delete("killswitch")
    }
}
