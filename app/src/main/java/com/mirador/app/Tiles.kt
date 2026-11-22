package com.mirador.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

private fun TileService.openActivity(i: Intent) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(i)
    }
}

/** Bouton des réglages rapides : lance le scan anti-spy. */
class ScanTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "Scan anti-spy"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() = openActivity(Intent(this, ScanActivity::class.java))
}

/** Bouton des réglages rapides : active / coupe le filtre DNS. */
class DnsTile : TileService() {
    override fun onStartListening() = refresh()

    private fun refresh() {
        qsTile?.apply {
            label = "Filtre DNS"
            state = if (DnsVpnService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        // Si un code PIN est défini, on passe par l'appli pour couper le filtre
        if (DnsVpnService.running && Lock.hasPin(this)) {
            openActivity(Intent(this, DnsActivity::class.java))
            return
        }
        val ok = runCatching {
            if (DnsVpnService.running) {
                Store.setDnsEnabled(this, false)
                startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            } else {
                if (VpnService.prepare(this) != null) error("autorisation VPN requise")
                Store.setDnsEnabled(this, true)
                startService(Intent(this, DnsVpnService::class.java))
            }
        }.isSuccess
        if (!ok) openActivity(Intent(this, DnsActivity::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 600)
    }
}
