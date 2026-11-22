package com.mirador.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class DnsActivity : GuardedActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        if (!DnsBlocklist.loaded) Thread { DnsBlocklist.load(this); runOnUiThread { if (unlocked) render() } }.start()
    }

    override fun render() {
        val ctx = this
        val c = screen()
        c.header(R.drawable.ic_globe, 0xFF10B981.toInt(), "Filtre DNS")
        c.label("Bloque, pour TOUTES les applis, les sites de pub, de pistage, de malware et les sites de logiciels espions. Fonctionne comme un VPN local : seules les requêtes DNS passent par Mirador, rien n'est envoyé à un serveur Mirador.", 13f, color = Ui.MUTED)

        val on = DnsVpnService.running
        val v = if (on) Verdict.SAFE else Verdict.CAUTION
        c.card(v.bg).apply {
            iconTitle(v.iconRes, v.color, if (on) "Filtre ACTIF" else "Filtre INACTIF", 18f)
            label("${DnsBlocklist.size} domaines dans la liste", 14f)
            if (on) label("${DnsBlocklist.blockedCount} requêtes bloquées depuis le démarrage", 14f)
            if (on) btn("Arrêter le filtre", Ui.DARK) { stopDns() } else btn("Démarrer le filtre", Verdict.SAFE.solid) { askAndStart() }
        }
        c.btn("Rafraîchir", Ui.DARK) { render() }

        c.btn("Télécharger la liste complète (~80 000 domaines)") {
            toast("Téléchargement…")
            Thread {
                val res = runCatching { DnsBlocklist.download(ctx) }
                runOnUiThread {
                    res.onSuccess { toast("Liste à jour : $it domaines") }.onFailure { toast("Échec : ${it.message}") }
                    render()
                }
            }.start()
        }

        val recent = DnsBlocklist.recent()
        if (recent.isNotEmpty()) {
            c.label("Derniers domaines bloqués", 17f, true, top = 18)
            c.card().apply { recent.take(30).forEach { iconTitle(R.drawable.ic_block, Verdict.DANGER.color, it, 13f, top = 6).raw() } }
        }

        c.label("Bon à savoir", 17f, true, top = 18)
        c.label(
            "• Si « DNS privé » est en mode « Strict » (Paramètres > Réseau), le filtre est contourné : mettez-le sur Automatique ou Désactivé.\n" +
                "• Dans Chrome, désactivez « Utiliser un DNS sécurisé » pour que le filtre s'applique aussi au navigateur.\n" +
                "• Un seul VPN peut être actif à la fois.\n" +
                "• Pour qu'il reste toujours actif : Paramètres VPN > Mirador > « VPN permanent ».",
            13f,
        )
        c.btn("Paramètres VPN", Ui.DARK) { safeStart(Intent(Settings.ACTION_VPN_SETTINGS)) }
    }

    private fun askAndStart() {
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 10) else startDns()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) startDns()
    }

    private fun startDns() {
        Store.setDnsEnabled(this, true)
        startService(Intent(this, DnsVpnService::class.java))
        handler.postDelayed({ render() }, 500)
    }

    private fun stopDns() {
        Store.setDnsEnabled(this, false)
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        handler.postDelayed({ render() }, 500)
    }
}
