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

/** Home-screen widget: status, server / live speed, and a one-tap connect button. */
class NetinoWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.netino.vpn.WIDGET_TOGGLE"

        /** Redraws every widget; cheap, called on state changes and every few seconds while connected. */
        fun update(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, NetinoWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, views(app))
        }

        private fun views(context: Context): RemoteViews {
            val state = VpnController.state.value
            val t = VpnController.traffic.value
            val v = RemoteViews(context.packageName, R.layout.widget_netino)
            val (status, detail) = when (state) {
                is VpnState.Connected -> context.getString(R.string.status_connected) to
                    "${state.server.name} • ↓ ${formatSpeed(t.downBps)}"
                is VpnState.Connecting -> context.getString(R.string.status_connecting) to state.server.name
                is VpnState.Error -> context.getString(R.string.status_error) to context.getString(R.string.tap_to_connect)
                VpnState.Disconnected -> context.getString(R.string.status_disconnected) to context.getString(R.string.tap_to_connect)
            }
            v.setTextViewText(R.id.widget_status, status)
            v.setTextViewText(R.id.widget_detail, detail)
            v.setInt(R.id.widget_toggle, "setBackgroundResource",
                if (state is VpnState.Connected || state is VpnState.Connecting) R.drawable.widget_btn_on else R.drawable.widget_btn_off)
            val toggle = PendingIntent.getBroadcast(context, 10,
                Intent(context, NetinoWidget::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_toggle, toggle)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = mgr.updateAppWidget(ids, views(context))

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (VpnService.prepare(context) != null) {
            // First use: the VPN permission dialog needs the app
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        VpnController.toggle()
        update(context)
    }
}
