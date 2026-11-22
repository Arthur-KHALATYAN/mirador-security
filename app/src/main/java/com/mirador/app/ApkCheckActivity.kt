package com.mirador.app

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import java.io.File

/** Analyse un fichier .apk AVANT de l'installer. */
class ApkCheckActivity : GuardedActivity() {

    private data class ApkReport(
        val label: String, val pkg: String, val version: String,
        val score: Int, val reasons: List<String>, val installedNote: String?,
    )

    private var report: ApkReport? = null
    private var error: String? = null
    private var pendingUri: Uri? = null

    private val risky = listOf(
        "android.permission.READ_SMS" to (25 to "Lire vos SMS"),
        "android.permission.RECEIVE_SMS" to (15 to "Intercepter les SMS reçus"),
        "android.permission.SEND_SMS" to (15 to "Envoyer des SMS"),
        "android.permission.RECORD_AUDIO" to (15 to "Utiliser le micro"),
        "android.permission.CAMERA" to (10 to "Utiliser la caméra"),
        "android.permission.READ_CALL_LOG" to (20 to "Lire le journal d'appels"),
        "android.permission.READ_CONTACTS" to (10 to "Lire les contacts"),
        "android.permission.ACCESS_FINE_LOCATION" to (10 to "Localisation précise"),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to (20 to "Localisation en permanence"),
        "android.permission.SYSTEM_ALERT_WINDOW" to (10 to "S'afficher par-dessus les autres applis"),
        "android.permission.REQUEST_INSTALL_PACKAGES" to (10 to "Installer d'autres applis"),
        "android.permission.QUERY_ALL_PACKAGES" to (5 to "Voir toutes vos applis"),
        "android.permission.RECEIVE_BOOT_COMPLETED" to (5 to "Démarrer avec le téléphone"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ouvert depuis un gestionnaire de fichiers (« Ouvrir avec Mirador »)
        pendingUri = intent?.data
    }

    override fun render() {
        pendingUri?.let { pendingUri = null; analyze(it); return }
        val c = screen()
        c.header(R.drawable.ic_android, 0xFF10B981.toInt(), "Analyse d'un APK")
        c.label("Vérifiez une appli téléchargée hors Play Store AVANT de l'installer : permissions, accès dangereux, base de logiciels espions.", 13f, color = Ui.MUTED)
        c.btn("Choisir un fichier .apk") {
            Lock.allowExternal()
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            startActivityForResult(pick, 40)
        }
        error?.let { c.card(Verdict.CAUTION.bg).label("Impossible d'analyser : $it", 14f, color = Verdict.CAUTION.color) }
        report?.let { r ->
            val v = when {
                r.score >= 60 -> Verdict.DANGER
                r.score >= 25 -> Verdict.CAUTION
                else -> Verdict.SAFE
            }
            c.card(v.bg).apply {
                iconTitle(v.iconRes, v.color, r.label, 18f)
                label("${r.pkg} • version ${r.version}", 12f, color = Ui.MUTED)
                label("Risque : ${r.score}/100", 14f, true)
                r.installedNote?.let { label(it, 13f, true) }
                if (r.reasons.isEmpty()) label("Aucun accès sensible demandé.", 13f)
                r.reasons.forEach { label("• $it", 13f, top = 3) }
            }
            c.label(
                when (v) {
                    Verdict.DANGER -> "Ne l'installez pas, sauf si vous êtes absolument sûr de sa provenance."
                    Verdict.CAUTION -> "Installez-la seulement si elle vient d'une source fiable et si ces accès sont justifiés."
                    Verdict.SAFE -> "Pas de signal inquiétant, mais préférez toujours le Play Store."
                }, 14f, top = 10,
            )
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 40 && resultCode == RESULT_OK) data?.data?.let { pendingUri = it }
    }

    private fun analyze(uri: Uri) {
        screen().label("Analyse en cours…", 16f)
        Thread {
            val res = runCatching { inspect(uri) }
            File(cacheDir, "check.apk").delete() // toujours supprimé, même en cas d'erreur
            runOnUiThread {
                report = res.getOrNull()
                error = res.exceptionOrNull()?.message
                if (unlocked) render()
            }
        }.start()
    }

    private fun inspect(uri: Uri): ApkReport {
        val f = File(cacheDir, "check.apk")
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            ?: error("fichier illisible")
        val pm = packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or Certs.flags
        val p: PackageInfo = pm.archiveInfo(f.path, flags) ?: error("ce n'est pas un APK valide")
        val ai = p.applicationInfo
        ai?.sourceDir = f.path
        ai?.publicSourceDir = f.path
        val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: p.packageName

        val reasons = mutableListOf<String>()
        var score = 0
        fun add(pts: Int, why: String) { score += pts; reasons += why }

        Stalkerware.ensureLoaded(this)
        val certs = Certs.sha1List(p)
        Stalkerware.match(p.packageName, certs)?.let { add(100, "⛔ LOGICIEL ESPION CONNU : « $it »") }

        if (p.services.orEmpty().any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
            add(30, "Demande l'accès Accessibilité (lire l'écran, agir à votre place)")
        if (p.services.orEmpty().any { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" })
            add(20, "Veut lire toutes vos notifications")
        if (p.receivers.orEmpty().any { it.permission == "android.permission.BIND_DEVICE_ADMIN" })
            add(25, "Veut devenir administrateur de l'appareil (difficile à désinstaller)")

        val req = p.requestedPermissions.orEmpty().toSet()
        risky.forEach { (perm, v) -> if (perm in req) add(v.first, v.second) }

        val installed = runCatching { pm.pkgInfo(p.packageName, Certs.flags) }.getOrNull()
        val note = installed?.let {
            if (Certs.sha1List(it).intersect(certs.toSet()).isEmpty()) {
                add(40, "Signature DIFFÉRENTE de l'appli déjà installée : version modifiée / piégée ?")
                "Cette appli est déjà installée, mais ce fichier n'a pas la même signature."
            } else "Mise à jour d'une appli déjà installée (même signature)."
        }
        f.delete()
        return ApkReport(label, p.packageName, p.versionName ?: "?", score.coerceAtMost(100), reasons, note)
    }
}
