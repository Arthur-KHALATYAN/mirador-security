package com.mirador.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/*
 * Accès au PackageManager avec les API modernes (Android 13+ : *Flags.of(...)),
 * et repli sur les anciennes signatures pour les versions précédentes.
 */

fun PackageManager.pkgs(flags: Int = 0): List<PackageInfo> =
    if (Build.VERSION.SDK_INT >= 33) getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getInstalledPackages(flags)

fun PackageManager.pkgInfo(pkg: String, flags: Int = 0): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getPackageInfo(pkg, flags)

fun PackageManager.appInfo(pkg: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= 33) getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    else @Suppress("DEPRECATION") getApplicationInfo(pkg, 0)

fun PackageManager.archiveInfo(path: String, flags: Int): PackageInfo? =
    if (Build.VERSION.SDK_INT >= 33) getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getPackageArchiveInfo(path, flags)

fun PackageManager.activities(intent: Intent, flags: Int): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= 33) queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") queryIntentActivities(intent, flags)

fun PackageManager.resolve(intent: Intent, flags: Int): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= 33) resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") resolveActivity(intent, flags)

/** Appli qui a installé ce paquet (Play Store, Galaxy Store…), ou null. */
fun PackageManager.installer(pkg: String): String? = try {
    if (Build.VERSION.SDK_INT >= 30) getInstallSourceInfo(pkg).installingPackageName
    else @Suppress("DEPRECATION") getInstallerPackageName(pkg)
} catch (e: Exception) {
    null
}
