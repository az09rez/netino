package com.netino.vpn.service

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import com.netino.vpn.R
import com.netino.vpn.data.Repository
import com.netino.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Opt-in: connects the VPN while one of the chosen apps (e.g. Telegram, Instagram) is in the foreground,
 * and disconnects 2 minutes after leaving them if it was this service that connected. Reads only the
 * name of the foreground app (usage access) every few seconds, on the device; nothing leaves the phone.
 */
class AppWatchService : Service() {

    companion object {
        private const val CHANNEL = "auto_connect"
        private const val ID = 2

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** Starts or stops the watcher to match the settings (call from the foreground). */
        fun sync(context: Context) {
            val want = Repository.settings.value.autoConnectApps.isNotEmpty() && hasUsageAccess(context)
            val intent = Intent(context, AppWatchService::class.java)
            runCatching { if (want) ContextCompat.startForegroundService(context, intent) else context.stopService(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.auto_connect_apps), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_netino)
            .setContentTitle(getString(R.string.auto_connect_watching))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        }
        if (watchJob?.isActive != true) watchJob = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        val usm = getSystemService(UsageStatsManager::class.java)
        var foreground: String? = null
        var startedByUs = false
        var leftAt = 0L
        while (scope.isActive) {
            val apps = Repository.settings.value.autoConnectApps
            if (apps.isEmpty()) { stopSelf(); return }
            // Last "moved to foreground" event of the past minute
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED || e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foreground = e.packageName
            }
            val inWatched = foreground in apps
            when {
                inWatched && !VpnController.isActive && VpnService.prepare(this) == null -> {
                    VpnController.connect()
                    startedByUs = true
                    leftAt = 0
                }
                inWatched -> leftAt = 0
                startedByUs && VpnController.isActive -> {
                    if (leftAt == 0L) leftAt = now
                    if (now - leftAt > 120_000) { VpnController.disconnect(); startedByUs = false; leftAt = 0 }
                }
                !VpnController.isActive -> startedByUs = false
            }
            delay(3000)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
