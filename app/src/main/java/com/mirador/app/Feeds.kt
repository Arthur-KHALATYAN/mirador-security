package com.mirador.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private fun download(url: String, dest: File) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Mirador/3.0 (Android)")
    }
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    try {
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    } finally {
        conn.disconnect()
    }
    if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
    }
}

/**
 * Base publique des logiciels espions (stalkerware) maintenue par l'association Echap :
 * noms de paquets, certificats de signature et sites web / serveurs.
 */
object Stalkerware {
    const val SOURCE = "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"

    @Volatile private var packages: Map<String, String> = emptyMap()
    @Volatile private var certs: Map<String, String> = emptyMap()
    @Volatile var domains: Map<String, String> = emptyMap()
        private set
    @Volatile var loaded = false
        private set

    val appCount: Int get() = packages.size

    private fun file(c: Context) = File(c.filesDir, "stalkerware.yaml")

    fun ensureLoaded(c: Context) {
        if (!loaded) load(c)
    }

    /** Lecteur YAML minimal, suffisant pour le format ioc.yaml. */
    @Synchronized
    fun load(c: Context) {
        val pk = HashMap<String, String>()
        val ce = HashMap<String, String>()
        val dom = HashMap<String, String>()
        val f = file(c)
        if (f.exists()) {
            var name = "?"
            var key = ""
            f.forEachLine { raw ->
                val t = raw.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEachLine
                when {
                    raw.startsWith("- name:") -> {
                        name = t.removePrefix("- name:").trim().trim('"', '\'')
                        key = ""
                    }
                    t.startsWith("- ") -> {
                        val v = t.removePrefix("- ").trim().trim('"', '\'')
                        when (key) {
                            "packages" -> pk[v] = name
                            "certificates" -> ce[v.uppercase().replace(":", "")] = name
                            "websites", "domains" -> dom[v.lowercase().removePrefix("www.")] = name
                        }
                    }
                    t.contains(':') -> key = t.substringBefore(':').trim()
                }
            }
        }
        packages = pk
        certs = ce
        domains = dom
        loaded = true
    }

    fun update(c: Context): Int {
        download(SOURCE, file(c))
        load(c)
        return appCount
    }

    /** Nom du logiciel espion si l'appli correspond à la base, sinon null. */
    fun match(pkg: String, certSha1: List<String>): String? =
        packages[pkg] ?: certSha1.firstNotNullOfOrNull { certs[it] }

    fun domainMatch(host: String): String? {
        var h = host.lowercase().removePrefix("www.")
        while (true) {
            domains[h]?.let { return it }
            val i = h.indexOf('.')
            if (i < 0) return null
            h = h.substring(i + 1)
            if (!h.contains('.')) return null
        }
    }
}

/** Listes publiques de liens de phishing / malware (OpenPhish, URLhaus), vérifiées sur le téléphone. */
object PhishFeed {
    private val sources = listOf(
        "https://openphish.com/feed.txt",
        "https://urlhaus.abuse.ch/downloads/text_online/",
    )

    /** Hébergeurs partagés : on ne bloque pas tout le site pour un seul lien malveillant. */
    private val sharedHosts = setOf(
        "docs.google.com", "drive.google.com", "sites.google.com", "forms.gle", "storage.googleapis.com",
        "github.com", "raw.githubusercontent.com", "gist.github.com", "dropbox.com", "dl.dropboxusercontent.com",
        "onedrive.live.com", "1drv.ms", "bit.ly", "t.co", "tinyurl.com", "discord.com", "cdn.discordapp.com",
        "t.me", "telegram.me", "linktr.ee", "ipfs.io", "cloudflare-ipfs.com", "firebasestorage.googleapis.com",
        "mediafire.com", "wetransfer.com", "we.tl", "sharepoint.com", "facebook.com", "instagram.com", "youtube.com",
    )

    @Volatile private var urls: Set<String> = emptySet()
    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile var loaded = false
        private set

    val size: Int get() = urls.size

    private fun file(c: Context) = File(c.filesDir, "phish.txt")

    private fun norm(u: String) = u.trim().lowercase()
        .removePrefix("http://").removePrefix("https://").removePrefix("www.").trimEnd('/')

    fun ensureLoaded(c: Context) {
        if (!loaded) load(c)
    }

    @Synchronized
    fun load(c: Context) {
        val u = HashSet<String>()
        val h = HashSet<String>()
        val f = file(c)
        if (f.exists()) {
            f.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEachLine
                u += norm(t)
                Uri.parse(t).host?.lowercase()?.removePrefix("www.")?.let { host ->
                    if (host !in sharedHosts) h += host
                }
            }
        }
        urls = u
        hosts = h
        loaded = true
    }

    fun update(c: Context): Int {
        val parts = mutableListOf<String>()
        var ok = 0
        for (src in sources) {
            val tmp = File(c.cacheDir, "feed.tmp")
            if (runCatching { download(src, tmp) }.isSuccess) {
                ok++
                parts += tmp.readText()
            }
            tmp.delete()
        }
        if (ok == 0) error("aucune liste n'a pu être téléchargée")
        file(c).writeText(parts.joinToString("\n"))
        load(c)
        return size
    }

    fun check(url: String): Finding? {
        if (norm(url) in urls) return Finding(100, "Lien présent dans les listes de phishing / malware (OpenPhish, URLhaus)")
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return null
        if (host in hosts) return Finding(50, "Ce site héberge des liens de phishing / malware connus (OpenPhish, URLhaus)")
        return null
    }
}

object Feeds {
    /** Ajoute au rapport d'un lien les correspondances avec les bases locales. */
    fun enrich(c: Context, r: UrlReport): UrlReport {
        PhishFeed.ensureLoaded(c)
        Stalkerware.ensureLoaded(c)
        var out = r
        PhishFeed.check(r.url)?.let { out = UrlAnalyzer.withFinding(out, it) }
        if (r.host.isNotEmpty()) {
            Stalkerware.domainMatch(r.host)?.let {
                out = UrlAnalyzer.withFinding(out, Finding(60, "Site lié au logiciel espion « $it » (base Echap)"))
            }
        }
        return out
    }

    /** Met à jour toutes les bases. À appeler hors du thread principal. */
    fun updateAll(c: Context): String {
        val a = runCatching { Stalkerware.update(c) }
        val b = runCatching { PhishFeed.update(c) }
        if (a.isSuccess || b.isSuccess) {
            Store.setFeedsUpdated(c, System.currentTimeMillis())
            if (DnsBlocklist.loaded) DnsBlocklist.load(c)
        }
        return "Logiciels espions : " + (a.getOrNull()?.let { "$it applis" } ?: "échec (${a.exceptionOrNull()?.message})") +
            "\nPhishing / malware : " + (b.getOrNull()?.let { "$it liens" } ?: "échec (${b.exceptionOrNull()?.message})")
    }
}
