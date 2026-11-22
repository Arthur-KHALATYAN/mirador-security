package com.mirador.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

data class AppRisk(
    val pkg: String,
    val label: String,
    val score: Int,
    val reasons: List<String>,
    val usesAccessibility: Boolean,
    /** Empreinte du certificat (pour lier une approbation à cette version précise). */
    val cert: String? = null,
    /** Approuvée par l'utilisateur (« je m'en porte garant »). */
    val approved: Boolean = false,
) {
    val verdict: Verdict
        get() = when {
            score >= 60 -> Verdict.DANGER
            score >= 25 -> Verdict.CAUTION
            else -> Verdict.SAFE
        }
}

/**
 * Cherche les comportements typiques des logiciels espions (stalkerware) :
 * accessibilité, administrateur d'appareil, lecture des notifications,
 * clavier tiers, appli cachée, installée hors store, micro/SMS/localisation…
 */
object AppScanner {

    private val trustedInstallers = setOf(
        "com.android.vending", "com.google.android.feedback", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "com.amazon.venezia", "com.xiaomi.market", "com.xiaomi.mipicks",
        "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "org.fdroid.fdroid",
    )

    /**
     * Permissions « classiques » : WhatsApp, Instagram… en ont besoin pour fonctionner,
     * elles pèsent donc peu et leur total est plafonné. Ce qui trahit un logiciel espion,
     * c'est la discrétion (cachée, hors store) et les accès spéciaux (accessibilité, admin…).
     */
    private val grantedPerms: List<Triple<String, Int, String>> = listOf(
        Triple(Manifest.permission.RECORD_AUDIO, 6, "Accès au micro"),
        Triple(Manifest.permission.CAMERA, 3, "Accès à la caméra"),
        Triple(Manifest.permission.ACCESS_FINE_LOCATION, 3, "Localisation précise"),
        Triple(Manifest.permission.ACCESS_BACKGROUND_LOCATION, 8, "Localisation en permanence (arrière-plan)"),
        Triple(Manifest.permission.READ_SMS, 10, "Lit vos SMS"),
        Triple(Manifest.permission.RECEIVE_SMS, 5, "Intercepte les SMS reçus (codes de validation)"),
        Triple(Manifest.permission.SEND_SMS, 5, "Peut envoyer des SMS"),
        Triple(Manifest.permission.READ_CALL_LOG, 8, "Lit le journal d'appels"),
        Triple(Manifest.permission.READ_CONTACTS, 3, "Lit vos contacts"),
    )

    fun installerOf(pm: PackageManager, pkg: String): String? = pm.installer(pkg)

    /** Installée depuis un store officiel ET visible dans le menu des applis. */
    fun isTrustedVisible(pm: PackageManager, pkg: String): Boolean {
        val inst = installerOf(pm, pkg)
        return inst != null && inst in trustedInstallers && pm.getLaunchIntentForPackage(pkg) != null
    }

    /** État des accès sensibles de l'appareil, lu une fois par scan. */
    class Env(c: Context) {
        val pm: PackageManager = c.packageManager
        val self: String = c.packageName
        val accessibility: String = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val notifListeners: String = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
        val keyboard: String = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val admins: Set<String> = c.getSystemService(DevicePolicyManager::class.java)
            ?.activeAdmins?.map { it.packageName }?.toSet().orEmpty()

        val approvals: Map<String, Store.Approval> = Store.approvals(c)

        init {
            Stalkerware.ensureLoaded(c)
        }
    }

