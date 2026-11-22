package com.mirador.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class Check(val ok: Boolean, val title: String, val detail: String, val fixAction: String? = null)

object DeviceAudit {

    fun isDefaultBrowser(c: Context): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val ri = c.packageManager.resolve(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == c.packageName
    }

    fun run(c: Context): List<Check> {
        val cr = c.contentResolver
        val pm = c.packageManager
        val out = mutableListOf<Check>()

        val secure = c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        out += Check(
            secure, "Verrouillage de l'écran",
            if (secure) "Un code / schéma / empreinte protège l'appareil."
            else "Aucun verrouillage : n'importe qui peut prendre le téléphone et installer un espion.",
            Settings.ACTION_SECURITY_SETTINGS,
        )

        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        out += Check(
            !adb, "Débogage USB",
            if (adb) "Activé : un ordinateur branché peut contrôler le téléphone et installer des applis."
            else "Désactivé.",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )

        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        out += Check(
            !dev, "Options pour les développeurs",
            if (dev) "Activées. Si ce n'est pas vous, quelqu'un a pu modifier le téléphone." else "Désactivées.",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )

        val rooted = isRooted(c)
        out += Check(
            !rooted, "Root",
            if (rooted) "Appareil rooté : les protections d'Android peuvent être contournées." else "Aucun signe de root détecté.",
        )

        val patch = Build.VERSION.SECURITY_PATCH
        val ageDays = runCatching {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)!!
            (System.currentTimeMillis() - d.time) / 86_400_000L
        }.getOrNull()
        val upToDate = ageDays != null && ageDays <= 180
        out += Check(
            upToDate, "Mises à jour de sécurité",
            "Dernier correctif : $patch" + (ageDays?.let { " (il y a $it jours)" } ?: "") +
                if (upToDate) "." else " — installez les mises à jour système, des failles connues ne sont pas corrigées.",
            Settings.ACTION_SETTINGS,
        )

        val acc = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
            .map { it.substringBefore('/') }.distinct()
            .map { pkg -> runCatching { pm.getApplicationLabel(pm.appInfo(pkg)).toString() }.getOrDefault(pkg) }
        out += Check(
            acc.isEmpty(), "Services d'accessibilité",
            if (acc.isEmpty()) "Aucun service actif."
            else "${acc.size} actif(s) : ${acc.joinToString(", ")}. Ils peuvent lire tout l'écran : désactivez ceux que vous ne reconnaissez pas.",
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
        )

        wifiCheck(c)?.let { out += it }

        return out
    }

    /** Sécurité du Wi-Fi connecté (Android 12+). */
    @Suppress("DEPRECATION")
    private fun wifiCheck(c: Context): Check? {
        if (Build.VERSION.SDK_INT < 31) return null
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ?: return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val (ok, txt) = when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> false to "Réseau OUVERT (sans mot de passe) : les autres personnes connectées peuvent voir une partie de votre trafic. Évitez de vous connecter à vos comptes ; activez le filtre DNS ou un VPN."
            WifiInfo.SECURITY_TYPE_WEP -> false to "Chiffrement WEP obsolète, cassable en quelques minutes. Changez la sécurité de la box en WPA2/WPA3."
            WifiInfo.SECURITY_TYPE_OWE -> true to "Réseau ouvert mais chiffré (OWE)."
            WifiInfo.SECURITY_TYPE_PSK -> true to "Protégé par mot de passe (WPA2)."
            WifiInfo.SECURITY_TYPE_SAE -> true to "Protégé par mot de passe (WPA3)."
            WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> true to "Réseau d'entreprise (WPA-Entreprise)."
            else -> true to "Type de sécurité inconnu."
        }
        val extra = if (captive) " Réseau public avec page de connexion : prudence." else ""
        return Check(ok && !captive, "Wi-Fi actuel", txt + extra, Settings.ACTION_WIFI_SETTINGS)
    }

    private fun isRooted(c: Context): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk",
        )
        if (suPaths.any { File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        return listOf("com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser").any {
            runCatching { c.packageManager.pkgInfo(it, 0); true }.getOrDefault(false)
        }
    }
}
