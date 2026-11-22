package com.mirador.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notifier {
    private const val CH_ALERT = "alerts"
    private const val CH_SERVICE = "service"

    fun init(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, I18n.tr("Alertes de sécurité"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, I18n.tr("Surveillance active"), NotificationManager.IMPORTANCE_MIN))
    }

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alert(c: Context, id: Int, titleFr: String, textFr: String, open: Intent?) {
        val title = I18n.tr(titleFr)
        val text = I18n.tr(textFr)
        Store.incAlerts(c)
        if (!canNotify(c)) return
        init(c)
        val target = open ?: Intent(c, MainActivity::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, id, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun serviceNotification(c: Context, text: String, open: Intent): Notification {
        init(c)
        val pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(c, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Mirador")
            .setContentText(I18n.tr(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
