package com.mirador.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * « Protection contre la désinstallation » : tant que ce droit est actif, Mirador ne peut pas
 * être désinstallé directement. Il faut d'abord le désactiver, ce qui déclenche une alerte.
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun component(c: Context) = ComponentName(c, AdminReceiver::class.java)

        fun isActive(c: Context) =
            c.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(c)) == true

        fun requestIntent(c: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Empêche la désinstallation de Mirador sans passer d'abord par ce réglage, et vous alerte si quelqu'un essaie.",
            )

        fun deactivate(c: Context) {
            c.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(component(c))
        }
    }

    /** Échec de déverrouillage du TÉLÉPHONE (nécessite la politique « watch-login »). */
    override fun onPasswordFailed(c: Context, intent: Intent, user: android.os.UserHandle) {
        Store.addLog(c, "intrusions", "🔓 Échec de déverrouillage du téléphone")
    }

    override fun onDisableRequested(c: Context, intent: Intent): CharSequence {
        Notifier.alert(
            c, 4401,
            "⚠️ Tentative de désactivation de Mirador",
            "Quelqu'un essaie de retirer la protection contre la désinstallation. Si ce n'est pas vous, annulez et vérifiez votre téléphone.",
            null,
        )
        return "Attention : Mirador ne vous protégera plus et pourra être désinstallé. Continuer ?"
    }

    override fun onDisabled(c: Context, intent: Intent) {
        Notifier.alert(
            c, 4402,
            "⛔ Protection anti-désinstallation désactivée",
            "Mirador peut maintenant être désinstallé. Si ce n'est pas vous, réactivez-la et changez le code de votre téléphone.",
            null,
        )
    }
}
