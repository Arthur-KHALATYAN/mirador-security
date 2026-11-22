package com.mirador.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Écran affiché à chaque fois qu'un lien est ouvert sur le téléphone.
 * L'utilisateur voit le verdict AVANT que la page ne soit chargée.
 */
class MiradorActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var report: UrlReport
    private var originalUrl: String? = null
    private var hops = 0
    private var decided = false
    private var blockReason: String? = null

    /** null = non configuré, "checking", "clean", "threat", ou message d'erreur. */
    private var sbState: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = extractUrl(intent)
        if (raw == null) {
            toast("Aucun lien trouvé")
            finish()
            return
        }
        report = Feeds.enrich(this, UrlAnalyzer.analyze(raw))

        blockReason = Store.blockReason(this, report)
        blockReason?.let {
            report = UrlAnalyzer.withFinding(report, Finding(100, it))
            render()
            return
        }

        if (report.domain in Store.trusted(this)) {
            openInBrowser(report.url, "ouvert (domaine de confiance)")
            return
        }

        val autoOpen = Store.autoOpenSafe(this) && report.verdict == Verdict.SAFE
        val key = Store.sbKey(this)
        if (key.isNotEmpty()) {
            if (autoOpen) screen().iconTitle(R.drawable.ic_shield, Ui.ACCENT_TEXT, "Vérification du lien…", 18f, top = 24) else render()
            runSafeBrowsing(key, autoOpen)
            return
        }
        if (autoOpen) {
            openInBrowser(report.url, "ouvert (auto)")
            return
        }
        render()
    }

    private fun extractUrl(i: Intent): String? {
        val s = i.getStringExtra(EXTRA_URL)
            ?: i.dataString
            ?: i.getStringExtra(Intent.EXTRA_TEXT)?.let { txt ->
                Regex("""https?://\S+""").find(txt)?.value
                    ?: txt.trim().takeIf { it.contains('.') && !it.contains(' ') }
            }
            ?: return null
        val t = s.trim()
        return if (t.contains("://")) t else "https://$t"
    }

    private fun runSafeBrowsing(key: String, autoOpen: Boolean) {
        sbState = "checking"
        val url = report.url
        Thread {
            val res = SafeBrowsing.check(this, key, url)
            runOnUiThread {
                if (isFinishing || isDestroyed || report.url != url) return@runOnUiThread
                res.onSuccess { threats ->
                    if (threats.isEmpty()) {
                        sbState = "clean"
                        if (autoOpen) {
                            openInBrowser(report.url, "ouvert (auto)")
                            return@runOnUiThread
                        }
                    } else {
                        sbState = "threat"
                        val what = threats.joinToString(", ") { SafeBrowsing.describe(it) }
                        report = UrlAnalyzer.withFinding(report, Finding(100, "Google Safe Browsing : site SIGNALÉ ($what)"))
                    }
                }.onFailure { sbState = "Erreur : ${it.message}" }
                render()
            }
        }.start()
    }

    private fun render() {
        val r = report
        val c = screen()

        c.card(r.verdict.bg).apply {
            iconTitle(r.verdict.iconRes, r.verdict.color, if (blockReason != null) "BLOQUÉ" else r.verdict.label.uppercase(), 26f)
            label("Score de risque : ${r.score}/100", 14f, true)
            label(
                when {
                    blockReason != null -> "Ce lien est interdit par vos règles (modifiable dans Réglages)."
                    r.verdict == Verdict.SAFE -> "Aucun signe d'arnaque évident. Restez prudent si on vous demande un mot de passe ou une carte bancaire."
                    r.verdict == Verdict.CAUTION -> "Ce lien présente des signes suspects. N'entrez aucune information personnelle."
                    else -> "Ce lien ressemble fortement à une arnaque / hameçonnage ou à un piège. Il est conseillé de NE PAS l'ouvrir."
                }, 14f, top = 6,
            )
        }

        c.label(
            "Google Safe Browsing : " + when (sbState) {
                null -> "non configuré (Réglages)"
                "checking" -> "vérification en cours…"
                "clean" -> "aucun signalement"
                "threat" -> "site signalé !"
                else -> sbState!!
            }, 13f, color = Ui.MUTED, top = 10,
        )

        originalUrl?.let {
            c.card().apply {
                label("Lien d'origine ($hops redirection(s)) :", 12f, true, Ui.MUTED)
                label(it, 12f, color = Ui.MUTED).setTextIsSelectable(true)
            }
        }

        c.label("Site réel", 13f, color = Ui.MUTED, top = 14)
        c.label(r.displayHost.ifEmpty { "(inconnu)" }, 20f, true)
        c.label("Adresse complète", 13f, color = Ui.MUTED, top = 10)
        c.label(r.url, 13f).raw().setTextIsSelectable(true)

        c.label("Analyse", 17f, true, top = 18)
        if (r.findings.isEmpty()) c.label("Aucun signal suspect détecté.", 14f)
        r.findings.forEach { c.label("• ${it.message}  (+${it.points})", 14f, top = 6) }

        if (blockReason == null) {
            when (r.verdict) {
                Verdict.SAFE -> c.btn("Ouvrir le lien", r.verdict.solid) { openInBrowser(r.url, "ouvert") }
                Verdict.CAUTION -> c.btn("Ouvrir quand même", r.verdict.solid) { openInBrowser(r.url, "ouvert") }
                Verdict.DANGER -> c.btn("Ouvrir malgré le danger", r.verdict.solid) {
                    AlertDialog.Builder(this)
                        .setTitle("Êtes-vous sûr ?")
                        .setMessage("Ce lien est classé DANGEREUX. Ne saisissez aucun mot de passe, code ou carte bancaire et n'installez rien.")
                        .setPositiveButton("Ouvrir quand même") { _, _ -> openInBrowser(r.url, "ouvert malgré danger") }
                        .setNegativeButton("Annuler", null)
                        .showTr()
                }
            }
            if (originalUrl == null && r.host.isNotEmpty()) {
                c.btn("Voir la vraie destination (redirections)") { expand() }
            }
        }
        c.btn("Copier le lien") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("lien", r.url))
            toast("Lien copié")
        }
        if (blockReason == null && r.verdict != Verdict.DANGER && r.domain.isNotEmpty()) {
            c.btn("Toujours faire confiance à ${r.domain}", Ui.DARK) {
                Lock.confirm(this, "Code requis pour ne plus contrôler ${r.domain}") {
                    Store.trust(this, r.domain)
                    toast("${r.domain} ne sera plus contrôlé")
                }
            }
        }
        if (blockReason == null && r.domain.isNotEmpty()) {
            c.btn("Bloquer ${r.domain} pour toujours", Ui.DARK) {
                Store.addBlocked(this, r.domain)
                toast("${r.domain} est bloqué")
                decide("bloqué (ajouté à la liste)")
                finish()
            }
        }
        c.btn("Fermer sans ouvrir", Ui.DARK) {
            decide("bloqué")
            finish()
        }
    }

    private fun expand() {
        toast("Recherche de la destination… (la page n'est pas ouverte)")
        val start = report.url
        Thread {
            val result = runCatching { UrlAnalyzer.resolveRedirects(start) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { chain ->
                    if (chain.size > 1) {
                        originalUrl = start
                        hops = chain.size - 1
                        report = Feeds.enrich(this, UrlAnalyzer.analyze(chain.last()))
                        blockReason = Store.blockReason(this, report)
                        blockReason?.let { report = UrlAnalyzer.withFinding(report, Finding(100, it)) }
                        val key = Store.sbKey(this)
                        if (key.isNotEmpty() && blockReason == null) runSafeBrowsing(key, false) else sbState = null
                        render()
                    } else {
                        toast("Pas de redirection : c'est bien la destination finale.")
                    }
                }.onFailure { toast("Impossible de joindre le site : ${it.message}") }
            }
        }.start()
    }

    /** Ouvre le lien dans un VRAI navigateur (pas Mirador), pour éviter une boucle. */
    private fun openInBrowser(url: String, action: String) {
        // Seuls les liens web s'ouvrent (pas intent://, market://, file://, javascript:…)
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            toast("Ce type de lien ne peut pas être ouvert depuis Mirador.")
            if (!isFinishing) render()
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = packageManager.activities(view, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }

        if (handlers.isEmpty()) {
            toast("Aucun autre navigateur installé. Installez Chrome, Firefox ou Brave.")
            render()
            return
        }

        val preferred = Store.preferredBrowser(this)
        val launch = { pkg: String ->
            try {
                startActivity(Intent(view).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                decide(action)
            } catch (e: Exception) {
                Store.setPreferredBrowser(this, null)
                toast("Impossible d'ouvrir avec ce navigateur")
            }
            finish()
        }

        when {
            preferred != null && preferred in handlers -> launch(preferred)
            handlers.size == 1 -> launch(handlers[0])
            else -> {
                val labels = handlers.map { pkg ->
                    runCatching {
                        packageManager.getApplicationLabel(packageManager.appInfo(pkg)).toString()
                    }.getOrDefault(pkg)
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Ouvrir avec… (choix mémorisé)")
                    .setItems(labels) { _, i ->
                        Store.setPreferredBrowser(this, handlers[i])
                        launch(handlers[i])
                    }
                    .setOnCancelListener { render() }
                    .showTr()
            }
        }
    }

    private fun decide(action: String) {
        if (decided || !::report.isInitialized) return
        decided = true
        Store.addHistory(this, HistoryEntry(System.currentTimeMillis(), report.url, report.verdict, report.score, action))
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) decide(if (blockReason != null) "bloqué (liste)" else "fermé")
        super.onDestroy()
    }
}
