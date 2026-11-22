package com.mirador.app

import android.content.Context
import android.content.Intent

/** Élément du score : points gagnés si `ok`, sinon action pour l'obtenir. */
data class ScoreItem(val title: String, val points: Int, val ok: Boolean, val action: Intent?)

/** Score de sécurité global sur 100. */
object SecurityScore {

    fun items(c: Context): List<ScoreItem> {
        val checks = DeviceAudit.run(c).associateBy { it.title }
        fun audit(title: String) = checks[title]?.ok ?: true
        fun open(cls: Class<*>) = Intent(c, cls)
        fun settings(action: String?) = action?.let { Intent(it) }
        val fresh = System.currentTimeMillis() - Store.feedsUpdated(c) < 3 * 24 * 3600_000L
        return listOf(
            ScoreItem("Verrouillage de l'écran", 15, audit("Verrouillage de l'écran"), settings(checks["Verrouillage de l'écran"]?.fixAction)),
            ScoreItem("Contrôle des liens actif", 15, DeviceAudit.isDefaultBrowser(c), open(MainActivity::class.java)),
            ScoreItem("Aucune appli à risque élevé", 10, Store.notifiedDanger(c).isEmpty(), open(ScanActivity::class.java)),
            ScoreItem(PATCH, 8, audit("Mises à jour de sécurité"), settings(checks["Mises à jour de sécurité"]?.fixAction)),
            ScoreItem("Téléphone non rooté", 7, audit("Root"), null),
            ScoreItem("Débogage USB désactivé", 5, audit("Débogage USB"), settings(checks["Débogage USB"]?.fixAction)),
            ScoreItem("Filtre DNS actif", 8, DnsVpnService.running, open(DnsActivity::class.java)),
            ScoreItem("Code PIN Mirador", 7, Lock.hasPin(c), open(SettingsActivity::class.java)),
            ScoreItem("Anti-désinstallation", 5, AdminReceiver.isActive(c), open(SettingsActivity::class.java)),
            ScoreItem("Alertes SMS d'arnaque", 5, Store.smsEnabled(c), open(SmsScanActivity::class.java)),
            ScoreItem("Alertes messageries (WhatsApp…)", 5, Store.msgEnabled(c) && MessageGuardService.isEnabled(c), open(SmsScanActivity::class.java)),
            ScoreItem("Surveillance micro / caméra", 5, Store.monitorEnabled(c), open(SensorLogActivity::class.java)),
            ScoreItem("Bases de menaces à jour", 5, fresh, open(SettingsActivity::class.java)),
        ).filterNot { it.title == PATCH && checks["Mises à jour de sécurité"]?.accepted == true }
    }

    const val PATCH = "Mises à jour de sécurité récentes"

    fun score(items: List<ScoreItem>): Int = items.filter { it.ok }.sumOf { it.points } * 100 / items.sumOf { it.points }
}
