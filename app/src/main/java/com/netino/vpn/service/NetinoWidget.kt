package com.netino.vpn.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.netino.vpn.R
import com.netino.vpn.ui.MainActivity
import android.os.SystemClock
import android.view.View
import com.netino.vpn.ui.faDigits

/** Home-screen widget (4x1): status, server · delay, and a one-tap connect button; light or dark with the system. */
class NetinoWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.netino.vpn.WIDGET_TOGGLE"
        internal const val ACTION_SWITCH = "com.netino.vpn.WIDGET_SWITCH"
        private const val ACTION_FASTEST = "com.netino.vpn.WIDGET_FASTEST"
        private const val ACTION_WARP = "com.netino.vpn.WIDGET_WARP"

        /** Redraws every widget of both sizes; cheap, called on state changes and every few seconds while connected. */
        fun update(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            for ((cls, large) in listOf(NetinoWidget::class.java to false, NetinoWidgetLarge::class.java to true)) {
                val ids = mgr.getAppWidgetIds(ComponentName(app, cls))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(app, large))
            }
        }

        internal fun views(context: Context, large: Boolean): RemoteViews {
            val state = VpnController.state.value
            val t = VpnController.traffic.value
            val fa = context.resources.configuration.locales[0].language == "fa"
            fun num(x: String) = if (fa) faDigits(x) else x
            fun color(id: Int) = context.getColor(id)
            val v = RemoteViews(context.packageName, if (large) R.layout.widget_netino_large else R.layout.widget_netino)
            val (status, statusColor) = when (state) {
                is VpnState.Connected -> context.getString(R.string.status_connected) to R.color.widget_good
                is VpnState.Connecting -> context.getString(R.string.status_connecting) to R.color.widget_warn
                is VpnState.Error -> context.getString(R.string.status_error) to R.color.widget_bad
                VpnState.Disconnected -> context.getString(R.string.status_disconnected) to R.color.widget_on
            }
            // Small: "server · delay"; large: the server, then the time connected (a chronometer, so it ticks by itself)
            val detail = when (state) {
                is VpnState.Connected -> if (large) state.server.name + " ·"
                    else state.server.name + (state.server.lastPingMs.takeIf { it > 0 }?.let { " · " + num("$it") + "ms" } ?: "")
                is VpnState.Connecting -> state.server.name
                else -> context.getString(R.string.tap_to_connect)
            }
            v.setTextViewText(R.id.widget_status, status)
            v.setTextColor(R.id.widget_status, color(statusColor))
            v.setTextViewText(R.id.widget_detail, detail)
            val on = state is VpnState.Connected
            v.setInt(R.id.widget_toggle, "setBackgroundResource", when (state) {
                is VpnState.Connected -> R.drawable.widget_btn_on
                is VpnState.Connecting -> R.drawable.widget_btn_busy
                else -> R.drawable.widget_btn_off
            })
            v.setInt(R.id.widget_toggle, "setColorFilter", color(when (state) {
                is VpnState.Connected -> android.R.color.white
                is VpnState.Connecting -> R.color.widget_warn
                is VpnState.Error -> R.color.widget_bad
                else -> R.color.widget_on_variant
            }))
            v.setContentDescription(R.id.widget_toggle, context.getString(if (on || state is VpnState.Connecting) R.string.cd_disconnect else R.string.cd_connect))
            v.setOnClickPendingIntent(R.id.widget_toggle, action(context, ACTION_TOGGLE, 10))
            v.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            if (large) {
                if (state is VpnState.Connected) {
                    v.setViewVisibility(R.id.widget_timer, View.VISIBLE)
                    v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - (System.currentTimeMillis() - state.since), null, true)
                } else {
                    v.setViewVisibility(R.id.widget_timer, View.GONE)
                    v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), null, false)
                }
                v.setTextViewText(R.id.widget_down, if (on) "↓ " + num(formatBits(t.downBps)) else "")
                v.setTextViewText(R.id.widget_up, if (on) "↑ " + num(formatBits(t.upBps)) else "")
                v.setOnClickPendingIntent(R.id.widget_fastest, action(context, ACTION_FASTEST, 12))
                v.setOnClickPendingIntent(R.id.widget_warp, action(context, ACTION_WARP, 13))
            }
            return v
        }

        /** Bits per second, as speed tests say it. */
        internal fun formatBits(bytesPerSec: Long): String {
            val bits = bytesPerSec * 8.0
            return when {
                bits >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.US, bits / 1_000_000)
                bits >= 1_000 -> "%.0f Kbps".format(java.util.Locale.US, bits / 1_000)
                else -> "%.0f bps".format(java.util.Locale.US, bits)
            }
        }

        internal fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, NetinoWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

        /** Both widget sizes send their buttons here. */
        internal fun handle(context: Context, action: String?) {
            if (action != ACTION_TOGGLE && action != ACTION_FASTEST && action != ACTION_WARP && action != ACTION_SWITCH) return
            if (VpnService.prepare(context) != null) {
                // First use: the VPN permission dialog needs the app
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            when (action) {
                ACTION_TOGGLE -> VpnController.toggle()
                ACTION_FASTEST -> VpnController.connectFastest()
                ACTION_WARP -> VpnController.connectWarp()
                ACTION_SWITCH -> VpnController.switchServer()
            }
            update(context)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = mgr.updateAppWidget(ids, views(context, false))

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        handle(context, intent.action)
    }
}

/** Home-screen widget (4x2): status, server, live download / upload speed, connect, quick connect and WARP. */
class NetinoWidgetLarge : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = mgr.updateAppWidget(ids, NetinoWidget.views(context, true))
}
