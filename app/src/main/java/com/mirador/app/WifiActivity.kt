package com.mirador.app

import android.content.Intent
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** « Qui est sur mon Wi-Fi ? » : appareils connectés, caméras, nouveaux venus. */
class WifiActivity : GuardedActivity() {

    private var generation = 0
    private var scanning = false
    private var status = ""

    override fun render() {
        val c = screen()
        c.header(R.drawable.ic_wifi, 0xFF14B8A6.toInt(), "Qui est sur mon Wi-Fi ?")
        c.label(
            "Liste les appareils connectés à votre réseau et signale les caméras et les nouveaux appareils. " +
                "Tout est analysé sur le téléphone. N'analysez que votre réseau ou celui du logement où vous êtes.",
            13f, color = Ui.MUTED,
        )

        if (WifiScan.currentWifi(this) == null) {
            c.card(Verdict.CAUTION.bg).apply {
                label("Vous n'êtes pas connecté à un réseau Wi-Fi.", 14f, true)
                btn("Paramètres Wi-Fi", Verdict.CAUTION.solid) { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            }
            return
        }

        val r = WifiScan.last
        if (scanning) {
            c.card().apply {
                iconTitle(R.drawable.ic_refresh, Ui.ACCENT_TEXT, status, 15f)
                label("Environ 15 secondes.", 13f, color = Ui.MUTED)
            }
        } else {
            c.btn(if (r == null) "Analyser le réseau" else "Relancer l'analyse") { scanNow() }
        }
        if (r == null) return

        val devs = r.devices.sortedWith(
            compareBy<NetDevice>({ !it.self }, { !it.gateway }, { it.kind != DevKind.CAMERA }, { !it.isNew }, { it.ipNum }),
        )
        val cams = devs.filter { it.kind == DevKind.CAMERA }
        val news = devs.filter { it.isNew }
        val risky = devs.filter { WifiScan.risks(it).isNotEmpty() }

        c.card(if (cams.isNotEmpty() || news.isNotEmpty() || risky.isNotEmpty()) Verdict.CAUTION.bg else Verdict.SAFE.bg).apply {
            iconTitle(R.drawable.ic_wifi, Ui.TEXT, "${devs.size} appareil(s) sur le réseau", 16f)
            label("Réseau ${r.subnet} • analysé à ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(r.time))}", 13f, color = Ui.MUTED)
            when {
                r.firstScan -> label("Première analyse de ce réseau : les prochains nouveaux appareils seront signalés.", 13f)
                news.isNotEmpty() -> label("${news.size} nouvel(s) appareil(s) depuis la dernière analyse.", 13f, true, Verdict.CAUTION.color)
                else -> label("Aucun nouvel appareil depuis la dernière analyse.", 13f)
            }
        }

        if (cams.isNotEmpty()) {
            c.card(Verdict.CAUTION.bg).apply {
                iconTitle(R.drawable.ic_camera, Verdict.CAUTION.color, "${cams.size} caméra(s) détectée(s)", 16f)
                label(
                    "Chez vous : vérifiez que vous connaissez chaque caméra.\n" +
                        "En location (Airbnb, hôtel, gîte) : une caméra dans une chambre ou une salle de bain est interdite. " +
                        "Prenez une photo, prévenez la plateforme et, si besoin, la police (17).",
                    13f,
                )
            }
        }

        c.label("Appareils", 18f, true, top = 18)
        c.card().apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            devs.forEachIndexed { i, d ->
                if (i > 0) divider()
                val warn = WifiScan.risks(d).isNotEmpty()
                val sub = listOfNotNull(
                    d.ip,
                    d.kind.label.takeIf { d.label != it },
                    listOfNotNull(d.vendor, d.model).joinToString(" ").takeIf { it.isNotBlank() && it != d.label },
                    "NOUVEAU".takeIf { d.isNew },
                    "service risqué".takeIf { warn },
                ).joinToString(" • ")
                val color = when {
                    d.kind == DevKind.CAMERA || d.isNew || warn -> Verdict.CAUTION.color
                    else -> Ui.TEXT
                }
                row(d.kind.icon, d.kind.tint, d.label, sub, color) { details(d) }
            }
        }
        c.label(
            "Les téléphones et ordinateurs peuvent ne pas répondre quand ils sont en veille : la liste n'est pas forcément complète.",
            12f, color = Ui.MUTED, top = 8,
        )

        c.label("Si vous ne reconnaissez pas un appareil", 17f, true, top = 18)
        c.label(
            "• Regardez la liste des appareils dans l'interface de votre box : elle affiche souvent leur nom.\n" +
                "• Changez le mot de passe Wi-Fi (WPA2 ou WPA3) et désactivez le WPS dans la box.\n" +
                "• Un appareil inconnu peut aussi être celui d'un voisin ou d'un proche à qui vous avez donné le code.",
            13f,
        )
    }

    private fun details(d: NetDevice) {
        val lines = buildList {
            add("Type : ${d.kind.label}")
            add("Adresse IP : ${d.ip}")
            d.name?.let { add("Nom : $it") }
            d.vendor?.let { add("Fabricant : $it") }
            d.model?.let { add("Modèle : $it") }
            d.mac?.let { add("Adresse MAC : $it") }
            if (d.services.isNotEmpty()) add("Services annoncés : " + d.services.joinToString(", ") { it.removePrefix("_") })
            if (d.ports.isNotEmpty()) add("Ports ouverts : " + d.ports.joinToString(", ") { p -> WifiScan.PORT_NAMES[p]?.let { "$p ($it)" } ?: "$p" })
            (d.title ?: d.server)?.let { add("Page web : $it") }
            WifiScan.risks(d).forEach { add("⚠ $it") }
            if (d.isNew) add("Nouvel appareil depuis la dernière analyse.")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(d.label)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Fermer", null)
            .showTr()
    }

    private fun scanNow() {
        if (scanning) return
        scanning = true
        status = "Recherche des appareils…"
        val gen = ++generation
        render()
        Thread {
            val r = runCatching {
                WifiScan.scan(applicationContext) { s ->
                    runOnUiThread {
                        status = s
                        if (gen == generation && scanning && !isFinishing && unlocked) render()
                    }
                }
            }.getOrNull()
            runOnUiThread {
                scanning = false
                if (r == null) toast("Analyse impossible : vérifiez la connexion Wi-Fi")
                if (!isFinishing && unlocked) render()
            }
        }.start()
    }
}
