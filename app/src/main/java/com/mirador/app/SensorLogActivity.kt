package com.mirador.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Switch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorLogActivity : GuardedActivity() {

    override fun render() {
        val ctx = this
        val c = screen()
        c.header(R.drawable.ic_mic, 0xFFF59E0B.toInt(), "Micro & caméra")
        c.label("Note chaque fois qu'une appli allume le micro ou la caméra, et vous alerte si ça arrive écran éteint (signe typique d'espionnage).", 13f, color = Ui.MUTED)

        c.addView(Switch(this).apply {
            text = "Surveiller le micro et la caméra"
            setTextColor(Ui.TEXT)
            isChecked = Store.monitorEnabled(ctx)
            setOnCheckedChangeListener { _, on ->
                Store.setMonitorEnabled(ctx, on)
                if (on) SensorMonitorService.start(ctx) else SensorMonitorService.stop(ctx)
            }
            setPadding(0, dp(12), 0, dp(4))
        })

        if (!SensorMonitorService.hasUsageAccess(this)) {
            c.card(Verdict.CAUTION.bg).apply {
                label("Pour savoir QUELLE appli utilise le micro ou la caméra, autorisez Mirador dans « Accès aux données d'utilisation ».", 14f)
                btn("Autoriser", Verdict.CAUTION.solid) { safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            c.btn("Tableau de bord Confidentialité d'Android", Ui.DARK) {
                safeStart(Intent("android.intent.action.REVIEW_PERMISSION_USAGE"))
            }
        }

        c.label("Journal", 18f, true, top = 18)
        val log = Store.sensorLog(this)
        if (log.isEmpty()) c.label("Aucun accès enregistré pour l'instant.", 14f, color = Ui.MUTED)
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
        log.take(100).forEach { e ->
            val v = if (e.screenOff && e.start) Verdict.DANGER else if (e.start) Verdict.CAUTION else Verdict.SAFE
            c.card(v.bg).apply {
                val icon = if (e.type == "Micro") R.drawable.ic_mic else R.drawable.ic_camera
                iconTitle(icon, v.color, "${e.type} ${if (e.start) "ALLUMÉ" else "éteint"} • ${fmt.format(Date(e.time))}", 14f)
                if (e.start) label("Appli probable : ${e.app ?: "inconnue"}" + if (e.screenOff) "  •  écran éteint" else "", 13f)
            }
        }
        if (log.isNotEmpty()) c.btn("Effacer le journal", Ui.DARK) { Store.clearSensorLog(ctx); render() }
    }
}
