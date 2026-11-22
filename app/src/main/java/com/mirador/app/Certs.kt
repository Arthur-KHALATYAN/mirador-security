package com.mirador.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Empreintes SHA-1 des certificats de signature des applis. */
object Certs {
    val flags: Int
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }

    fun sha1List(p: PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION")
            p.signatures
        }
        return sigs.orEmpty().filterNotNull().map { sha1(it.toByteArray()) }
    }

    fun own(c: Context): String? = runCatching {
        sha1List(c.packageManager.pkgInfo(c.packageName, flags)).firstOrNull()
    }.getOrNull()
}
