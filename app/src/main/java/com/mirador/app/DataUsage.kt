@file:Suppress("DEPRECATION")

package com.mirador.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import java.util.Calendar
import java.util.Locale

data class AppTraffic(
    val uid: Int,
    val label: String,
    val pkg: String?,
    val rx: Long,
    val tx: Long,
    val bgTx: Long,
    val nightTx: Long,
    val system: Boolean,
    /** Installée depuis le Play Store et visible : les gros envois (sauvegardes WhatsApp, stories…) sont normaux. */
    val trusted: Boolean,
) {
    /** Un logiciel espion envoie ce qu'il collecte : beaucoup d'envoi en arrière-plan ou la nuit. */
    val suspicious: Boolean get() = !system && !trusted && (bgTx > 50L * MB || nightTx > 10L * MB)

    companion object { const val MB = 1024L * 1024L }
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.FRANCE, "%.1f Mo", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.FRANCE, "%.0f Ko", b / (1L shl 10).toDouble())
    else -> "$b o"
}

/** Données envoyées / reçues par chaque appli (nécessite l'accès aux données d'utilisation). */
object DataUsage {
    private const val DAY = 24 * 60 * 60 * 1000L

    fun collect(c: Context, days: Int = 7): List<AppTraffic> {
        val nsm = c.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val end = System.currentTimeMillis()
        val start = end - days * DAY
        val map = HashMap<Int, LongArray>() // rx, tx, bgTx, nightTx

        val nights = (0 until days).mapNotNull { d ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -d)
            }
            val s = cal.timeInMillis
            if (s >= end) null else s to minOf(s + 6 * 60 * 60 * 1000L, end)
        }

        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            runCatching {
                val stats = nsm.querySummary(type, null, start, end)
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val a = map.getOrPut(b.uid) { LongArray(4) }
                    a[0] += b.rxBytes
                    a[1] += b.txBytes
                    if (b.state == NetworkStats.Bucket.STATE_DEFAULT) a[2] += b.txBytes
                }
                stats.close()
            }
            for ((ns, ne) in nights) {
                runCatching {
                    val stats = nsm.querySummary(type, null, ns, ne)
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        map.getOrPut(b.uid) { LongArray(4) }[3] += b.txBytes
                    }
                    stats.close()
                }
            }
        }

        val pm = c.packageManager
        return map.map { (uid, a) ->
            val pkgs = runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()
            val pkg = pkgs.firstOrNull()
            val ai = pkg?.let { runCatching { pm.appInfo(it) }.getOrNull() }
            val label = when {
                uid == 0 -> "Système (root)"
                uid == 1000 -> "Système Android"
                uid == -4 -> "Applis désinstallées"
                uid == -5 -> "Partage de connexion"
                ai != null -> pm.getApplicationLabel(ai).toString() + if (pkgs.size > 1) " (+${pkgs.size - 1})" else ""
                else -> "UID $uid"
            }
            val system = uid < 10000 || (ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            val trusted = pkg != null && (AppScanner.isTrustedVisible(pm, pkg) || pkg in Store.approvals(c))
            AppTraffic(uid, label, pkg, a[0], a[1], a[2], a[3], system, trusted)
        }.filter { it.rx + it.tx > 0 }.sortedByDescending { it.tx }
    }
}
