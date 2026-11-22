package com.mirador.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout

/** Vue « qui a accès à quoi » : micro, localisation, SMS… + accès spéciaux. */
class PermissionsActivity : GuardedActivity() {

    private data class App(val pkg: String, val label: String)

    private val groups: List<Pair<String, List<String>>> = listOf(
        "Micro" to listOf("android.permission.RECORD_AUDIO"),
        "Caméra" to listOf("android.permission.CAMERA"),
        "Localisation" to listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
        "Localisation en arrière-plan" to listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
        "SMS" to listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS"),
        "Journal d'appels" to listOf("android.permission.READ_CALL_LOG"),
        "Contacts" to listOf("android.permission.READ_CONTACTS"),
        "Photos, vidéos, fichiers" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_EXTERNAL_STORAGE"),
        "Agenda" to listOf("android.permission.READ_CALENDAR"),
        "Capteurs corporels" to listOf("android.permission.BODY_SENSORS"),
    )

    private val groupIcons = listOf(
        R.drawable.ic_mic, R.drawable.ic_camera, R.drawable.ic_place, R.drawable.ic_place, R.drawable.ic_chat,
        R.drawable.ic_phone, R.drawable.ic_person, R.drawable.ic_image, R.drawable.ic_event, R.drawable.ic_heart,
    )

    private var generation = 0

    override fun render() {
        val gen = ++generation
        val c = screen()
        c.header(R.drawable.ic_key, 0xFF6366F1.toInt(), "Permissions des applis")
        c.label("Applis installées par vous ayant accès à vos données. Touchez une appli pour lui retirer un accès.", 13f, color = Ui.MUTED)
        val box = c.box()
        box.label("Analyse…", 14f, color = Ui.MUTED)
        Thread {
            val data = collect()
            val env = AppScanner.Env(this)
            runOnUiThread {
                if (isFinishing || gen != generation) return@runOnUiThread
                box.removeAllViews()
                show(box, data, env)
            }
        }.start()
    }

    private fun collect(): List<Pair<String, List<App>>> {
        val pm = packageManager
        val pkgs = pm.pkgs(PackageManager.GET_PERMISSIONS).filter {
            val ai = it.applicationInfo
            ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != packageName
        }
        fun granted(p: PackageInfo, perm: String): Boolean {
            val req = p.requestedPermissions ?: return false
            val flags = p.requestedPermissionsFlags ?: return false
            val i = req.indexOf(perm)
            return i >= 0 && i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
        }
        return groups.map { (title, perms) ->
            title to pkgs.filter { p -> perms.any { granted(p, it) } }
                .map { App(it.packageName, pm.getApplicationLabel(it.applicationInfo!!).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    private fun appRow(box: LinearLayout, app: App) {
        box.label("›  ${app.label}", 14f, top = 6).setOnClickListener {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
        }
    }

    private fun show(box: LinearLayout, data: List<Pair<String, List<App>>>, env: AppScanner.Env) {
        box.label("Accès spéciaux (les plus sensibles)", 17f, true, top = 10)
        fun special(icon: Int, title: String, raw: List<String>, settings: String) {
            val apps = raw.distinct().filter { it != packageName }.map { App(it, AppScanner.label(packageManager, it)) }
            val v = if (apps.isEmpty()) Verdict.SAFE else Verdict.CAUTION
            box.card(v.bg).apply {
                iconTitle(icon, v.color, "$title : ${apps.size}", 15f)
                apps.forEach { appRow(this, it) }
                if (apps.isNotEmpty()) btn("Gérer", v.solid) { safeStart(Intent(settings)) }
            }
        }
        special(
            R.drawable.ic_accessibility, "Accessibilité (lit l'écran)",
            env.accessibility.split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') },
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
        )
        special(
            R.drawable.ic_bell, "Lecture des notifications",
            env.notifListeners.split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') },
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
        )
        special(R.drawable.ic_shield, "Administrateur de l'appareil", env.admins.toList(), Settings.ACTION_SECURITY_SETTINGS)

        box.label("Permissions classiques", 17f, true, top = 18)
        data.forEachIndexed { gi, (title, apps) ->
            box.card().apply {
                iconTitle(groupIcons[gi], Ui.ACCENT_TEXT, "$title : ${apps.size} appli(s)", 15f)
                if (apps.isEmpty()) label("Aucune", 13f, color = Ui.MUTED)
                apps.forEach { appRow(this, it) }
            }
        }
    }
}
