package com.mirador.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackerActivity : GuardedActivity() {

    private var generation = 0

    override fun render() {
        val ctx = this
        val c = screen()
        c.header(R.drawable.ic_bluetooth, 0xFF8B5CF6.toInt(), "Traqueurs Bluetooth")
        c.label(
            "Détecte les AirTag, SmartTag, Tile, Chipolo et traqueurs Google cachés près de vous. " +
                "Mirador vérifie aussi en arrière-plan (toutes les ~15 min) et vous alerte si un traqueur inconnu vous suit plus de 30 min.",
            13f, color = Ui.MUTED,
        )

        if (!Trackers.hasPermissions(this)) {
            c.card(Verdict.CAUTION.bg).apply {
                label("Mirador a besoin de l'accès Bluetooth / position pour repérer les traqueurs (rien n'est enregistré ailleurs).", 14f)
                btn("Autoriser", Verdict.CAUTION.solid) { requestPermissions(Trackers.permissions(), 20) }
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            c.card().apply {
                label("Pour la détection automatique en arrière-plan, choisissez « Toujours autoriser » pour la position.", 13f)
                btn("Autoriser en arrière-plan", Ui.DARK) {
                    Lock.allowExternal()
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 21)
                }
            }
        }
        if (!Trackers.bluetoothOn(this)) {
            c.card(Verdict.CAUTION.bg).apply {
                label("Le Bluetooth est désactivé.", 14f, true)
                btn("Activer le Bluetooth", Verdict.CAUTION.solid) { safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
            return
        }

        c.btn("Scanner autour de moi (15 s)") { scanNow(c) }

        val hist = Trackers.history(this).sortedByDescending { it.last }
        val ignored = Store.ignoredTrackers(this)
        c.label("Vus ces dernières 24 h", 17f, true, top = 18)
        if (hist.isEmpty()) c.label("Aucun traqueur détecté.", 14f, color = Ui.MUTED)
        val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
        hist.forEach { t ->
            val mine = t.addr in ignored
            val v = when {
                mine -> Verdict.SAFE
                t.following -> Verdict.DANGER
                else -> Verdict.CAUTION
            }
            c.card(v.bg).apply {
                iconTitle(v.iconRes, v.color, t.type + if (mine) " (le vôtre)" else "", 15f)
                label("Vu ${t.count} fois, de ${fmt.format(Date(t.first))} à ${fmt.format(Date(t.last))} (${t.minutes} min)", 13f)
                if (t.following && !mine) label("Il semble vous suivre !", 13f, true, v.color)
                if (!mine) btn("C'est le mien / celui d'un proche", Ui.DARK) { Store.ignoreTracker(ctx, t.addr); render() }
            }
        }

        c.label("Si un traqueur vous suit", 17f, true, top = 18)
        c.label(
            "• Fouillez sac, poches, doublures, voiture (sous les sièges, coffre, pare-chocs).\n" +
                "• Un AirTag peut être désactivé en retirant sa pile (tournez le dos).\n" +
                "• Si vous êtes en danger, ne rentrez pas directement chez vous : allez dans un lieu public, appelez le 17.\n" +
                "• Gardez le traqueur comme preuve pour une plainte.",
            13f,
        )
    }

    private fun scanNow(c: LinearLayout) {
        val gen = ++generation
        val box = c.box()
        box.label("Scan en cours… restez immobile 15 s.", 14f, color = Ui.MUTED)
        Thread {
            val seen = Trackers.scanBlocking(this, 15_000)
            Trackers.record(this, seen)
            runOnUiThread {
                if (isFinishing || gen != generation || !unlocked) return@runOnUiThread
                toast(if (seen.isEmpty()) "Aucun traqueur détecté autour de vous" else "${seen.size} traqueur(s) détecté(s)")
                render()
            }
        }.start()
    }
}
