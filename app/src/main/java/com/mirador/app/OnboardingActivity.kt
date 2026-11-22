package com.mirador.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Assistant de premier lancement : active les protections une par une. */
class OnboardingActivity : Activity() {

    private data class Step(
        val iconRes: Int,
        val title: String,
        val text: String,
        val done: () -> Boolean,
        val action: String?,
        val run: (() -> Unit)?,
    )

    private var index = 0

    private val steps: List<Step> by lazy {
        listOf(
            Step(
                R.drawable.ic_shield, "Bienvenue dans Mirador",
                "En 2 minutes, on active ensemble les protections : liens piégés, SMS d'arnaque, logiciels espions, traqueurs… Tout est analysé sur votre téléphone.",
                { false }, null, null,
            ),
            Step(
                R.drawable.ic_link, "Contrôle des liens",
                "Mirador devient votre « navigateur » : chaque lien touché est vérifié AVANT de s'ouvrir, puis ouvert dans votre vrai navigateur.",
                { DeviceAudit.isDefaultBrowser(this) }, "Activer le contrôle des liens",
            ) { requestDefaultBrowser() },
            Step(
                R.drawable.ic_bell, "Alertes",
                "Autorisez les notifications pour être prévenu d'un SMS d'arnaque, d'une appli suspecte ou d'un traqueur.",
                { Notifier.canNotify(this) }, "Autoriser les notifications",
            ) { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) },
            Step(
                R.drawable.ic_chat, "SMS d'arnaque",
                "Mirador analyse les SMS reçus (faux colis, amendes, Ameli, CPF…) et vous alerte. Rien n'est envoyé ailleurs.",
                { Store.smsEnabled(this) && has(Manifest.permission.RECEIVE_SMS) }, "Activer les alertes SMS",
            ) {
                Store.setSmsEnabled(this, true)
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 2)
            },
            Step(
                R.drawable.ic_search, "WhatsApp, Messenger, Telegram…",
                "Pour repérer les arnaques dans vos messageries, activez Mirador dans « Accès aux notifications ».",
                { Store.msgEnabled(this) && MessageGuardService.isEnabled(this) }, "Ouvrir le réglage",
            ) {
                Store.setMsgEnabled(this, true)
                safeStart(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            },
            Step(
                R.drawable.ic_globe, "Filtre DNS",
                "Bloque pubs, traqueurs, sites de malware et de logiciels espions pour toutes les applis. Android va vous demander d'autoriser un « VPN » : il reste sur votre téléphone.",
                { DnsVpnService.running }, "Activer le filtre",
            ) {
                val prep = VpnService.prepare(this)
                if (prep != null) startActivityForResult(prep, 10) else startDns()
            },
            Step(
                R.drawable.ic_lock, "Code PIN",
                "Protège Mirador : sans ce code (ou votre empreinte), personne ne peut couper vos protections.",
                { Lock.hasPin(this) }, null, null,
            ),
            Step(
                R.drawable.ic_shield, "Anti-désinstallation",
                "Empêche de désinstaller Mirador sans passer par un réglage, et vous alerte si quelqu'un essaie.",
                { AdminReceiver.isActive(this) }, "Activer la protection",
            ) { safeStart(AdminReceiver.requestIntent(this)) },
            Step(
                R.drawable.ic_check_circle, "C'est prêt !",
                "Mirador veille sur votre téléphone. Retrouvez votre score de sécurité sur l'accueil ; vous pourrez relancer cet assistant depuis les Réglages.",
                { false }, null, null,
            ),
        )
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt("i") ?: 0
        // Android 13+ : retour (y compris « retour prédictif ») = étape précédente
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    private fun goBack() {
        if (index > 0) { index--; render() } else finishOnboarding()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("i", index)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val s = steps[index]
        val c = screen()
        val last = index == steps.lastIndex

        // Progression
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        steps.indices.forEach { i ->
            dots.addView(android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (i <= index) Ui.PRIMARY else Ui.OUTLINE)
                    cornerRadius = dp(4).toFloat()
                }
            }, LinearLayout.LayoutParams(if (i == index) dp(22) else dp(8), dp(8)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        c.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        c.addView(iconBadge(this, s.iconRes, Ui.ACCENT_TEXT, 96).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(44) }
        })
        c.label(s.title, 26f, true, top = 20).gravity = Gravity.CENTER
        c.label(s.text, 16f, color = Ui.MUTED, top = 12).gravity = Gravity.CENTER

        val done = s.done()
        if (done) {
            c.card(Verdict.SAFE.bg).iconTitle(R.drawable.ic_check_circle, Verdict.SAFE.color, "Déjà activé", 16f).gravity = Gravity.CENTER
        }

        // Étape code PIN : saisie directe
        if (s.iconRes == R.drawable.ic_lock && !done) {
            val p1 = EditText(this).apply {
                hint = "Code (4 chiffres min.)"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
            }
            val p2 = EditText(this).apply {
                hint = "Confirmer le code"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
            }
            c.addView(p1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
            c.addView(p2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            c.btn("Définir le code") {
                val a = p1.text.toString()
                when {
                    a.length < 4 -> toast("Au moins 4 chiffres")
                    a != p2.text.toString() -> toast("Les deux codes sont différents")
                    else -> { Lock.setPin(this, a); next() }
                }
            }
        } else if (!done && s.action != null && s.run != null) {
            c.btn(s.action) { s.run.invoke() }
        }

        // Navigation
        c.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(24)))
        when {
            last -> c.btn("Commencer") { finishOnboarding() }
            index == 0 -> c.btn("C'est parti") { next() }
            done -> c.btn("Continuer") { next() }
            else -> c.btn("Plus tard", Ui.DARK) { next() }
        }
        if (!last && index > 0) {
            c.label("Passer l'assistant", 14f, color = Ui.MUTED, top = 16).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                clickable(dp(12).toFloat()) { finishOnboarding() }
            }
        }
    }

    private fun next() {
        index = (index + 1).coerceAtMost(steps.lastIndex)
        render()
    }

    private fun finishOnboarding() {
        Store.setOnboarded(this)
        finish()
    }

    private fun startDns() {
        Store.setDnsEnabled(this, true)
        startService(Intent(this, DnsVpnService::class.java))
        window.decorView.postDelayed({ if (!isFinishing) render() }, 600)
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) startDns()
    }
    /** Android 12 et avant. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() = goBack()
}
