package com.netino.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.netino.vpn.R
import com.netino.vpn.ui.MainActivity
import com.netino.vpn.ui.formatSpeed

object ConnectionNotifier {
    const val ID = 1
    private const val CHANNEL = "vpn_status"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE   // hide server name on lock screen
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun build(ctx: Context, state: VpnState, t: Traffic): Notification {
        val context = ctx.applicationContext   // localized application resources
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(context, 1, Intent(context, DisconnectReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = when (state) {
            is VpnState.Connected -> context.getString(R.string.notif_connected, state.server.name)
            is VpnState.Connecting -> context.getString(R.string.status_connecting)
            else -> context.getString(R.string.app_name)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_netino)
            .setColor(0xFF1E88E5.toInt())
            .setContentTitle(title)
            .setContentText("↓ ${formatSpeed(t.downBps)}   ↑ ${formatSpeed(t.upBps)}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_netino),
                context.getString(R.string.cd_disconnect), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    fun update(context: Context, state: VpnState, t: Traffic) {
        if (state !is VpnState.Connected) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, state, t)) }
    }
}

/** "Disconnect" button in the notification. */
class DisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = VpnController.disconnect()
}
