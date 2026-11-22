package com.mirador.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanActivity : GuardedActivity() {

    private var result: Pair<Int, List<AppRisk>>? = null

    override fun render() {
        val r = result
        if (r == null) startScan() else show(r.first, r.second)
    }

    private fun startScan() {
        screen().label("Analyse des applications en cours…", 16f)
        Thread {
            val res = AppScanner.scan(this)
            // Met à jour le score de sécurité avec ce nouveau résultat
            Store.setNotifiedDanger(this, AppScanner.dangerous(res.second).map { it.pkg }.toSet())
            runOnUiThread {
                result = res
                if (!isFinishing && unlocked) show(res.first, res.second)
            }
        }.start()
    }

    /** Met à jour l'état « approuvée » sans relancer tout le scan. */
    private fun setApproved(r: AppRisk, approved: Boolean) {
        if (approved) Store.approve(this, r.pkg, r.cert ?: return) else Store.unapprove(this, r.pkg)
        result = result?.let { (n, list) -> n to list.map { if (it.pkg == r.pkg) it.copy(approved = approved) else it } }
        result?.let { Store.setNotifiedDanger(this, AppScanner.dangerous(it.second).map { a -> a.pkg }.toSet()) }
        render()
    }

    private fun askApprove(r: AppRisk) {
        AlertDialog.Builder(this)
            .setTitle("Vous portez-vous garant de « ${r.label} » ?")
            .setMessage(
                "Elle ne sera plus signalée comme dangereuse ni comptée dans le score. Vous autorisez en connaissance de cause :\n\n" +
                    r.reasons.joinToString("\n") { "• $it" } +
                    "\n\nL'approbation est annulée automatiquement si l'appli est remplacée par une version signée différemment. " +
                    "Vous pourrez la retirer à tout moment.",
            )
            .setPositiveButton("Je m'en porte garant") { _, _ ->
                Lock.confirm(this, "Code requis pour approuver ${r.label}") { setApproved(r, true) }
            }
            .setNegativeButton("Annuler", null)
            .showTr()
    }

    private fun appCard(c: LinearLayout, r: AppRisk) {
        val v = if (r.approved) Verdict.SAFE else r.verdict
        val knownSpy = r.reasons.any { it.contains("LOGICIEL ESPION CONNU") }
        c.card(v.bg).apply {
            iconTitle(if (r.approved) R.drawable.ic_shield else v.iconRes, v.color, r.label, 17f)
            label(r.pkg, 12f, color = Ui.MUTED, top = 0)
            if (r.approved) {
                val since = Store.approvals(context)[r.pkg]?.time ?: 0L
                label(
                    "Approuvée par vous" + (if (since > 0) " le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(since)) else "") +
                        " • risque calculé ${r.score}/100",
                    13f, true,
                )
                label("Ce que vous avez autorisé :", 13f, color = Ui.MUTED, top = 6)
            } else {
                label("Risque : ${r.score}/100", 14f, true)
            }
            r.reasons.forEach { label("• $it", 13f, top = 3) }
            btn("Voir / désinstaller cette appli", Ui.DARK) {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", r.pkg, null)))
            }
            if (r.usesAccessibility) {
                btn("Gérer son accès accessibilité", if (r.approved) Ui.DARK else v.solid) {
                    safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            when {
                r.approved -> btn("Retirer ma confiance", Ui.DARK) {
                    Lock.confirm(this@ScanActivity, "Code requis") { setApproved(r, false) }
                }
                knownSpy -> label("Logiciel espion connu : il ne peut pas être approuvé.", 12f, true, v.color, top = 8)
                r.cert != null -> btn("Je connais cette appli, je m'en porte garant", Ui.DARK) { askApprove(r) }
            }
        }
    }

    private fun show(total: Int, risks: List<AppRisk>) {
        val c = screen()
        val toCheck = risks.filter { !it.approved }
        val approved = risks.filter { it.approved }
        c.header(R.drawable.ic_eye, 0xFF6366F1.toInt(), "Scan anti-spy")
        c.label("$total applications installées analysées • ${toCheck.size} à vérifier • ${approved.size} approuvée(s)", 14f, color = Ui.MUTED)
        c.label("Surveillance automatique : vous êtes alerté à chaque nouvelle appli et à chaque nouvel accès sensible.", 13f, color = Ui.MUTED, top = 6)

        if (toCheck.isEmpty()) {
            c.card(Verdict.SAFE.bg).iconTitle(R.drawable.ic_check_circle, Verdict.SAFE.color, "Aucune application suspecte détectée.")
        }
        toCheck.forEach { appCard(c, it) }

        if (approved.isNotEmpty()) {
            c.label("Applis approuvées par vous", 18f, true, top = 22)
            c.label("Elles ne déclenchent plus d'alerte de risque. Vérifiez de temps en temps que vous êtes toujours d'accord avec ce qu'elles peuvent faire.", 13f, color = Ui.MUTED)
            approved.forEach { appCard(c, it) }
        }

        c.label(
            "Un score élevé ne prouve pas qu'une appli est malveillante (ex. une appli de montre connectée lit les notifications). " +
                "Méfiez-vous surtout des applis que vous ne reconnaissez pas, cachées, installées hors Play Store, " +
                "avec accessibilité ou administrateur. Si une appli refuse de se désinstaller : retirez-lui d'abord " +
                "le droit administrateur (Paramètres > Sécurité > Applis d'administration).",
            12f, color = Ui.MUTED, top = 18,
        )
        c.btn("Relancer le scan") { startScan() }
    }
}
