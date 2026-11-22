package com.mirador.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Widget d'écran d'accueil : état des protections + boutons rapides. */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(c)) }
    }

    companion object {
        private fun pi(c: Context, code: Int, i: Intent) = PendingIntent.getActivity(
            c, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun status(c: Context): String {
            val links = if (DeviceAudit.isDefaultBrowser(c)) "✅ Liens" else "⛔ Liens"
            val dns = if (DnsVpnService.running) "✅ DNS" else "⚪ DNS"
            val sms = if (Store.smsEnabled(c)) "✅ SMS" else "⚪ SMS"
            val last = Store.lastWatch(c)
            val time = if (last > 0) SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(last)) else "—"
            return "$links  $dns  $sms\nDernier contrôle : $time"
        }

        fun views(c: Context): RemoteViews = RemoteViews(c.packageName, R.layout.widget_status).apply {
            setTextViewText(R.id.w_status, status(c))
            setOnClickPendingIntent(R.id.w_root, pi(c, 31, Intent(c, MainActivity::class.java)))
            setOnClickPendingIntent(R.id.w_scan, pi(c, 32, Intent(c, ScanActivity::class.java)))
            setOnClickPendingIntent(R.id.w_dns, pi(c, 33, Intent(c, DnsActivity::class.java)))
        }

        fun refresh(c: Context) {
            runCatching {
                val m = AppWidgetManager.getInstance(c)
                m.getAppWidgetIds(ComponentName(c, StatusWidget::class.java)).forEach { m.updateAppWidget(it, views(c)) }
            }
        }
    }
}
