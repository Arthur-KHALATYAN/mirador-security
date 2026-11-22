package com.mirador.app

import android.app.Activity
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.Switch

class SettingsActivity : GuardedActivity() {

    override fun render() {
        val ctx = this
        val c = screen()
        c.header(R.drawable.ic_settings, 0xFF64748B.toInt(), "Réglages")

        // ---------- Langue & assistant ----------
        c.label("Langue", 18f, true, top = 18)
        c.card().apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val cur = Store.lang(ctx)
            listOf("auto" to "Automatique (langue du téléphone)", "fr" to "Français", "en" to "English").forEachIndexed { i, (code, name) ->
                if (i > 0) divider()
                row(if (cur == code) R.drawable.ic_radio_on else R.drawable.ic_radio_off, Ui.ACCENT_TEXT, name) {
                    Store.setLang(ctx, code)
                    I18n.init(ctx)
                    recreate()
                }
            }
        }
        c.btn("Relancer l'assistant de configuration", Ui.DARK) {
            startActivity(android.content.Intent(ctx, OnboardingActivity::class.java))
        }

        // ---------- Code PIN ----------
        c.label("Code de verrouillage", 18f, true, top = 18)
        c.label("Protège Mirador : sans le code (ou votre empreinte), personne ne peut couper les protections depuis l'appli.", 13f, color = Ui.MUTED)
        val pin1 = EditText(this).apply {
            hint = if (Lock.hasPin(ctx)) "Nouveau code (4 chiffres min.)" else "Code (4 chiffres min.)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
        }
        val pin2 = EditText(this).apply {
            hint = "Confirmer le code"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
        }
        c.addView(pin1)
        c.addView(pin2)
        c.btn(if (Lock.hasPin(this)) "Changer le code" else "Définir le code") {
            val a = pin1.text.toString()
            when {
                a.length < 4 -> toast("Au moins 4 chiffres")
                a != pin2.text.toString() -> toast("Les deux codes sont différents")
                else -> { Lock.setPin(ctx, a); toast("Code enregistré"); render() }
            }
        }
        if (Lock.hasPin(this)) {
            c.btn("Supprimer le code", Ui.DARK) { Lock.clearPin(ctx); toast("Code supprimé"); render() }
        }

        // ---------- Anti-désinstallation ----------
        c.label("Protection contre la désinstallation", 18f, true, top = 22)
        val admin = AdminReceiver.isActive(this)
        val av = if (admin) Verdict.SAFE else Verdict.CAUTION
        c.card(av.bg).apply {
            iconTitle(av.iconRes, av.color, if (admin) "Active" else "Inactive", 15f)
            label(
                "Empêche de désinstaller Mirador directement : il faut d'abord retirer ce droit (Paramètres > Sécurité > Applis d'administration), et vous êtes alerté si quelqu'un le fait.",
                13f,
            )
            if (admin) {
                btn("Désactiver", Ui.DARK) { AdminReceiver.deactivate(ctx); toast("Protection désactivée"); render() }
            } else {
                btn("Activer", av.solid) { Lock.allowExternal(); safeStart(AdminReceiver.requestIntent(ctx)) }
            }
            label("Si vous l'aviez déjà activée avant cette version, désactivez-la puis réactivez-la pour noter aussi les échecs de déverrouillage du téléphone.", 11f, color = Ui.MUTED, top = 6)
        }

