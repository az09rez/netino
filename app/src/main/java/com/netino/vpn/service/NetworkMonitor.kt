package com.netino.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.netino.vpn.data.NetKey

/**
 * Tracks which network the phone is on ("wifi", "cell:<operator>"). Used to keep server scores per
 * network and to reconnect when the phone moves between Wi-Fi and mobile data. The app itself is
 * outside the VPN, so its default network is the real underlying one.
 */
object NetworkMonitor {

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val key = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell:" + operator(app)
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }
                    val previous = NetKey.current
                    NetKey.current = key
                    if (previous != "other" && previous != key) VpnController.onNetworkChanged(previous, key)
                }

                override fun onLost(network: Network) {
                    // Keep the key: the next network's capabilities decide whether it changed
                }
            })
        }
    }

    private fun operator(context: Context): String = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: "mobile"
}
