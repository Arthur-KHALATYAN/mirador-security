package com.mirador.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Relance la surveillance après un redémarrage ou une mise à jour de Mirador. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WatchJob.schedule(c)
        if (Store.monitorEnabled(c)) SensorMonitorService.start(c)
        if (Store.dnsEnabled(c) && VpnService.prepare(c) == null) {
            runCatching { c.startService(Intent(c, DnsVpnService::class.java)) }
        }
    }
}