    fun label(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.appInfo(pkg)).toString() }.getOrDefault(pkg)

    /** Analyse une appli. null = appli système ou Mirador lui-même. */
    fun analyze(env: Env, p: PackageInfo): AppRisk? {
        val ai = p.applicationInfo ?: return null
        if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return null
        val pkg = p.packageName
        if (pkg == env.self) return null
        val pm = env.pm

        val reasons = mutableListOf<String>()
        var score = 0
        fun add(points: Int, why: String) { score += points; reasons += why }

        Stalkerware.match(pkg, Certs.sha1List(p))?.let {
            add(100, "⛔ LOGICIEL ESPION CONNU : « $it » (base Echap). Désinstallez-le.")
        }

        val installer = installerOf(pm, pkg)
        val sideloaded = installer == null || installer !in trustedInstallers
        val hidden = pm.getLaunchIntentForPackage(pkg) == null
        // Appli du Play Store avec une icône normale : les accès spéciaux comptent moins
        val trusted = !sideloaded && !hidden

        val usesAcc = env.accessibility.contains("$pkg/")
        if (usesAcc) add(if (trusted) 20 else 40, "Service d'accessibilité ACTIF : peut lire tout l'écran et cliquer à votre place")
        if (pkg in env.admins) add(if (trusted) 15 else 30, "Administrateur de l'appareil : peut bloquer sa désinstallation")
        if (env.notifListeners.contains("$pkg/")) add(if (trusted) 10 else 25, "Lit toutes vos notifications (messages, codes 2FA)")
        if (env.keyboard.startsWith("$pkg/")) add(if (trusted) 10 else 25, "Clavier par défaut : voit tout ce que vous tapez")

        if (sideloaded) add(20, "Installée hors store officiel (source : ${installer ?: "inconnue / APK"})")
        if (hidden) add(15, "Pas d'icône dans le menu des applis (cachée ?)")

        // Permissions classiques : poids faible et total plafonné
        val requested = p.requestedPermissions.orEmpty()
        val flags = p.requestedPermissionsFlags ?: IntArray(0)
        fun granted(perm: String): Boolean {
            val i = requested.indexOf(perm)
            return i >= 0 && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
        }
        var permScore = 0
        val permReasons = mutableListOf<String>()
        grantedPerms.forEach { (perm, pts, why) -> if (granted(perm)) { permScore += pts; permReasons += why } }
        if (Manifest.permission.SYSTEM_ALERT_WINDOW in requested) { permScore += 3; permReasons += "Peut s'afficher par-dessus les autres applis" }
        if (Manifest.permission.REQUEST_INSTALL_PACKAGES in requested) { permScore += 5; permReasons += "Peut installer d'autres applications" }
        score += permScore.coerceAtMost(if (trusted) 10 else 30)
        reasons += permReasons

        if (usesAcc && (hidden || sideloaded)) add(25, "Combinaison typique d'un logiciel espion (accessibilité + cachée/hors store)")
        if (hidden && sideloaded && permReasons.size >= 3) add(15, "Appli cachée, hors store, avec de nombreux accès")

        if (trusted && score < 25) reasons.add(0, "✔ Installée depuis le Play Store")
        val cert = Certs.sha1List(p).firstOrNull()
        return AppRisk(
            pkg, pm.getApplicationLabel(ai).toString(), score.coerceAtMost(100), reasons, usesAcc,
            cert, env.approvals[pkg]?.cert.let { it != null && it == cert },
        )
    }

    fun analyzePackage(env: Env, pkg: String): AppRisk? =
        runCatching { env.pm.pkgInfo(pkg, PackageManager.GET_PERMISSIONS or Certs.flags) }.getOrNull()?.let { analyze(env, it) }

    /** Applis à risque élevé, hors applis approuvées par l'utilisateur. */
    fun dangerous(risks: List<AppRisk>) = risks.filter { it.verdict == Verdict.DANGER && !it.approved }

    /** Retourne (nombre d'applis analysées, applis à risque triées). */
    fun scan(c: Context): Pair<Int, List<AppRisk>> {
        val env = Env(c)
        val all = env.pm.pkgs(PackageManager.GET_PERMISSIONS or Certs.flags).mapNotNull { analyze(env, it) }
        return all.size to all.filter { it.score >= 25 }.sortedByDescending { it.score }
    }

    /** Accès sensibles actuellement accordés, sous forme "type:package". */
    fun grants(c: Context): Set<String> {
        val env = Env(c)
        val out = mutableSetOf<String>()
        env.accessibility.split(':').filter { it.isNotBlank() }.forEach { out += "acc:" + it.substringBefore('/') }
        env.notifListeners.split(':').filter { it.isNotBlank() }.forEach { out += "notif:" + it.substringBefore('/') }
        env.admins.forEach { out += "admin:$it" }
        if (env.keyboard.isNotBlank()) out += "ime:" + env.keyboard.substringBefore('/')
        return out.filter { it.substringAfter(':') != c.packageName }.toSet()
    }

    fun describeGrant(c: Context, g: String): String {
        val name = label(c.packageManager, g.substringAfter(':'))
        return when (g.substringBefore(':')) {
            "acc" -> "$name a obtenu l'accès Accessibilité (peut lire l'écran et agir à votre place)"
            "notif" -> "$name peut maintenant lire toutes vos notifications"
            "admin" -> "$name est devenu administrateur de l'appareil"
            "ime" -> "$name est maintenant votre clavier (voit tout ce que vous tapez)"
            else -> g
        }
    }
}
