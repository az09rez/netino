package com.netino.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.netino.vpn.R
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Subscription
import com.netino.vpn.ui.MainActivity
import com.netino.vpn.ui.formatBytes

/**
 * Warns before a subscription stops working: 80 % of its data used, 3 days left, out of data, expired (from the
 * panel's subscription-userinfo header). Each level is announced once per subscription; a renewed subscription
 * starts over.
 */
object SubscriptionAlerts {
    private const val CHANNEL = "subscription"
    private const val DAY = 86_400_000L

    fun check(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val subs = Repository.subscriptions.value.filter { !it.builtIn }
        val old = Repository.settings.value.subAlerts
        val next = mutableMapOf<String, String>()
        for (sub in subs) {
            val level = level(sub, now) ?: continue
            next[sub.id] = level
            if (old[sub.id] != level) notify(app, sub, level, now)
        }
        if (next != old) Repository.updateSettings { it.copy(subAlerts = next) }
    }

    private fun level(sub: Subscription, now: Long): String? {
        val msLeft = sub.expire * 1000 - now
        return when {
            sub.expire > 0 && msLeft <= 0 -> "expired"
            sub.total > 0 && sub.used >= sub.total -> "used100"
            sub.expire > 0 && msLeft < 3 * DAY -> "days3"
            sub.total > 0 && sub.used >= sub.total * 8 / 10 -> "used80"
            else -> null
        }
    }

    private fun notify(context: Context, sub: Subscription, level: String, now: Long) = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_subscription), NotificationManager.IMPORTANCE_DEFAULT))
        val title = when (level) {
            "expired" -> context.getString(R.string.sub_alert_expired, sub.name)
            "used100" -> context.getString(R.string.sub_alert_used100, sub.name)
            "days3" -> context.getString(R.string.sub_alert_days, sub.name, ((sub.expire * 1000 - now) / DAY + 1).toInt())
            else -> context.getString(R.string.sub_alert_used80, sub.name, formatBytes(sub.used), formatBytes(sub.total))
        }
        val open = PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_netino)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.sub_alert_body))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.sub_alert_body)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1000 + (sub.id.hashCode() and 0xFFF), n)
    }
}
