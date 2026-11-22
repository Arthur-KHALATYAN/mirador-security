package com.mirador.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : GuardedActivity() {

    /** Lien trouvé dans le presse-papiers, proposé à la vérification. */
    private var clipUrl: String? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ : le presse-papiers n'est lisible que quand l'appli a le focus
        if (!hasFocus || !unlocked) return
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: return
        val url = ScamText.extractUrls(text.take(3000)).firstOrNull() ?: return
        val key = url.hashCode().toString()
        if (key == Store.lastClip(this)) return
        Store.setLastClip(this, key)
        clipUrl = url
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.init(this)
        WatchJob.schedule(this)
        // Première photo de l'état du téléphone (sert de référence pour les alertes)
        Thread { runCatching { WatchJob.runChecks(applicationContext) } }.start()
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this) && !Store.askedNotif(this)) {
            Store.setAskedNotif(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        if (Store.monitorEnabled(this)) SensorMonitorService.start(this)
        // Premier lancement : assistant de configuration
        if (!Store.onboarded(this) && savedInstanceState == null) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    /** Deux boutons côte à côte. */
    private fun LinearLayout.twoButtons(a: String, aColor: Int, onA: () -> Unit, b: String, bColor: Int, onB: () -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple(a, aColor, onA), Triple(b, bColor, onB)).forEachIndexed { i, (t, col, f) ->
            val w = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            w.btn(t, col, f)
            row.addView(w, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
        addView(row)
    }

    override fun render() {
        val ctx = this
        val c = screen()
        StatusWidget.refresh(this)
        val on = Verdict.SAFE.color
        val off = Ui.MUTED

        // ---------- En-tête ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.label("Mirador", 28f, true, top = 0)
        titles.label("Votre bouclier anti-arnaque et anti-espion", 13f, color = Ui.MUTED, top = 0)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (Lock.hasPin(this)) {
            header.addView(pill(this, "PIN", Ui.ACCENT_TEXT).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_lock, Ui.ACCENT_TEXT, 14), null, null, null)
                compoundDrawablePadding = dp(4)
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.SURFACE); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Ui.OUTLINE)
            }
            clickable(dp(22).toFloat()) { open(SettingsActivity::class.java) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        c.addView(header)

        // ---------- Score de sécurité ----------
        val items = SecurityScore.items(this)
        val score = SecurityScore.score(items)
        val todo = items.filter { !it.ok }.sortedByDescending { it.points }
        val (accent, state) = when {
            score >= 80 -> 0xFF34D399.toInt() to "Bien protégé"
            score >= 50 -> 0xFFFBBF24.toInt() to "Protection à renforcer"
            else -> 0xFFF87171.toInt() to "Téléphone vulnérable"
        }
        c.heroCard(0xFF1E293B.toInt(), 0xFF0B1220.toInt()).apply {
            addView(ScoreRing(ctx, score, accent), LinearLayout.LayoutParams(dp(100), dp(100)))
            val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            t.label("SCORE DE SÉCURITÉ", 11f, color = 0x99FFFFFF.toInt(), top = 0).letterSpacing = 0.1f
            t.label(state, 20f, true, android.graphics.Color.WHITE, top = 6)
            t.addView(pill(ctx, if (todo.isEmpty()) "Toutes les protections sont actives" else "${todo.size} action(s) recommandée(s)", accent),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(20) })
        }
        if (todo.isNotEmpty()) {
            c.card().apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                todo.take(4).forEachIndexed { i, item ->
                    if (i > 0) divider()
                    val click: (() -> Unit)? = if (item.title == SecurityScore.PATCH) ({ patchDialog() }) else item.action?.let { a -> { safeStart(a) } }
                    row(R.drawable.ic_add, Ui.ACCENT_TEXT, item.title, "+${item.points} points", onClick = click)
                }
            }
        }

        // ---------- Aide ----------
        c.card(Verdict.DANGER.bg).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            row(R.drawable.ic_help, Verdict.DANGER.solid, "Je pense être espionné(e)", "Guide pas à pas et contacts d'aide", Verdict.DANGER.color) { open(GuideActivity::class.java) }
        }

        clipUrl?.let { url ->
            c.card(Verdict.CAUTION.bg).apply {
                label("📋 Lien copié détecté", 16f, true, Verdict.CAUTION.color)
                label(url, 13f)
                btn("Vérifier ce lien", Verdict.CAUTION.solid) {
                    clipUrl = null
                    startActivity(Intent(ctx, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, url))
                }
                btn("Ignorer", Ui.DARK) { clipUrl = null; render() }
            }
        }

        // ---------- Vérifier un lien ----------
        c.label("Vérifier un lien", 18f, true, top = 18)
        val active = DeviceAudit.isDefaultBrowser(this)
        c.card().apply {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(iconBadge(ctx, R.drawable.ic_link, Ui.ACCENT_TEXT, 36))
            head.addView(android.widget.TextView(ctx).apply {
                text = "Contrôle automatique des liens"
                setPadding(dp(12), 0, 0, 0)
                textSize = 15f
                typeface = Ui.medium
                setTextColor(Ui.TEXT)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(pill(ctx, if (active) "Actif" else "Inactif", if (active) on else Verdict.DANGER.color))
            addView(head)
            label(
                if (active) "Chaque lien ouvert depuis un SMS, un mail ou WhatsApp est vérifié avant de s'ouvrir."
                else "Faites de Mirador votre navigateur par défaut : chaque lien sera vérifié, puis ouvert dans votre vrai navigateur.",
                13f, color = Ui.MUTED, top = 4,
            )
            if (!active) btn("Activer le contrôle des liens", Verdict.DANGER.solid) { requestDefaultBrowser() }
            addView(Switch(ctx).apply {
                text = "Ouvrir directement les liens sûrs"
                textSize = 14f
                setTextColor(Ui.TEXT)
                isChecked = Store.autoOpenSafe(ctx)
                setOnCheckedChangeListener { _, v -> Store.setAutoOpenSafe(ctx, v) }
                setPadding(0, dp(10), 0, dp(10))
            })
            val input = EditText(ctx).apply {
                hint = "Collez un lien reçu par SMS, mail…"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_GO
            }
            addView(input)
            val analyse = {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) startActivity(Intent(ctx, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, t))
            }
            input.setOnEditorActionListener { _, _, _ -> analyse(); true }
            twoButtons("Analyser", Ui.PRIMARY, analyse, "QR code", Ui.DARK) { QrScanner.scan(ctx) }
        }

        // ---------- Protections ----------
        c.label("Protections", 18f, true, top = 18)
        val dangerApps = Store.notifiedDanger(this).size
        val msgOn = Store.msgEnabled(this) && MessageGuardService.isEnabled(this)
        c.tiles(
            listOf(
                Tile(R.drawable.ic_eye, 0xFF6366F1.toInt(), "Anti-spy", if (dangerApps > 0) "$dangerApps appli(s) à risque" else "Rien à signaler",
                    if (dangerApps > 0) Verdict.DANGER.color else on) { open(ScanActivity::class.java) },
                Tile(R.drawable.ic_chat, 0xFF0EA5E9.toInt(), "SMS & messageries",
                    when { Store.smsEnabled(this) && msgOn -> "Actif"; Store.smsEnabled(this) || msgOn -> "Partiel"; else -> "Inactif" },
                    if (Store.smsEnabled(this) || msgOn) on else off) { open(SmsScanActivity::class.java) },
                Tile(R.drawable.ic_globe, 0xFF10B981.toInt(), "Filtre DNS", if (DnsVpnService.running) "Actif" else "Inactif",
                    if (DnsVpnService.running) on else off) { open(DnsActivity::class.java) },
                Tile(R.drawable.ic_mic, 0xFFF59E0B.toInt(), "Micro & caméra", if (Store.monitorEnabled(this)) "Surveillé" else "Inactif",
                    if (Store.monitorEnabled(this)) on else off) { open(SensorLogActivity::class.java) },
                Tile(R.drawable.ic_bluetooth, 0xFF8B5CF6.toInt(), "Traqueurs", if (Trackers.hasPermissions(this)) "Surveillé" else "À activer",
                    if (Trackers.hasPermissions(this)) on else off) { open(TrackerActivity::class.java) },
                Tile(R.drawable.ic_upload, 0xFFEC4899.toInt(), "Données envoyées", "7 derniers jours", off) { open(DataUsageActivity::class.java) },
            ),
        )

        // ---------- Outils ----------
        c.label("Outils", 18f, true, top = 18)
        c.card().apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            row(R.drawable.ic_key, 0xFF6366F1.toInt(), "Permissions des applis", "Qui a accès au micro, à la position, aux SMS…") { open(PermissionsActivity::class.java) }
            divider()
            row(R.drawable.ic_phone_forward, 0xFF0EA5E9.toInt(), "Renvoi d'appels", "Vos appels sont-ils détournés ?") { open(CallForwardActivity::class.java) }
            divider()
            row(R.drawable.ic_android, 0xFF10B981.toInt(), "Analyser un APK", "Avant d'installer une appli hors Play Store") { open(ApkCheckActivity::class.java) }
            divider()
            row(R.drawable.ic_lock, 0xFFF59E0B.toInt(), "Mot de passe piraté ?", "Vérifie les fuites sans l'envoyer") { open(PwnedActivity::class.java) }
            divider()
            row(R.drawable.ic_report, 0xFF64748B.toInt(), "Rapport de sécurité", "À partager avec quelqu'un qui peut aider") { Report.share(ctx) }
        }

        // ---------- Audit ----------
        c.label("Audit du téléphone", 18f, true, top = 18)
        c.card().apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            DeviceAudit.run(ctx).forEachIndexed { i, chk ->
                if (i > 0) divider()
                val a = chk.fixAction
                val isPatch = chk.title == "Mises à jour de sécurité"
                row(
                    when { chk.ok -> R.drawable.ic_check_circle; chk.accepted -> R.drawable.ic_help; else -> R.drawable.ic_warning },
                    when { chk.ok -> Verdict.SAFE.solid; chk.accepted -> Ui.MUTED; else -> Verdict.CAUTION.solid }, chk.title,
                    if (chk.ok) null else chk.detail,
                    when { chk.ok -> Ui.TEXT; chk.accepted -> Ui.TEXT; else -> Verdict.CAUTION.color },
                    onClick = when {
                        chk.ok -> null
                        isPatch -> ({ patchDialog() })
                        a != null -> ({ safeStart(Intent(a)) })
                        else -> null
                    },
                )
            }
        }
        val last = Store.lastWatch(this)
        if (last > 0) {
            c.label("Dernière vérification automatique : " + SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(last)), 12f, color = Ui.MUTED, top = 8)
        }

        // ---------- Historique ----------
        val hist = Store.history(this)
        if (hist.isNotEmpty()) {
            c.label("Derniers liens", 18f, true, top = 18)
            val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            c.card().apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                hist.take(6).forEachIndexed { i, e ->
                    if (i > 0) divider()
                    val host = android.net.Uri.parse(e.url).host ?: e.url
                    row(R.drawable.ic_link, e.verdict.solid, host, "${e.verdict.label} • ${e.action} • ${fmt.format(Date(e.time))}") {
                        startActivity(Intent(ctx, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, e.url))
                    }
                }
            }
            c.btn("Effacer l'historique", Ui.DARK) { Store.clearHistory(ctx); render() }
        }
    }

    private fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), 1)
                return
            }
        }
        safeStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    /** Mises à jour de sécurité : chercher une mise à jour, ou signaler un vieux téléphone qui n'en reçoit plus. */
    private fun patchDialog() {
        val patch = Build.VERSION.SECURITY_PATCH
        val accepted = Store.noUpdatesPatch(this) == patch
        val advice = "Pour compenser :\n" +
            "• gardez le contrôle des liens et le filtre DNS actifs ;\n" +
            "• n'installez que des applis du Play Store ;\n" +
            "• mettez à jour Chrome et « Android System WebView » depuis le Play Store ;\n" +
            "• évitez les Wi-Fi publics ;\n" +
            "• si possible, une ROM alternative (ex. LineageOS) peut prolonger les mises à jour."
        val b = android.app.AlertDialog.Builder(this).setTitle("Mises à jour de sécurité")
        if (accepted) {
            b.setMessage("Vous avez indiqué que ce téléphone ne reçoit plus de mises à jour (correctif du $patch).\n\n$advice")
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Annuler ce choix") { _, _ -> Store.setNoUpdatesPatch(this, null); render() }
        } else {
            b.setMessage("Dernier correctif installé : $patch.\n\nCherchez d'abord une mise à jour dans Paramètres > Mise à jour logicielle. " +
                "Si votre téléphone n'en reçoit plus (modèle trop ancien), indiquez-le : ce point ne comptera plus dans le score.")
                .setPositiveButton("Chercher une mise à jour") { _, _ -> safeStart(Intent(Settings.ACTION_SETTINGS)) }
                .setNeutralButton("Plus de mises à jour") { _, _ ->
                    Store.setNoUpdatesPatch(this, patch)
                    render()
                    android.app.AlertDialog.Builder(this).setTitle("C'est noté").setMessage(advice).setPositiveButton("OK", null).showTr()
                }
                .setNegativeButton("Annuler", null)
        }
        b.showTr()
    }
}
