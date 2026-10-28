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
import com.netino.vpn.ui.faDigits
import com.netino.vpn.ui.formatBytes

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
        val fa = context.resources.configuration.locales[0].language == "fa"
        fun num(x: String) = if (fa) faDigits(x) else x
        val title = when (state) {
            is VpnState.Connected -> context.getString(R.string.notif_connected, context.getString(R.string.status_connected), state.server.name)
            is VpnState.Connecting -> context.getString(R.string.status_connecting)
            else -> context.getString(R.string.app_name)
        }
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_netino)
            .setColor(0xFF1A73D9.toInt())
            .setContentTitle(title)
            .setContentText(
                "↓ " + num(NetinoWidget.formatBits(t.downBps)) + " · ↑ " + num(NetinoWidget.formatBits(t.upBps)) +
                    " · " + num(formatBytes(t.sessionRx + t.sessionTx)),
            )
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_netino),
                context.getString(R.string.cd_disconnect), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        // Connected: the time since connecting in the header (ticks by itself), and "switch server"
        if (state is VpnState.Connected) {
            b.setWhen(state.since).setShowWhen(true).setUsesChronometer(true)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_netino),
                context.getString(R.string.notif_switch), NetinoWidget.action(context, NetinoWidget.ACTION_SWITCH, 14)).build())
        }
        return b.build()
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
