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
import com.netino.vpn.ui.formatSpeed

/** Home-screen widget (4x1): status, server / live speed, and a one-tap connect button. */
class NetinoWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.netino.vpn.WIDGET_TOGGLE"
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
            val v = RemoteViews(context.packageName, if (large) R.layout.widget_netino_large else R.layout.widget_netino)
            val (status, detail) = when (state) {
                is VpnState.Connected -> context.getString(R.string.status_connected) to
                    if (large) state.server.name else "${state.server.name} • ↓ ${formatSpeed(t.downBps)}"
                is VpnState.Connecting -> context.getString(R.string.status_connecting) to state.server.name
                is VpnState.Error -> context.getString(R.string.status_error) to context.getString(R.string.tap_to_connect)
                VpnState.Disconnected -> context.getString(R.string.status_disconnected) to context.getString(R.string.tap_to_connect)
            }
            v.setTextViewText(R.id.widget_status, status)
            v.setTextViewText(R.id.widget_detail, detail)
            v.setInt(R.id.widget_toggle, "setBackgroundResource",
                if (state is VpnState.Connected || state is VpnState.Connecting) R.drawable.widget_btn_on else R.drawable.widget_btn_off)
            v.setOnClickPendingIntent(R.id.widget_toggle, action(context, ACTION_TOGGLE, 10))
            v.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            if (large) {
                val on = state is VpnState.Connected
                v.setTextViewText(R.id.widget_down, if (on) "↓ ${formatSpeed(t.downBps)}" else "")
                v.setTextViewText(R.id.widget_up, if (on) "↑ ${formatSpeed(t.upBps)}" else "")
                v.setOnClickPendingIntent(R.id.widget_fastest, action(context, ACTION_FASTEST, 12))
                v.setOnClickPendingIntent(R.id.widget_warp, action(context, ACTION_WARP, 13))
            }
            return v
        }

        private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, NetinoWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

        /** Both widget sizes send their buttons here. */
        internal fun handle(context: Context, action: String?) {
            if (action != ACTION_TOGGLE && action != ACTION_FASTEST && action != ACTION_WARP) return
            if (VpnService.prepare(context) != null) {
                // First use: the VPN permission dialog needs the app
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            when (action) {
                ACTION_TOGGLE -> VpnController.toggle()
                ACTION_FASTEST -> VpnController.connectFastest()
                ACTION_WARP -> VpnController.connectWarp()
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
