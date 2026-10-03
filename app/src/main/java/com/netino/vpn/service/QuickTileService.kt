package com.netino.vpn.service

import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** One-tap connect / disconnect from the notification shade. */
class QuickTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (VpnService.prepare(this) != null) return   // permission not granted yet -> open the app once
        VpnController.toggle()
        refresh()
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (VpnController.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
