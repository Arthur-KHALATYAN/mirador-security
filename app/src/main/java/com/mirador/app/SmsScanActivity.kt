package com.mirador.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Switch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsScanActivity : GuardedActivity() {

    private data class Hit(val from: String, val date: Long, val body: String, val report: TextReport)

    private var generation = 0

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun render() {
        val ctx = this
        val gen = ++generation
        val c = screen()
        c.header(R.drawable.ic_chat, 0xFF0EA5E9.toInt(), "SMS & messageries")
        c.label("Repère les SMS d'arnaque : faux colis, fausse amende, Ameli, CPF, « coucou maman », compte bloqué… Tout est analysé sur le téléphone.", 13f, color = Ui.MUTED)

        c.addView(Switch(this).apply {
            text = "Alerte immédiate quand un SMS suspect arrive"
            setTextColor(Ui.TEXT)
            isChecked = Store.smsEnabled(ctx) && has(Manifest.permission.RECEIVE_SMS)
            setOnCheckedChangeListener { _, on ->
                Store.setSmsEnabled(ctx, on)
                if (on && !has(Manifest.permission.RECEIVE_SMS)) requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
            }
            setPadding(0, dp(12), 0, dp(4))
        })

        // Messageries (WhatsApp, Messenger, Telegram…)
        val listenerOn = MessageGuardService.isEnabled(this)
        c.addView(Switch(this).apply {
            text = "Alerte pour les messages WhatsApp, Messenger, Telegram, Signal, Gmail…"
            setTextColor(Ui.TEXT)
            isChecked = Store.msgEnabled(ctx) && listenerOn
            setOnCheckedChangeListener { _, on ->
                Store.setMsgEnabled(ctx, on)
                if (on && !MessageGuardService.isEnabled(ctx)) {
                    toast("Activez Mirador dans la liste « Accès aux notifications »")
                    Lock.allowExternal()
                    safeStart(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
            }
            setPadding(0, dp(8), 0, dp(4))
        })
        c.label(
            "Lit le texte des notifications de ces applis pour y repérer les arnaques. Rien n'est enregistré ni envoyé. " +
                "Applis surveillées : " + MessageGuardService.APPS.values.distinct().joinToString(", ") + ".",
            12f, color = Ui.MUTED,
        )

        c.label("SMS déjà reçus", 18f, true, top = 18)
        if (!has(Manifest.permission.READ_SMS)) {
            c.label("Pour analyser vos SMS existants, Mirador doit pouvoir les lire. Rien n'est envoyé ailleurs.", 14f)
            c.btn("Autoriser la lecture des SMS") { requestPermissions(arrayOf(Manifest.permission.READ_SMS), 2) }
            return
        }

        val box = c.box()
        box.label("Analyse des 300 derniers SMS…", 14f, color = Ui.MUTED)
        Thread {
            val hits = scanInbox()
            runOnUiThread {
                if (isFinishing || gen != generation) return@runOnUiThread
                box.removeAllViews()
                show(box, hits)
            }
        }.start()
    }

    private fun scanInbox(): List<Hit> {
        val hits = mutableListOf<Hit>()
        try {
            contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n < 300) {
                    n++
                    val body = cur.getString(1) ?: continue
                    val r = ScamText.analyze(body)
                    if (r.score >= 20) hits += Hit(cur.getString(0) ?: "?", cur.getLong(2), body, r)
                }
            }
        } catch (_: Exception) {
        }
        return hits.sortedByDescending { it.report.score }
    }

    private fun show(box: android.widget.LinearLayout, hits: List<Hit>) {
        if (hits.isEmpty()) {
            box.card(Verdict.SAFE.bg).iconTitle(R.drawable.ic_check_circle, Verdict.SAFE.color, "Aucun SMS suspect parmi les 300 derniers.", 15f)
            return
        }
        box.label("${hits.size} SMS suspect(s)", 14f, true)
        val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.FRANCE)
        for (h in hits) {
            val v = h.report.verdict
            box.card(v.bg).apply {
                iconTitle(v.iconRes, v.color, "${h.from}  •  ${fmt.format(Date(h.date))}", 14f)
                label(if (h.body.length > 300) h.body.take(300) + "…" else h.body, 13f, top = 6).raw()
                h.report.reasons.forEach { label("• $it", 13f, top = 3) }
                h.report.worstUrl?.let { u ->
                    btn("Analyser le lien", v.solid) {
                        startActivity(Intent(this@SmsScanActivity, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, u.url))
                    }
                }
            }
        }
        box.label("Conseil : supprimez ces SMS et signalez-les gratuitement en les transférant au 33700.", 12f, color = Ui.MUTED, top = 14)
    }
}
