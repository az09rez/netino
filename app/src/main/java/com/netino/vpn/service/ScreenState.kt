package com.netino.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * Whether the screen is on, kept from the system's broadcasts (no polling). With the screen off the app does
 * as little as possible: the speed isn't drawn, the notification and widget aren't refreshed, checks are rare.
 */
object ScreenState {
    @Volatile var on = true
        private set

    fun start(context: Context) {
        val app = context.applicationContext
        on = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    on = i.action == Intent.ACTION_SCREEN_ON
                    // Back on: show the current state right away instead of at the next tick
                    if (on) {
                        runCatching { ConnectionNotifier.update(app, VpnController.state.value, VpnController.traffic.value) }
                        runCatching { NetinoWidget.update(app) }
                    }
                }
            }, filter)
        }
    }
}
