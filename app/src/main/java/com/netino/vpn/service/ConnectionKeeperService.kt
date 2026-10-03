package com.netino.vpn.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process in the foreground while the WireGuard backend is running. */
class ConnectionKeeperService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = ConnectionNotifier.build(this, VpnController.state.value, VpnController.traffic.value)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ConnectionNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ConnectionNotifier.ID, n)
        return START_STICKY
    }
}
