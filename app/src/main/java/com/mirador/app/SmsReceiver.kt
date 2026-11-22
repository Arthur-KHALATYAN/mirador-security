package com.mirador.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Analyse chaque SMS reçu et alerte s'il ressemble à une arnaque. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !Store.smsEnabled(c)) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (msgs.isEmpty()) return
        val from = msgs[0]?.displayOriginatingAddress ?: "inconnu"
        val body = msgs.joinToString("") { it?.displayMessageBody.orEmpty() }
        val r = ScamText.analyze(body)
        if (r.verdict == Verdict.SAFE) return

        val open = r.worstUrl?.let {
            Intent(c, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, it.url)
        } ?: Intent(c, SmsScanActivity::class.java)
        Notifier.alert(
            c, (from + body).hashCode(),
            "${r.verdict.icon} SMS suspect de $from",
            r.reasons.joinToString(" • ") + "\nNe cliquez pas sur le lien et ne répondez pas.",
            open,
        )
    }
}