        // ---------- Intrus ----------
        c.label("Intrus", 18f, true, top = 22)
        c.addView(Switch(this).apply {
            text = "Photo de l'intrus (caméra avant) après 2 mauvais codes Mirador"
            setTextColor(Ui.TEXT)
            isChecked = Store.intruderPhoto(ctx) && IntruderCam.hasCamera(ctx)
            setOnCheckedChangeListener { _, on ->
                Store.setIntruderPhoto(ctx, on)
                if (on && !IntruderCam.hasCamera(ctx)) requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 50)
            }
            setPadding(0, dp(8), 0, dp(4))
        })
        if (!Lock.hasPin(this)) c.label("Nécessite un code PIN (ci-dessus).", 12f, color = Ui.MUTED)
        val photos = IntruderCam.photos(this)
        photos.take(6).forEach { f ->
            c.card().apply {
                iconTitle(R.drawable.ic_camera, Ui.TEXT, java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date(f.lastModified())), 13f)
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                android.graphics.BitmapFactory.decodeFile(f.path, opts)?.let { bmp ->
                    addView(android.widget.ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        // la caméra avant enregistre l'image couchée
                        rotation = 270f
                    }, android.widget.LinearLayout.LayoutParams(dp(220), dp(220)))
                }
            }
        }
        if (photos.isNotEmpty()) c.btn("Supprimer les photos", Ui.DARK) { photos.forEach { it.delete() }; render() }
        val intrusions = Store.log(this, "intrusions")
        if (intrusions.isNotEmpty()) {
            c.card().apply {
                label("Journal des tentatives", 15f, true)
                val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE)
                intrusions.take(20).forEach { (t, m) -> label("${fmt.format(java.util.Date(t))} – $m", 13f, top = 2) }
                btn("Effacer le journal", Ui.DARK) { Store.clearLog(ctx, "intrusions"); render() }
            }
        }

        // ---------- Sauvegarde ----------
        c.label("Sauvegarde des réglages", 18f, true, top = 22)
        c.label("Liste de blocage, domaines de confiance, préférences, clé Safe Browsing (pas le code PIN). Pratique pour un nouveau téléphone.", 13f, color = Ui.MUTED)
        c.btn("Exporter les réglages") {
            Lock.allowExternal()
            startActivityForResult(
                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_TITLE, "mirador-reglages.json"),
                60,
            )
        }
        c.btn("Importer des réglages", Ui.DARK) {
            Lock.allowExternal()
            startActivityForResult(
                android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                61,
            )
        }

        // ---------- Bases de menaces ----------
        c.label("Bases de menaces (gratuites, sans clé)", 18f, true, top = 22)
        Stalkerware.ensureLoaded(this)
        PhishFeed.ensureLoaded(this)
        val upd = Store.feedsUpdated(this)
        c.label(
            "• Logiciels espions connus (Echap) : ${Stalkerware.appCount} applis, ${Stalkerware.domains.size} sites\n" +
                "• Liens de phishing / malware (OpenPhish, URLhaus) : ${PhishFeed.size}\n" +
                "Mise à jour : " + (if (upd > 0) java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(upd)) else "jamais") +
                " (automatique chaque jour)",
            13f,
        )
        c.btn("Mettre à jour maintenant") {
            toast("Téléchargement…")
            Thread {
                val msg = Feeds.updateAll(ctx)
                runOnUiThread { toast(msg); if (unlocked) render() }
            }.start()
        }

        // ---------- Safe Browsing ----------
        c.label("Google Safe Browsing", 18f, true, top = 18)
        c.label(
            "Compare chaque lien à la base Google des sites d'arnaque et de malware (le lien est envoyé à Google).\n" +
                "Clé gratuite : console.cloud.google.com → créer un projet → activer « Safe Browsing API » → Identifiants → Créer une clé API.",
            13f, color = Ui.MUTED,
        )
        val keyInput = EditText(this).apply {
            hint = "Clé API (AIza…)"
            setText(Store.sbKey(ctx))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD // clé masquée
            typeface = android.graphics.Typeface.DEFAULT
            isSingleLine = true
        }
        c.addView(keyInput)
        c.btn("Enregistrer la clé") {
            Store.setSbKey(ctx, keyInput.text.toString())
            toast(if (Store.sbKey(ctx).isEmpty()) "Safe Browsing désactivé" else "Clé enregistrée")
        }
        c.btn("Tester la clé", Ui.DARK) {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) { toast("Entrez une clé"); return@btn }
            Thread {
                val res = SafeBrowsing.check(ctx, key, SafeBrowsing.TEST_URL)
                runOnUiThread {
                    res.onSuccess { toast(if (it.isNotEmpty()) "✅ Clé valide, Safe Browsing fonctionne" else "Réponse vide : vérifiez que l'API est activée") }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.start()
        }

        // ---------- Liste de blocage ----------
        c.label("Liste de blocage", 18f, true, top = 22)
        c.addView(Switch(this).apply {
            text = "Bloquer tous les liens qui téléchargent une application (.apk)"
            setTextColor(Ui.TEXT)
            isChecked = Store.blockApk(ctx)
            setOnCheckedChangeListener { _, on -> Store.setBlockApk(ctx, on) }
            setPadding(0, dp(8), 0, dp(4))
        })
        val domainInput = EditText(this).apply {
            hint = "Domaine à bloquer (ex. site-arnaque.com)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        c.addView(domainInput)
        c.btn("Bloquer ce domaine") {
            val raw = domainInput.text.toString().trim().lowercase()
            val host = Uri.parse(if (raw.contains("://")) raw else "https://$raw").host?.removePrefix("www.")
            if (host.isNullOrBlank() || !host.contains('.')) {
                toast("Domaine invalide")
            } else {
                Store.addBlocked(ctx, host)
                reloadDns()
                toast("$host bloqué")
                render()
            }
        }
        Store.blockedDomains(this).sorted().forEach { d ->
            c.card().apply {
                iconTitle(R.drawable.ic_block, Verdict.DANGER.color, "$d   (touchez pour débloquer)", 14f)
                setOnClickListener { Store.removeBlocked(ctx, d); reloadDns(); render() }
            }
        }

        // ---------- Confiance ----------
        val trusted = Store.trusted(this)
        if (trusted.isNotEmpty()) {
            c.label("Domaines de confiance (jamais contrôlés)", 18f, true, top = 22)
            trusted.sorted().forEach { d ->
                c.card().apply {
                    iconTitle(R.drawable.ic_check_circle, Verdict.SAFE.color, "$d   (touchez pour retirer)", 14f)
                    setOnClickListener { Store.untrust(ctx, d); render() }
                }
            }
        }

        // ---------- Navigateur ----------
        if (Store.preferredBrowser(this) != null) {
            c.label("Navigateur", 18f, true, top = 22)
            c.btn("Changer le navigateur utilisé pour ouvrir les liens", Ui.DARK) {
                Store.setPreferredBrowser(ctx, null)
                toast("Le choix vous sera redemandé au prochain lien")
                render()
            }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        runCatching {
            when (requestCode) {
                60 -> contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.exportJson(this).toByteArray()) }
                61 -> {
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("fichier illisible")
                    Store.importJson(this, json)
                    reloadDns()
                }
            }
        }.onSuccess { toast(if (requestCode == 60) "Réglages exportés" else "Réglages importés") }
            .onFailure { toast("Erreur : ${it.message}") }
    }

    private fun reloadDns() {
        if (DnsBlocklist.loaded) Thread { DnsBlocklist.load(this) }.start()
    }
}
