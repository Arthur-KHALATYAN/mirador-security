package com.mirador.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Renvoi d'appels : un moyen classique d'espionnage (vos appels / codes par SMS vocaux
 * arrivent sur le téléphone de quelqu'un d'autre).
 */
class CallForwardActivity : GuardedActivity() {

    /** null = inconnu, true = renvoi inconditionnel actif. */
    private var forwarding: Boolean? = null
    private var callback: Any? = null

    private fun dial(code: String) {
        Lock.allowExternal()
        safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code))))
    }

    override fun onStart() {
        super.onStart()
        listen()
    }

    override fun onStop() {
        unlisten()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallForwardingIndicatorListener {
                    override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                        forwarding = cfi
                        if (unlocked) render()
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                callback = cb
            } else {
                val l = object : android.telephony.PhoneStateListener() {
                    override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                        forwarding = cfi
                        if (unlocked) render()
                    }
                }
                tm.listen(l, android.telephony.PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR)
                callback = l
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun unlisten() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        val cb = callback ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
            else if (cb is android.telephony.PhoneStateListener) tm.listen(cb, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
        callback = null
    }

    override fun render() {
        val c = screen()
        c.header(R.drawable.ic_phone_forward, 0xFF0EA5E9.toInt(), "Renvoi d'appels")
        c.label(
            "Quelqu'un ayant eu votre téléphone en main peut renvoyer vos appels (et les codes de validation par appel) vers son numéro. Vérifiez-le ici.",
            13f, color = Ui.MUTED,
        )

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            c.card().apply {
                label("Autoriser Mirador à lire l'état du téléphone permet de détecter automatiquement un renvoi permanent.", 13f)
                btn("Autoriser", Ui.DARK) { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 30) }
            }
        } else {
            val v = when (forwarding) {
                true -> Verdict.DANGER
                false -> Verdict.SAFE
                null -> Verdict.CAUTION
            }
            c.card(v.bg).iconTitle(v.iconRes, v.color,
                when (forwarding) {
                    true -> "Un renvoi PERMANENT de vos appels est actif !"
                    false -> "Aucun renvoi permanent détecté"
                    null -> "Détection en cours…"
                }, 16f,
            )
        }

        c.label("Vérifier avec votre opérateur", 17f, true, top = 18)
        c.label("Ces codes ouvrent le clavier du téléphone : appuyez sur Appeler, la réponse de l'opérateur s'affiche.", 13f, color = Ui.MUTED)
        c.btn("Vérifier le renvoi permanent (*#21#)") { dial("*#21#") }
        c.btn("Renvoi si pas de réponse (*#61#)", Ui.DARK) { dial("*#61#") }
        c.btn("Renvoi si injoignable (*#62#)", Ui.DARK) { dial("*#62#") }
        c.btn("Renvoi si occupé (*#67#)", Ui.DARK) { dial("*#67#") }
        c.btn("Annuler TOUS les renvois (##002#)", Verdict.DANGER.solid) { dial("##002#") }
        c.label(
            "Le renvoi vers votre propre messagerie vocale est normal. Un renvoi vers un numéro inconnu ne l'est pas : annulez-le et changez le code de votre téléphone.",
            12f, color = Ui.MUTED, top = 12,
        )
    }
}
