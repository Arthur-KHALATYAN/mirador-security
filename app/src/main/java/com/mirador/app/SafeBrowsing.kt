package com.mirador.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Safe Browsing Lookup API v4.
 * Le lien est envoyé à Google pour être comparé à sa base de sites d'arnaque / malware.
 * À appeler hors du thread principal.
 */
object SafeBrowsing {
    /** URL de test officielle de Google : toujours signalée comme malware. */
    const val TEST_URL = "http://malware.testing.google.test/testing/malware/"

    /** Liste des menaces trouvées (vide = rien de connu). */
    fun check(c: Context, key: String, url: String): Result<List<String>> = runCatching {
        val body = JSONObject()
            .put("client", JSONObject().put("clientId", "mirador").put("clientVersion", "1.1"))
            .put(
                "threatInfo", JSONObject()
                    .put("threatTypes", JSONArray(listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION")))
                    .put("platformTypes", JSONArray(listOf("ANY_PLATFORM")))
                    .put("threatEntryTypes", JSONArray(listOf("URL")))
                    .put("threatEntries", JSONArray().put(JSONObject().put("url", url))),
            )
        val endpoint = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" + URLEncoder.encode(key, "UTF-8")
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Content-Type", "application/json")
            // Permet d'utiliser une clé limitée à cette appli (« Restrictions Android »)
            setRequestProperty("X-Android-Package", c.packageName)
            Certs.own(c)?.let { setRequestProperty("X-Android-Cert", it) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                // Message exact renvoyé par Google, pour savoir quoi corriger
                val detail = runCatching {
                    JSONObject(conn.errorStream.bufferedReader().readText()).getJSONObject("error").getString("message")
                }.getOrNull()
                error("HTTP $code" + (detail?.let { " : $it" } ?: ""))
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            val m = resp.optJSONArray("matches") ?: return@runCatching emptyList()
            (0 until m.length()).map { m.getJSONObject(it).getString("threatType") }.distinct()
        } finally {
            conn.disconnect()
        }
    }

    fun describe(t: String) = when (t) {
        "MALWARE" -> "logiciel malveillant"
        "SOCIAL_ENGINEERING" -> "hameçonnage / arnaque"
        "UNWANTED_SOFTWARE" -> "logiciel indésirable"
        "POTENTIALLY_HARMFUL_APPLICATION" -> "application dangereuse"
        else -> t
    }
}
