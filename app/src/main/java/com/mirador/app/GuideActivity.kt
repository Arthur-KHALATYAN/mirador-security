package com.mirador.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Guide pas à pas « Je pense être espionné(e) ». */
class GuideActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val c = screen()
        col = c
        c.header(R.drawable.ic_help, Verdict.DANGER.solid, "Je pense être espionné(e)")
        c.label("Suivez ces étapes dans l'ordre. Prenez votre temps.", 13f, color = Ui.MUTED)

        c.card(Verdict.DANGER.bg).apply {
            label("1. Votre sécurité d'abord", 16f, true, Verdict.DANGER.color)
            label(
                "Si la personne qui vous surveille est violente ou proche de vous (conjoint, ex, famille), " +
                    "supprimer le logiciel espion peut l'alerter et aggraver la situation. " +
                    "Parlez-en d'abord à un professionnel. En cas de danger immédiat : 17 (ou SMS au 114).",
                14f,
            )
            btn("Appeler le 3919 (violences, gratuit, anonyme)", Verdict.DANGER.solid) { dial("3919") }
            btn("Appeler le 17", Ui.DARK) { dial("17") }
        }

        step(
            "2. Gardez des preuves",
            "Avant de supprimer quoi que ce soit : faites des captures d'écran des applis suspectes et partagez le rapport Mirador " +
                "vers une adresse mail sûre. Ces éléments servent pour une plainte.",
        ) { btn("Partager le rapport de sécurité", Ui.DARK) { Report.share(ctx) } }

        step(
            "3. Cherchez les logiciels espions",
            "Lancez le scan. Regardez surtout les applis avec Accessibilité, Administrateur, lecture des notifications, cachées ou installées hors Play Store.",
        ) {
            btn("Scanner les applications") { startActivity(Intent(ctx, ScanActivity::class.java)) }
            btn("Voir les permissions", Ui.DARK) { startActivity(Intent(ctx, PermissionsActivity::class.java)) }
        }

        step(
            "4. Vérifiez vos comptes (depuis un AUTRE appareil si possible)",
            "• Google : appareils connectés et activité récente → déconnectez ce que vous ne reconnaissez pas, changez le mot de passe, activez la validation en 2 étapes.\n" +
                "• WhatsApp : Paramètres > Appareils connectés → déconnectez tout appareil inconnu (WhatsApp Web).\n" +
                "• Messenger, Instagram, Snapchat, iCloud : même vérification des sessions actives.\n" +
                "• Google Maps : Partage de position → arrêtez les partages inconnus. Vérifiez aussi Family Link.",
        ) {
            btn("Appareils connectés Google", Ui.DARK) { open("https://myaccount.google.com/device-activity") }
            btn("Sécurité du compte Google", Ui.DARK) { open("https://myaccount.google.com/security-checkup") }
        }

        step(
            "5. Vérifiez le renvoi d'appels et les traqueurs",
            "Un renvoi d'appels ou un traqueur Bluetooth caché permettent aussi de vous surveiller.",
        ) {
            btn("Renvoi d'appels", Ui.DARK) { startActivity(Intent(ctx, CallForwardActivity::class.java)) }
            btn("Traqueurs Bluetooth", Ui.DARK) { startActivity(Intent(ctx, TrackerActivity::class.java)) }
        }

        step(
            "6. Protégez le téléphone",
            "• Changez le code de déverrouillage (un code que personne ne connaît, pas une date de naissance).\n" +
                "• Supprimez les empreintes / visages que vous n'avez pas enregistrés.\n" +
                "• Désactivez le débogage USB et les options développeur.\n" +
                "• Activez le code PIN et l'anti-désinstallation de Mirador.",
        ) { btn("Réglages Mirador", Ui.DARK) { startActivity(Intent(ctx, SettingsActivity::class.java)) } }

        step(
            "7. En dernier recours",
            "Si un doute persiste : sauvegardez photos et contacts, puis faites une réinitialisation d'usine. " +
                "Ne restaurez PAS une sauvegarde complète du téléphone (elle peut contenir l'espion) : réinstallez vos applis depuis le Play Store.",
        )

        step(
            "Se faire aider",
            "• Echap : association spécialisée dans les logiciels espions et le cyber-harcèlement.\n" +
                "• Cybermalveillance.gouv.fr : assistance et conseils officiels.\n" +
                "• Vous pouvez porter plainte : l'installation d'un logiciel espion est un délit.",
        ) {
            btn("Echap", Ui.DARK) { open("https://echap.eu.org") }
            btn("Cybermalveillance.gouv.fr", Ui.DARK) { open("https://www.cybermalveillance.gouv.fr") }
        }
    }

    private lateinit var col: android.widget.LinearLayout

    private fun step(title: String, text: String, extra: (android.widget.LinearLayout.() -> Unit)? = null) {
        col.card().apply {
            label(title, 16f, true)
            label(text, 14f)
            extra?.invoke(this)
        }
    }

    private fun dial(n: String) = safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")))

    private fun open(url: String) = safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
