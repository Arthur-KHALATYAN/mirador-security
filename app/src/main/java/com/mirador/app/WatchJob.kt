package com.mirador.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Tâche de fond (toutes les ~15 min, même après redémarrage) :
 *  1. alerte à chaque nouvelle appli installée, avec son score de risque ;
 *  2. alerte quand une appli obtient accessibilité / admin / notifications / clavier ;
 *  3. scan complet une fois par jour.
 */
class WatchJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                runChecks(this)
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 4201
        private const val DAY = 24 * 60 * 60 * 1000L

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(c, WatchJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            )
        }

        @Synchronized
        fun runChecks(c: Context) {
            val pm = c.packageManager
            val env = AppScanner.Env(c)

            // 1. Nouvelles applis
            val installed = pm.pkgs(0).map { it.packageName }.toSet()
            val known = Store.knownPackages(c)
            if (known.isNotEmpty()) {
                for (pkg in installed - known) {
                    val r = AppScanner.analyzePackage(env, pkg) ?: continue
                    val v = r.verdict
                    val details = if (r.reasons.isEmpty()) "Aucun accès sensible." else r.reasons.take(3).joinToString(" • ")
                    Notifier.alert(
                        c, pkg.hashCode(),
                        "${v.icon} Nouvelle appli : ${r.label}",
                        "Risque ${r.score}/100. $details",
                        Intent(c, ScanActivity::class.java),
                    )
                }
            }
            Store.setKnownPackages(c, installed)

            // Signature changée = appli remplacée par une version modifiée
            val oldCerts = Store.certs(c)
            val newCerts = HashMap<String, String>()
            for (p in pm.pkgs(Certs.flags)) {
                val ai = p.applicationInfo ?: continue
                if (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
                val sha = Certs.sha1List(p).firstOrNull() ?: continue
                newCerts[p.packageName] = sha
                val before = oldCerts[p.packageName]
                if (before != null && before != sha) {
                    val name = AppScanner.label(pm, p.packageName)
                    Store.addLog(c, "intrusions", "Signature changée : $name")
                    Notifier.alert(
                        c, ("sig" + p.packageName).hashCode(),
                        "⛔ $name a été remplacée",
                        "La signature de « $name » a changé : l'appli a pu être remplacée par une version modifiée (piégée). Désinstallez-la et réinstallez-la depuis le Play Store.",
                        Intent(c, ScanActivity::class.java),
                    )
                }
            }
            Store.setCerts(c, newCerts)

            // Traqueurs Bluetooth qui vous suivent
            runCatching { Trackers.backgroundCheck(c) }

            // 2. Nouveaux accès sensibles
            val now = AppScanner.grants(c)
            val prev = Store.grants(c)
            if (prev != null) {
                for (g in now - prev) {
                    Notifier.alert(
                        c, g.hashCode(),
                        "⚠️ Nouvel accès sensible",
                        AppScanner.describeGrant(c, g) + ". Si ce n'est pas vous, désactivez-le immédiatement.",
                        Intent(c, ScanActivity::class.java),
                    )
                }
            }
            Store.setGrants(c, now)

            // 3. Scan complet quotidien
            val t = System.currentTimeMillis()
            if (t - Store.lastFullScan(c) > DAY) {
                val danger = AppScanner.dangerous(AppScanner.scan(c).second)
                val fresh = danger.filter { it.pkg !in Store.notifiedDanger(c) }
                if (fresh.isNotEmpty()) {
                    Notifier.alert(
                        c, 777,
                        "⛔ ${fresh.size} appli(s) à risque élevé",
                        fresh.take(5).joinToString(", ") { it.label } + ". Touchez pour vérifier.",
                        Intent(c, ScanActivity::class.java),
                    )
                }
                Store.setNotifiedDanger(c, danger.map { it.pkg }.toSet())

                // Applis qui envoient beaucoup de données en arrière-plan / la nuit
                if (SensorMonitorService.hasUsageAccess(c)) {
                    val sus = runCatching { DataUsage.collect(c) }.getOrDefault(emptyList()).filter { it.suspicious }
                    val newSus = sus.filter { it.label !in Store.notifiedData(c) }
                    if (newSus.isNotEmpty()) {
                        Notifier.alert(
                            c, 778,
                            "📶 Envoi de données inhabituel",
                            newSus.take(4).joinToString(", ") { "${it.label} (${fmtBytes(it.bgTx)} en arrière-plan)" } + ". Touchez pour vérifier.",
                            Intent(c, DataUsageActivity::class.java),
                        )
                    }
                    Store.setNotifiedData(c, sus.map { it.label }.toSet())
                }
                Store.setLastFullScan(c, t)
            }

            // Bases de menaces : mise à jour quotidienne
            if (t - Store.feedsUpdated(c) > DAY) runCatching { Feeds.updateAll(c) }

            // Résumé hebdomadaire
            if (Store.lastWeekly(c) == 0L) {
                Store.setLastWeekly(c, t)
            } else if (t - Store.lastWeekly(c) > 7 * DAY) {
                val since = Store.lastWeekly(c)
                val links = Store.history(c).filter { it.time > since }
                val bad = links.count { it.verdict != Verdict.SAFE }
                val newApps = pm.pkgs(0).count { it.firstInstallTime > since }
                val sensors = Store.sensorLog(c).filter { it.time > since && it.start }
                val intrusions = Store.log(c, "intrusions").count { it.first > since }
                val score = SecurityScore.score(SecurityScore.items(c))
                val alerts = Store.takeAlerts(c)
                Notifier.alert(
                    c, 779,
                    "🛡️ Votre semaine avec Mirador – score $score/100",
                    "• ${links.size} liens contrôlés, dont $bad suspects\n" +
                        "• $newApps nouvelle(s) appli(s)\n" +
                        "• ${sensors.size} accès micro/caméra" + (sensors.count { it.screenOff }.takeIf { it > 0 }?.let { " (dont $it écran éteint !)" } ?: "") + "\n" +
                        "• $alerts alerte(s), $intrusions tentative(s) d'accès",
                    null,
                )
                Store.setLastWeekly(c, t)
            }

            Store.setLastWatch(c, t)
            StatusWidget.refresh(c)
        }
    }
}
