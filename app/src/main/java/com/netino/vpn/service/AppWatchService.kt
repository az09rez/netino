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
import com.netino.vpn.data.NetKey
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
 * Opt-in automation, all on the device:
 *  - apps: connects while one of the chosen apps (e.g. Telegram, Instagram) is in the foreground, and disconnects
 *    2 minutes after leaving them if it was this service that connected (reads only the foreground app's name)
 *  - network: connects when the phone moves to mobile data, disconnects when it moves to Wi-Fi
 *  - quiet hours: off between two times, and back on afterwards if it was on before
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

        private fun appsActive(context: Context) = Repository.settings.value.autoConnectApps.isNotEmpty() && hasUsageAccess(context)

        private fun rulesActive() = Repository.settings.value.let { it.autoOnMobile || it.autoOffWifi || it.quietEnabled }

        /** Whether [minute] (after midnight) falls in the quiet hours, which may run past midnight. */
        fun inQuiet(from: Int, to: Int, minute: Int) = if (from <= to) minute in from until to else minute >= from || minute < to

        /** Starts or stops the watcher to match the settings (call from the foreground). */
        fun sync(context: Context) {
            val want = appsActive(context) || rulesActive()
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
            .setContentTitle(getString(if (Repository.settings.value.autoConnectApps.isNotEmpty()) R.string.auto_connect_watching else R.string.auto_rules_watching))
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
        var lastNet = NetKey.current
        var quiet = false
        var onBeforeQuiet = false
        while (scope.isActive) {
            val s = Repository.settings.value
            val apps = if (hasUsageAccess(this)) s.autoConnectApps else emptySet()
            if (apps.isEmpty() && !rulesActive()) { stopSelf(); return }
            val now = System.currentTimeMillis()
            val canStart = VpnService.prepare(this) == null

            // ---- quiet hours ----
            val cal = java.util.Calendar.getInstance()
            val nowQuiet = s.quietEnabled && inQuiet(s.quietFrom, s.quietTo, cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE))
            if (nowQuiet && !quiet) {
                onBeforeQuiet = VpnController.isActive
                if (onBeforeQuiet) VpnController.disconnect()
            } else if (!nowQuiet && quiet) {
                if (onBeforeQuiet && !VpnController.isActive && canStart) VpnController.connect()
                onBeforeQuiet = false
            }
            quiet = nowQuiet

            // ---- network: mobile data -> on, Wi-Fi -> off (on the change only, so a manual choice sticks) ----
            val net = NetKey.current
            if (net != lastNet && net != "other") {
                if (!quiet && s.autoOnMobile && net.startsWith("cell") && !VpnController.isActive && canStart) VpnController.connect()
                if (s.autoOffWifi && net == "wifi" && VpnController.isActive) { VpnController.disconnect(); startedByUs = false }
                lastNet = net
            }
            // Only network rules / quiet hours, or the screen is off (no app can come to the foreground):
            // a check every 15 s is plenty and lets the phone sleep in between
            if (quiet || apps.isEmpty() || !ScreenState.on) { delay(15_000); continue }

            // Last "moved to foreground" event of the past minute
            val events = usm.queryEvents(now - 60_000, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED || e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foreground = e.packageName
            }
            val inWatched = foreground in apps
            when {
                inWatched && !VpnController.isActive && canStart -> {
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
