package com.mirador.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Vérifie si un mot de passe a fuité (Have I Been Pwned – Pwned Passwords).
 * Seuls les 5 premiers caractères de son empreinte SHA-1 sont envoyés : le mot de passe
 * ne quitte jamais le téléphone (méthode « k-anonymat »).
 */
object Pwned {
    fun count(password: String): Result<Int> = runCatching {
        val hash = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val prefix = hash.take(5)
        val suffix = hash.drop(5)
        val conn = (URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Add-Padding", "true")
            setRequestProperty("User-Agent", "Mirador-Android")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().lineSequence()
                .map { it.trim().split(':') }
                .firstOrNull { it.size == 2 && it[0].equals(suffix, ignoreCase = true) }
                ?.get(1)?.toIntOrNull() ?: 0
        } finally {
            conn.disconnect()
        }
    }
}

class PwnedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Empêche les captures d'écran de cet écran
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        render()
    }

    private fun render() {
        val c = screen()
        c.header(R.drawable.ic_lock, 0xFFF59E0B.toInt(), "Mot de passe piraté ?")
        c.label(
            "Vérifie si un mot de passe apparaît dans les fuites de données connues (plus de 900 millions de mots de passe). " +
                "Votre mot de passe n'est JAMAIS envoyé : seul un petit morceau de son empreinte chiffrée l'est (méthode Have I Been Pwned).",
            13f, color = Ui.MUTED,
        )
        val input = EditText(this).apply {
            hint = "Mot de passe à tester"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // pas de police « machine à écrire »
            isSingleLine = true
        }
        c.addView(input)
        val result = c.box()
        c.btn("Vérifier") {
            val pwd = input.text.toString()
            input.setText("")
            if (pwd.isEmpty()) return@btn
            result.removeAllViews()
            result.label("Vérification…", 14f, color = Ui.MUTED)
            Thread {
                val res = Pwned.count(pwd)
                runOnUiThread { if (!isFinishing) show(result, res) }
            }.start()
        }
    }

    private fun show(box: LinearLayout, res: Result<Int>) {
        box.removeAllViews()
        res.onSuccess { n ->
            if (n > 0) {
                box.card(Verdict.DANGER.bg).apply {
                    iconTitle(R.drawable.ic_block, Verdict.DANGER.color, "Ce mot de passe a fuité $n fois", 17f)
                    label("Les pirates l'essaient en premier. Changez-le partout où vous l'utilisez et activez la double authentification.", 14f)
                }
            } else {
                box.card(Verdict.SAFE.bg).apply {
                    iconTitle(R.drawable.ic_check_circle, Verdict.SAFE.color, "Absent des fuites connues", 17f)
                    label("Bon signe. Utilisez quand même un mot de passe différent pour chaque site.", 14f)
                }
            }
        }.onFailure { box.label("Impossible de vérifier : ${it.message}", 14f, color = Verdict.CAUTION.color) }
    }
}
