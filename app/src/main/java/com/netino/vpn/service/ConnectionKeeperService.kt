package com.netino.vpn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps the process in the foreground while the WireGuard backend is running. */
class ConnectionKeeperService : Service() {

    companion object {
        @Volatile private var running = false
        @Volatile private var stopRequested = false

        fun start(context: Context) {
            stopRequested = false
            ContextCompat.startForegroundService(context, Intent(context, ConnectionKeeperService::class.java))
        }

        /**
         * Stopping a service started with startForegroundService() before it called startForeground()
         * crashes the app ("did not then call startForeground"). If it isn't up yet, it stops itself
         * right after going foreground instead.
         */
        fun stop(context: Context) {
            if (running) runCatching { context.stopService(Intent(context, ConnectionKeeperService::class.java)) }
            else stopRequested = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = ConnectionNotifier.build(this, VpnController.state.value, VpnController.traffic.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ConnectionNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ConnectionNotifier.ID, n)
        }
        running = true
        if (stopRequested) {
            stopRequested = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
