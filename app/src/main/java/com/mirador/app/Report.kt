package com.mirador.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rapport de sécurité complet, en texte, à partager (mail, WhatsApp, Drive…). */
object Report {

    fun build(c: Context): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        val sb = StringBuilder()
        sb.appendLine("RAPPORT DE SÉCURITÉ MIRADOR – ${fmt.format(Date())}")
        sb.appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} • correctif ${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine()

        sb.appendLine("== Protections ==")
        sb.appendLine("Contrôle des liens : " + if (DeviceAudit.isDefaultBrowser(c)) "actif" else "inactif")
        sb.appendLine("Google Safe Browsing : " + if (Store.sbKey(c).isNotEmpty()) "configuré" else "non configuré")
        sb.appendLine("Filtre DNS : " + if (DnsVpnService.running) "actif (${DnsBlocklist.blockedCount} requêtes bloquées)" else "inactif")
        sb.appendLine("Alertes SMS : " + if (Store.smsEnabled(c)) "actives" else "inactives")
        sb.appendLine("Surveillance micro/caméra : " + if (Store.monitorEnabled(c)) "active" else "inactive")
        sb.appendLine("Code PIN : " + if (Lock.hasPin(c)) "oui" else "non")
        Stalkerware.ensureLoaded(c)
        PhishFeed.ensureLoaded(c)
        sb.appendLine("Bases : ${Stalkerware.appCount} logiciels espions, ${PhishFeed.size} liens malveillants")
        sb.appendLine()

        sb.appendLine("== Audit du téléphone ==")
        DeviceAudit.run(c).forEach { sb.appendLine((if (it.ok) "[OK] " else "[!!] ") + it.title + " : " + it.detail) }
        sb.appendLine()

        sb.appendLine("== Applications à risque ==")
        val (total, risks) = AppScanner.scan(c)
        sb.appendLine("$total applis analysées, ${risks.size} à vérifier")
        risks.take(30).forEach { r ->
            sb.appendLine("- ${r.label} (${r.pkg}) : risque ${r.score}/100" + if (r.approved) " [approuvée par l'utilisateur]" else "")
            r.reasons.forEach { sb.appendLine("    • $it") }
        }
        sb.appendLine()

        if (SensorMonitorService.hasUsageAccess(c)) {
            val sus = runCatching { DataUsage.collect(c) }.getOrDefault(emptyList()).filter { it.suspicious }
            sb.appendLine("== Envois de données suspects (7 jours) ==")
            if (sus.isEmpty()) sb.appendLine("Aucun")
            sus.forEach { sb.appendLine("- ${it.label} : envoyé ${fmtBytes(it.tx)}, arrière-plan ${fmtBytes(it.bgTx)}, nuit ${fmtBytes(it.nightTx)}") }
            sb.appendLine()
        }

        val sensors = Store.sensorLog(c).filter { it.start }.take(20)
        if (sensors.isNotEmpty()) {
            sb.appendLine("== Derniers accès micro / caméra ==")
            sensors.forEach {
                sb.appendLine("- ${fmt.format(Date(it.time))} ${it.type} par ${it.app ?: "?"}" + if (it.screenOff) " (ÉCRAN ÉTEINT)" else "")
            }
            sb.appendLine()
        }

        val links = Store.history(c).filter { it.verdict != Verdict.SAFE }.take(20)
        if (links.isNotEmpty()) {
            sb.appendLine("== Liens suspects récents ==")
            links.forEach { sb.appendLine("- ${fmt.format(Date(it.time))} [${it.verdict.label} ${it.score}] ${it.action} : ${it.url}") }
        }
        return sb.toString()
    }

    fun share(a: Activity) {
        a.toast("Préparation du rapport…")
        Thread {
            val text = runCatching { build(a) }.getOrElse { "Erreur : ${it.message}" }
            a.runOnUiThread {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Rapport de sécurité Mirador")
                    .putExtra(Intent.EXTRA_TEXT, text)
                a.startActivity(Intent.createChooser(send, "Partager le rapport"))
            }
        }.start()
    }
}
