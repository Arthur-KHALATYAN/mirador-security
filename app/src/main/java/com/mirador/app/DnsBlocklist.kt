package com.mirador.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Liste des domaines bloqués par le filtre DNS. */
object DnsBlocklist {
    /** Liste publique (publicité, traqueurs, malware) – environ 80 000 domaines. */
    const val SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    @Volatile private var domains: Set<String> = emptySet()
    @Volatile var loaded = false
        private set

    val size: Int get() = domains.size

    private val counter = AtomicInteger()
    val blockedCount: Int get() = counter.get()
    private val recent = ArrayDeque<String>()

    /** Petite liste intégrée : régies pub / traqueurs courants + sites de logiciels espions commerciaux. */
    private val builtIn = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com", "adsrvr.org", "moatads.com",
        "amazon-adsystem.com", "hotjar.com", "adcolony.com", "unityads.unity3d.com", "applovin.com",
        "mspy.com", "flexispy.com", "thetruthspy.com", "spyic.com", "cocospy.com", "hoverwatch.com",
        "spyzie.com", "xnspy.com", "highstermobile.com", "spyhide.com", "mobile-tracker-free.com",
        "spymaster.pro", "ikeymonitor.com", "eyezy.com", "umobix.com",
    )

    fun file(c: Context) = File(c.filesDir, "hosts.txt")

    fun load(c: Context) {
        val s = HashSet<String>(builtIn)
        s += Store.blockedDomains(c)
        Stalkerware.ensureLoaded(c)
        s += Stalkerware.domains.keys
        val f = file(c)
        if (f.exists()) f.forEachLine { line -> parseLine(line)?.let { s += it } }
        domains = s
        loaded = true
    }

    private val spaces = Regex("\\s+")

    private fun parseLine(line: String): String? {
        val t = line.substringBefore('#').trim()
        if (t.isEmpty()) return null
        val parts = t.split(spaces)
        val d = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
        if (d == "localhost" || d == "0.0.0.0" || d.endsWith(".local") || !d.contains('.')) return null
        return d
    }

    /** Télécharge la liste complète. À appeler hors du thread principal. Renvoie le nombre de domaines. */
    fun download(c: Context): Int {
        val conn = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
        }
        val tmp = File(c.filesDir, "hosts.tmp")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(file(c))) {
            tmp.copyTo(file(c), overwrite = true)
            tmp.delete()
        }
        load(c)
        return size
    }

    fun isBlocked(name: String): Boolean {
        var n = name.trimEnd('.')
        while (true) {
            if (n in domains) return true
            val i = n.indexOf('.')
            if (i < 0 || i == n.length - 1) return false
            n = n.substring(i + 1)
        }
    }

    fun record(name: String) {
        counter.incrementAndGet()
        synchronized(recent) {
            recent.remove(name)
            recent.addFirst(name)
            while (recent.size > 50) recent.removeLast()
        }
    }

    fun recent(): List<String> = synchronized(recent) { recent.toList() }
}
