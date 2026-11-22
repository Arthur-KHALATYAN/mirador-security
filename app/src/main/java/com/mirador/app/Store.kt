package com.mirador.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val time: Long, val url: String, val verdict: Verdict, val score: Int, val action: String)

data class SensorEvent(val time: Long, val type: String, val start: Boolean, val app: String?, val screenOff: Boolean)

/** Stockage local uniquement (rien ne quitte le téléphone). */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("mirador", Context.MODE_PRIVATE)
    private fun set(c: Context, key: String): Set<String> = p(c).getStringSet(key, emptySet())!!.toSet()
    private fun putSet(c: Context, key: String, v: Set<String>) = p(c).edit().putStringSet(key, v).apply()
    private fun bool(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    private fun putBool(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()

    // ---------- Historique des liens ----------
    fun history(c: Context): List<HistoryEntry> {
        val arr = runCatching { JSONArray(p(c).getString("history", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.getLong("t"), o.getString("u"),
                    runCatching { Verdict.valueOf(o.getString("v")) }.getOrDefault(Verdict.CAUTION),
                    o.getInt("s"), o.getString("a"),
                )
            }.getOrNull()
        }
    }

    fun addHistory(c: Context, e: HistoryEntry) {
        val arr = JSONArray()
        (listOf(e) + history(c)).take(200).forEach {
            arr.put(JSONObject().put("t", it.time).put("u", it.url).put("v", it.verdict.name).put("s", it.score).put("a", it.action))
        }
        p(c).edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory(c: Context) = p(c).edit().remove("history").apply()

    // ---------- Domaines de confiance / navigateur ----------
    fun trusted(c: Context) = set(c, "trusted")
    fun trust(c: Context, domain: String) = putSet(c, "trusted", trusted(c) + domain)
    fun untrust(c: Context, domain: String) = putSet(c, "trusted", trusted(c) - domain)

    fun preferredBrowser(c: Context): String? = p(c).getString("browser", null)
    fun setPreferredBrowser(c: Context, pkg: String?) = p(c).edit().putString("browser", pkg).apply()

    fun autoOpenSafe(c: Context) = bool(c, "autoSafe", false)
    fun setAutoOpenSafe(c: Context, v: Boolean) = putBool(c, "autoSafe", v)

    // ---------- Google Safe Browsing ----------
    fun sbKey(c: Context): String = p(c).getString("sbKey", "")!!.trim()
    fun setSbKey(c: Context, k: String) = p(c).edit().putString("sbKey", k.trim()).apply()

    // ---------- Liste de blocage ----------
    fun blockedDomains(c: Context) = set(c, "blocked")
    fun addBlocked(c: Context, d: String) = putSet(c, "blocked", blockedDomains(c) + d)
    fun removeBlocked(c: Context, d: String) = putSet(c, "blocked", blockedDomains(c) - d)
    fun blockApk(c: Context) = bool(c, "blockApk", true)
    fun setBlockApk(c: Context, v: Boolean) = putBool(c, "blockApk", v)

    /** Raison du blocage si le lien est interdit par les règles de l'utilisateur, sinon null. */
    fun blockReason(c: Context, r: UrlReport): String? {
        val host = r.host
        blockedDomains(c).firstOrNull { host == it || host.endsWith(".$it") }?.let {
            return "Domaine bloqué par vous ($it)"
        }
        val path = Uri.parse(r.url).path?.lowercase().orEmpty()
        if (blockApk(c) && (path.endsWith(".apk") || path.endsWith(".xapk"))) {
            return "Téléchargement d'application (.apk) bloqué dans les réglages"
        }
        return null
    }

    // ---------- Fonctions activées ----------
    fun smsEnabled(c: Context) = bool(c, "sms", false)
    fun setSmsEnabled(c: Context, v: Boolean) = putBool(c, "sms", v)
    fun dnsEnabled(c: Context) = bool(c, "dns", false)
    fun setDnsEnabled(c: Context, v: Boolean) = putBool(c, "dns", v)
    fun monitorEnabled(c: Context) = bool(c, "monitor", false)
    fun setMonitorEnabled(c: Context, v: Boolean) = putBool(c, "monitor", v)
    fun askedNotif(c: Context) = bool(c, "askedNotif", false)
    fun setAskedNotif(c: Context) = putBool(c, "askedNotif", true)

    // ---------- Surveillance automatique ----------
    fun knownPackages(c: Context) = set(c, "knownPkgs")
    fun setKnownPackages(c: Context, v: Set<String>) = putSet(c, "knownPkgs", v)
    fun grants(c: Context): Set<String>? = if (p(c).contains("grants")) set(c, "grants") else null
    fun setGrants(c: Context, v: Set<String>) = putSet(c, "grants", v)
    fun lastFullScan(c: Context) = p(c).getLong("lastFull", 0L)
    fun setLastFullScan(c: Context, t: Long) = p(c).edit().putLong("lastFull", t).apply()
    fun notifiedDanger(c: Context) = set(c, "notifiedDanger")
    fun setNotifiedDanger(c: Context, v: Set<String>) = putSet(c, "notifiedDanger", v)
    fun lastWatch(c: Context) = p(c).getLong("lastWatch", 0L)
    fun setLastWatch(c: Context, t: Long) = p(c).edit().putLong("lastWatch", t).apply()

    // ---------- Code de verrouillage ----------
    fun pinHash(c: Context): String? = p(c).getString("pinHash", null)
    fun pinSalt(c: Context): String? = p(c).getString("pinSalt", null)
    fun setPin(c: Context, salt: String, hash: String) = p(c).edit().putString("pinSalt", salt).putString("pinHash", hash).apply()
    fun clearPin(c: Context) = p(c).edit().remove("pinSalt").remove("pinHash").remove("pinFails").remove("pinBlocked").apply()
    fun pinFails(c: Context) = p(c).getInt("pinFails", 0)
    fun setPinFails(c: Context, n: Int) = p(c).edit().putInt("pinFails", n).commit()
    fun pinBlockedUntil(c: Context) = p(c).getLong("pinBlocked", 0L)
    fun setPinBlockedUntil(c: Context, t: Long) = p(c).edit().putLong("pinBlocked", t).commit()

    // ---------- Bases de menaces / divers ----------
    fun feedsUpdated(c: Context) = p(c).getLong("feedsUpdated", 0L)
    fun setFeedsUpdated(c: Context, t: Long) = p(c).edit().putLong("feedsUpdated", t).apply()
    fun lastClip(c: Context): String? = p(c).getString("lastClip", null)
    fun setLastClip(c: Context, v: String) = p(c).edit().putString("lastClip", v).apply()
    fun notifiedData(c: Context) = set(c, "notifiedData")
    fun setNotifiedData(c: Context, v: Set<String>) = putSet(c, "notifiedData", v)

    // ---------- Messageries / intrus / traqueurs / signatures ----------
    fun msgEnabled(c: Context) = bool(c, "msg", false)
    fun setMsgEnabled(c: Context, v: Boolean) = putBool(c, "msg", v)
    fun intruderPhoto(c: Context) = bool(c, "intruder", false)
    fun setIntruderPhoto(c: Context, v: Boolean) = putBool(c, "intruder", v)
    fun ignoredTrackers(c: Context) = set(c, "ignoredTrackers")
    fun ignoreTracker(c: Context, addr: String) = putSet(c, "ignoredTrackers", ignoredTrackers(c) + addr)
    fun alertedTrackers(c: Context) = set(c, "alertedTrackers")
    fun setAlertedTrackers(c: Context, v: Set<String>) = putSet(c, "alertedTrackers", v)
    fun certs(c: Context): Map<String, String> =
        set(c, "certs").associate { it.substringBefore('|') to it.substringAfter('|') }
    fun setCerts(c: Context, m: Map<String, String>) = putSet(c, "certs", m.map { "${it.key}|${it.value}" }.toSet())
    fun lastWeekly(c: Context) = p(c).getLong("lastWeekly", 0L)
    fun setLastWeekly(c: Context, t: Long) = p(c).edit().putLong("lastWeekly", t).apply()
    @Synchronized
    fun incAlerts(c: Context) = p(c).edit().putInt("alerts", p(c).getInt("alerts", 0) + 1).apply()
    fun takeAlerts(c: Context): Int = p(c).getInt("alerts", 0).also { p(c).edit().putInt("alerts", 0).apply() }

    /** Journaux simples (texte horodaté), ex. "intrusions", "trackers". */
    @Synchronized
    fun log(c: Context, key: String): List<Pair<Long, String>> {
        val arr = runCatching { JSONArray(p(c).getString("log_$key", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { arr.getJSONObject(i).let { it.getLong("t") to it.getString("m") } }.getOrNull()
        }
    }

    @Synchronized
    fun addLog(c: Context, key: String, msg: String) {
        val arr = JSONArray()
        (listOf(System.currentTimeMillis() to msg) + log(c, key)).take(200).forEach {
            arr.put(JSONObject().put("t", it.first).put("m", it.second))
        }
        p(c).edit().putString("log_$key", arr.toString()).apply()
    }

    fun clearLog(c: Context, key: String) = p(c).edit().remove("log_$key").apply()

    // ---------- Applis approuvées (« je m'en porte garant ») ----------
    data class Approval(val pkg: String, val cert: String, val time: Long)

    fun approvals(c: Context): Map<String, Approval> = set(c, "approved").mapNotNull {
        val p = it.split('|')
        if (p.size == 3) Approval(p[0], p[1], p[2].toLongOrNull() ?: 0L) else null
    }.associateBy { it.pkg }

    fun approve(c: Context, pkg: String, cert: String) =
        putSet(c, "approved", set(c, "approved").filterNot { it.startsWith("$pkg|") }.toSet() + "$pkg|$cert|${System.currentTimeMillis()}")

    fun unapprove(c: Context, pkg: String) =
        putSet(c, "approved", set(c, "approved").filterNot { it.startsWith("$pkg|") }.toSet())

    /** Approuvée ET même signature qu'au moment de l'approbation (sinon l'approbation ne vaut plus). */
    fun isApproved(c: Context, pkg: String, cert: String?): Boolean =
        cert != null && approvals(c)[pkg]?.cert == cert

    // ---------- Assistant / langue ----------
    fun onboarded(c: Context) = bool(c, "onboarded", false)
    fun setOnboarded(c: Context) = putBool(c, "onboarded", true)
    /** "auto", "fr" ou "en". */
    fun lang(c: Context): String = p(c).getString("lang", "auto")!!
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()

    // ---------- Sauvegarde ----------
    private val backupBools = listOf("autoSafe", "blockApk", "sms", "msg", "monitor", "intruder")
    private val backupSets = listOf("trusted", "blocked", "ignoredTrackers")

    fun exportJson(c: Context): String {
        val o = JSONObject().put("app", "Mirador").put("version", 1)
        backupBools.forEach { o.put(it, p(c).getBoolean(it, it == "blockApk")) }
        backupSets.forEach { o.put(it, JSONArray(set(c, it).toList())) }
        o.put("sbKey", sbKey(c))
        o.put("browser", preferredBrowser(c) ?: "")
        return o.toString(2)
    }

    fun importJson(c: Context, json: String) {
        val o = JSONObject(json)
        require(o.optString("app") == "Mirador") { "Ce fichier n'est pas une sauvegarde Mirador" }
        val e = p(c).edit()
        backupBools.forEach { if (o.has(it)) e.putBoolean(it, o.getBoolean(it)) }
        backupSets.forEach { k ->
            o.optJSONArray(k)?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
        }
        if (o.has("sbKey")) e.putString("sbKey", o.getString("sbKey"))
        o.optString("browser").takeIf { it.isNotEmpty() }?.let { e.putString("browser", it) }
        e.apply()
    }

    // ---------- Journal micro / caméra ----------
    @Synchronized
    fun sensorLog(c: Context): List<SensorEvent> {
        val arr = runCatching { JSONArray(p(c).getString("sensors", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                SensorEvent(o.getLong("t"), o.getString("k"), o.getBoolean("s"), o.optString("a").ifEmpty { null }, o.getBoolean("o"))
            }.getOrNull()
        }
    }

    @Synchronized
    fun addSensorEvent(c: Context, e: SensorEvent) {
        val arr = JSONArray()
        (listOf(e) + sensorLog(c)).take(300).forEach {
            arr.put(JSONObject().put("t", it.time).put("k", it.type).put("s", it.start).put("a", it.app ?: "").put("o", it.screenOff))
        }
        p(c).edit().putString("sensors", arr.toString()).apply()
    }

    fun clearSensorLog(c: Context) = p(c).edit().remove("sensors").apply()
}
