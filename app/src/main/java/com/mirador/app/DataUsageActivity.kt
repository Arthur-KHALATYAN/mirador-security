package com.mirador.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout

class DataUsageActivity : GuardedActivity() {

    private var generation = 0

    override fun render() {
        val gen = ++generation
        val c = screen()
        c.header(R.drawable.ic_upload, 0xFFEC4899.toInt(), "Données envoyées par les applis")
        c.label("Un logiciel espion doit envoyer ce qu'il collecte (messages, photos, enregistrements). Mirador repère les applis qui envoient beaucoup en arrière-plan ou la nuit (0 h – 6 h). Période : 7 derniers jours.", 13f, color = Ui.MUTED)

        if (!SensorMonitorService.hasUsageAccess(this)) {
            c.card(Verdict.CAUTION.bg).apply {
                label("Autorisez Mirador dans « Accès aux données d'utilisation » pour voir la consommation par appli.", 14f)
                btn("Autoriser", Verdict.CAUTION.solid) { safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            return
        }

        val box = c.box()
        box.label("Calcul en cours…", 14f, color = Ui.MUTED)
        Thread {
            val list = runCatching { DataUsage.collect(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || gen != generation) return@runOnUiThread
                box.removeAllViews()
                show(box, list)
            }
        }.start()
    }

    private fun show(box: LinearLayout, list: List<AppTraffic>) {
        val sus = list.filter { it.suspicious }
        if (sus.isEmpty()) {
            box.card(Verdict.SAFE.bg).iconTitle(R.drawable.ic_check_circle, Verdict.SAFE.color, "Aucun envoi de données anormal détecté.", 15f)
        } else {
            box.iconTitle(R.drawable.ic_warning, Verdict.CAUTION.color, "${sus.size} appli(s) à surveiller", 17f, top = 10)
        }
        box.label("Classement par données envoyées", 16f, true, top = 16)
        list.take(40).forEach { t ->
            val v = if (t.suspicious) Verdict.CAUTION else null
            box.card(v?.bg ?: Ui.CARD).apply {
                if (t.suspicious) iconTitle(R.drawable.ic_warning, Verdict.CAUTION.color, t.label, 15f) else label(t.label, 15f, true)
                label("Envoyé : ${fmtBytes(t.tx)} (dont arrière-plan ${fmtBytes(t.bgTx)}, nuit ${fmtBytes(t.nightTx)}) • Reçu : ${fmtBytes(t.rx)}", 13f)
                if (t.system) label("Appli système", 12f, color = Ui.MUTED)
                t.pkg?.let { pkg ->
                    if (!t.system) {
                        setOnClickListener {
                            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
                        }
                    }
                }
            }
        }
        box.label("Touchez une appli pour ouvrir ses paramètres (désinstaller, limiter les données en arrière-plan).", 12f, color = Ui.MUTED, top = 12)
    }
}
