package com.mirador.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Analyse les messages reçus dans WhatsApp, Messenger, Telegram, Signal, Gmail…
 * (via leurs notifications) et alerte en cas d'arnaque. Tout reste sur le téléphone.
 */
class MessageGuardService : NotificationListenerService() {

    companion object {
        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.facebook.orca" to "Messenger",
            "com.facebook.katana" to "Facebook",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.snapchat.android" to "Snapchat",
            "com.discord" to "Discord",
            "com.viber.voip" to "Viber",
            "jp.naver.line.android" to "LINE",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages Samsung",
            "com.google.android.gm" to "Gmail",
            "com.microsoft.office.outlook" to "Outlook",
            "com.linkedin.android" to "LinkedIn",
            "com.vinted" to "Vinted",
            "fr.leboncoin" to "Leboncoin",
        )

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            return flat.contains(ComponentName(c, MessageGuardService::class.java).flattenToString())
        }

        private val seen = LinkedHashSet<Int>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Store.msgEnabled(this)) return
        val app = APPS[sbn.packageName] ?: return
        val extras = sbn.notification?.extras ?: return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = buildString {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it).append('\n') }
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.let { append(it).append('\n') }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { append(it).append('\n') }
            val messages: Array<out android.os.Parcelable>? =
                if (android.os.Build.VERSION.SDK_INT >= 33) extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
                else @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            messages?.forEach { m ->
                (m as? Bundle)?.getCharSequence("text")?.let { append(it).append('\n') }
            }
        }.trim()
        if (text.isEmpty()) return

        val key = (sbn.packageName + text).hashCode()
        synchronized(seen) {
            if (!seen.add(key)) return
            if (seen.size > 300) seen.remove(seen.first())
        }

        val r = ScamText.analyze(text)
        if (r.verdict == Verdict.SAFE) return
        val open = r.worstUrl?.let {
            Intent(this, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, it.url)
        }
        Notifier.alert(
            this, key,
            "${r.verdict.icon} Message suspect ($app)" + if (title.isNotEmpty()) " de $title" else "",
            r.reasons.joinToString(" • ") + "\nNe cliquez pas sur le lien, ne donnez aucun code ni information bancaire.",
            open,
        )
    }
}
