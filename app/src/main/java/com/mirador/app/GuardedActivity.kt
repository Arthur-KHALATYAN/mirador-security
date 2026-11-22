package com.mirador.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.text.InputType
import android.widget.EditText
import java.security.MessageDigest
import java.security.SecureRandom

/** Code PIN de Mirador (empêche quelqu'un d'autre de désactiver les protections). */
object Lock {
    /** Délai toléré hors de l'appli (ex. fenêtre d'autorisation système) avant de reverrouiller. */
    const val GRACE_MS = 5_000L

    @Volatile private var unlockedSession = false

    /** PBKDF2 : rend chaque essai coûteux, même si les données de l'appli sont extraites. */
    private const val ITERATIONS = 60_000

    fun hasPin(c: Context) = Store.pinHash(c) != null
    fun isLocked(c: Context) = hasPin(c) && !unlockedSession
    @Volatile private var externalUntil = 0L

    fun touch() { unlockedSession = true }
    fun relock() { if (System.currentTimeMillis() > externalUntil) unlockedSession = false }

    /** À appeler avant d'ouvrir un sélecteur de fichier / écran système : pas de reverrouillage au retour. */
    fun allowExternal() { externalUntil = System.currentTimeMillis() + 3 * 60_000L }

    private var sessionFailures = 0

    /** Attente restante (s). Le compteur est enregistré : fermer l'appli ne le remet pas à zéro. */
    fun waitSeconds(c: Context): Long = ((Store.pinBlockedUntil(c) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    fun check(c: Context, pin: String): Boolean {
        if (waitSeconds(c) > 0) return false
        val ok = verify(c, pin)
        if (ok) {
            Store.setPinFails(c, 0)
            sessionFailures = 0
            touch()
        } else {
            sessionFailures++
            val fails = Store.pinFails(c) + 1
            Store.setPinFails(c, fails)
            Store.addLog(c, "intrusions", "Mauvais code saisi dans Mirador (essai n°$sessionFailures)")
            // À partir de 5 erreurs : 30 s, puis le double à chaque erreur (max 1 h)
            if (fails >= 5) {
                val wait = (30_000L shl (fails - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
                Store.setPinBlockedUntil(c, System.currentTimeMillis() + wait)
            }
            if (sessionFailures >= 2 && c is Activity && Store.intruderPhoto(c)) IntruderCam.capture(c)
        }
        return ok
    }

    private fun verify(c: Context, pin: String): Boolean {
        val salt = Store.pinSalt(c) ?: return false
        val stored = Store.pinHash(c) ?: return false
        return if (stored.startsWith("pbkdf2$")) {
            val iter = stored.split('$').getOrNull(1)?.toIntOrNull() ?: return false
            MessageDigest.isEqual(pbkdf2(salt, pin, iter).toByteArray(), stored.toByteArray())
        } else {
            // Ancien format (SHA-256) : on vérifie puis on passe au nouveau format
            val ok = MessageDigest.isEqual(sha256(salt, pin).toByteArray(), stored.toByteArray())
            if (ok) Store.setPin(c, salt, pbkdf2(salt, pin, ITERATIONS))
            ok
        }
    }

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        Store.setPin(c, salt, pbkdf2(salt, pin, ITERATIONS))
        Store.setPinFails(c, 0)
        Store.setPinBlockedUntil(c, 0)
        touch()
    }

    private fun pbkdf2(salt: String, pin: String, iter: Int): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(), iter, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return "pbkdf2$$iter$" + key.joinToString("") { "%02x".format(it) }
    }

    fun clearPin(c: Context) = Store.clearPin(c)

    /** Demande le code avant une action sensible (si un code est défini). */
    fun confirm(a: Activity, title: String, action: () -> Unit) {
        if (!hasPin(a)) return action()
        val input = EditText(a).apply {
            hint = "Code PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
        }
        AlertDialog.Builder(a)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                if (check(a, input.text.toString())) action()
                else a.toast(if (waitSeconds(a) > 0) "Trop d'essais. Réessayez dans ${waitSeconds(a)} s." else "Code incorrect")
            }
            .setNegativeButton("Annuler", null)
            .showTr()
            .also { d ->
                // Curseur directement dans le champ, clavier ouvert
                d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                input.requestFocus()
            }
    }

    private fun sha256(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Écran protégé par le code PIN (si un code est défini). */
abstract class GuardedActivity : Activity() {
    protected var unlocked = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Pas d'aperçu du contenu dans les applis récentes (sinon on y verrait le scan, le journal…)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        else window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    abstract fun render()

    override fun onResume() {
        super.onResume()
        if (Lock.isLocked(this)) {
            unlocked = false
            showLock(autoBiometric = true)
        } else {
            unlocked = true
            render()
        }
    }

    private var pinField: EditText? = null

    /** Place le curseur dans le champ du code et ouvre le clavier. */
    private fun focusPin() {
        val f = pinField ?: return
        f.post {
            f.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(f, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hasBiometric(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            ?.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION")
            getSystemService(android.hardware.biometrics.BiometricManager::class.java)?.canAuthenticate() ==
            android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
        else -> false
    }

    private fun showLock(autoBiometric: Boolean) {
        val c = screen()
        val center = android.view.Gravity.CENTER_HORIZONTAL
        // Espaceurs pour centrer verticalement le bloc
        c.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(1, 0, 1f))

        c.addView(iconBadge(this, R.drawable.ic_lock, Ui.ACCENT_TEXT, 76).apply { (layoutParams as android.widget.LinearLayout.LayoutParams).gravity = center })
        c.label("Mirador est verrouillé", 24f, true, top = 12).gravity = center
        c.label("Entrez votre code pour accéder aux protections.", 15f, color = Ui.MUTED, top = 6).gravity = center

        val pin = EditText(this).apply {
            hint = "• • • •"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
            isSingleLine = true
            gravity = android.view.Gravity.CENTER
            textSize = 24f
            letterSpacing = 0.4f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        pinField = pin
        c.addView(pin, android.widget.LinearLayout.LayoutParams(dp(240), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
            gravity = center
        })

        val submit = {
            when {
                Lock.check(this, pin.text.toString()) -> unlockNow()
                Lock.waitSeconds(this) > 0 -> toast("Trop d'essais. Réessayez dans ${Lock.waitSeconds(this)} s.")
                else -> toast("Code incorrect")
            }
            pin.setText("")
        }
        pin.setOnEditorActionListener { _, _, _ -> submit(); true }
        c.btn("Déverrouiller") { submit() }
        if (Build.VERSION.SDK_INT >= 28 && hasBiometric()) {
            c.btn("Empreinte / visage", Ui.DARK) { biometric() }
        }
        c.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(1, 0, 1.4f))

        if (autoBiometric && Build.VERSION.SDK_INT >= 28 && hasBiometric()) biometric() else focusPin()
    }

    private fun biometric() {
        if (Build.VERSION.SDK_INT < 28) return
        runCatching {
            BiometricPrompt.Builder(this)
                .setTitle(I18n.tr("Déverrouiller Mirador"))
                // « Code PIN » : on ferme l'empreinte et on ouvre directement le clavier
                .setNegativeButton(I18n.tr("Code PIN"), mainExecutor) { _, _ -> focusPin() }
                .build()
                .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        unlockNow()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        focusPin()
                    }
                })
        }.onFailure { focusPin() }
    }

    protected fun unlockNow() {
        unlocked = true
        Lock.touch()
        render()
    }
}
