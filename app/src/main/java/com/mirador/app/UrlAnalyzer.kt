package com.mirador.app

import android.net.Uri
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URL

data class Finding(val points: Int, val message: String)

enum class Verdict(
    val label: String,
    val icon: String,
    /** Couleur pleine pour les boutons (texte blanc dessus). */
    val solid: Int,
    private val textDark: Int,
    private val bgLight: Int,
    private val bgDark: Int,
) {
    SAFE("Sûr", "✅", 0xFF2E7D32.toInt(), 0xFF81C784.toInt(), 0xFFE8F5E9.toInt(), 0xFF1B3320.toInt()),
    CAUTION("Prudence", "⚠️", 0xFFE65100.toInt(), 0xFFFFB74D.toInt(), 0xFFFFF3E0.toInt(), 0xFF3A2A12.toInt()),
    DANGER("Danger", "⛔", 0xFFC62828.toInt(), 0xFFEF9A9A.toInt(), 0xFFFFEBEE.toInt(), 0xFF3D1A1A.toInt());

    /** Couleur du texte (lisible en mode clair comme sombre). */
    val color: Int get() = if (Ui.dark) textDark else solid
    val bg: Int get() = if (Ui.dark) bgDark else bgLight

    companion object {
        fun ofLinkScore(score: Int) = when {
            score >= 50 -> DANGER
            score >= 20 -> CAUTION
            else -> SAFE
        }
    }
}

data class UrlReport(
    val url: String,
    val host: String,
    val displayHost: String,
    val domain: String,
    val score: Int,
    val verdict: Verdict,
    val findings: List<Finding>,
)

/** Analyse hors-ligne d'un lien : rien n'est envoyé sur Internet. */
object UrlAnalyzer {

    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly",
        "rebrand.ly", "shorturl.at", "tiny.cc", "rb.gy", "s.id", "t.ly", "v.gd", "lnkd.in",
        "bl.ink", "shorte.st", "adf.ly", "urlz.fr", "snip.ly", "qrco.de", "linktr.ee",
    )

    private val badTlds = setOf(
        "zip", "mov", "xyz", "top", "tk", "ml", "ga", "cf", "gq", "click", "country", "gdn",
        "work", "rest", "fit", "loan", "bid", "cam", "icu", "cfd", "sbs", "monster", "quest",
        "buzz", "lol", "cyou", "bond", "hair",
    )

    /** Marque → domaines officiels. Utilisé pour détecter les imitations. */
    private val brands: Map<String, Set<String>> = mapOf(
        "paypal" to setOf("paypal.com", "paypal.fr", "paypal.me", "paypalobjects.com"),
        "google" to setOf("google.com", "google.fr", "googleapis.com", "googleusercontent.com", "gstatic.com", "googlevideo.com", "goo.gl"),
        "gmail" to setOf("gmail.com", "google.com"),
        "apple" to setOf("apple.com", "icloud.com", "apple.news"),
        "icloud" to setOf("icloud.com", "apple.com"),
        "microsoft" to setOf("microsoft.com", "live.com", "office.com", "microsoftonline.com", "outlook.com"),
        "outlook" to setOf("outlook.com", "live.com", "office.com", "microsoft.com"),
        "amazon" to setOf("amazon.com", "amazon.fr", "amazon.de", "amazon.co.uk", "amazon.es", "amazon.it", "amazonaws.com", "media-amazon.com"),
        "facebook" to setOf("facebook.com", "fb.com", "fbcdn.net", "meta.com", "fb.me"),
        "instagram" to setOf("instagram.com", "cdninstagram.com"),
        "whatsapp" to setOf("whatsapp.com", "whatsapp.net", "wa.me"),
        "netflix" to setOf("netflix.com", "nflxext.com"),
        "ameli" to setOf("ameli.fr"),
        "impots" to setOf("impots.gouv.fr"),
        "antai" to setOf("antai.gouv.fr"),
        "franceconnect" to setOf("franceconnect.gouv.fr"),
        "laposte" to setOf("laposte.fr", "laposte.net", "labanquepostale.fr"),
        "labanquepostale" to setOf("labanquepostale.fr"),
        "colissimo" to setOf("colissimo.fr", "laposte.fr"),
        "chronopost" to setOf("chronopost.fr", "chronopost.com"),
        "mondialrelay" to setOf("mondialrelay.fr", "mondialrelay.com"),
        "dhl" to setOf("dhl.com", "dhl.fr", "dhl.de"),
        "creditagricole" to setOf("credit-agricole.fr", "credit-agricole.com"),
        "societegenerale" to setOf("societegenerale.fr", "societegenerale.com", "sg.fr"),
        "bnpparibas" to setOf("bnpparibas.com", "bnpparibas.fr", "mabanque.bnpparibas"),
        "boursorama" to setOf("boursorama.com", "boursobank.com"),
        "creditmutuel" to setOf("creditmutuel.fr", "creditmutuel.com"),
        "orange" to setOf("orange.fr", "orange.com", "sosh.fr"),
        "bouygues" to setOf("bouyguestelecom.fr", "bouygues.com"),
        "binance" to setOf("binance.com"),
        "coinbase" to setOf("coinbase.com"),
        "steam" to setOf("steampowered.com", "steamcommunity.com", "steamstatic.com"),
        "leboncoin" to setOf("leboncoin.fr"),
        "vinted" to setOf("vinted.fr", "vinted.com"),
    )

    private val officialDomains = brands.values.flatten().toSet()

    private val phishingWords = listOf(
        "login", "signin", "sign-in", "verify", "verification", "secure", "update", "account",
        "confirm", "password", "wallet", "colis", "livraison", "amende", "remboursement",
        "suspendu", "bloque", "debloquer", "cartevitale", "carte-vitale", "paiement", "payment",
        "cadeau", "gagnant", "winner", "reward", "urgent", "authentification", "identifiant",
    )

    private val multiSuffix = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "co.jp", "com.br", "co.in", "gouv.fr",
        "asso.fr", "com.fr", "co.za", "com.tr", "com.mx", "com.es", "co.ma", "com.tn", "dz.com",
    )

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun registrable(host: String): String {
        if (host.isEmpty() || ipv4.matches(host) || host.contains(':')) return host
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in multiSuffix) labels.takeLast(3).joinToString(".") else last2
    }

    fun analyze(url: String): UrlReport {
        val f = mutableListOf<Finding>()
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        val domain = registrable(host)
        val label = domain.substringBefore('.')
        val pathAndQuery = ((uri.path ?: "") + "?" + (uri.query ?: "")).lowercase()
        val isOfficial = domain in officialDomains

        if (scheme != "http" && scheme != "https") f += Finding(30, "Type de lien inhabituel ($scheme:)")
        if (scheme == "http") f += Finding(15, "Connexion non chiffrée (http) : ce que vous tapez peut être intercepté")
        if (host.isEmpty()) f += Finding(30, "Aucun nom de site valide dans l'adresse")
        if (!uri.userInfo.isNullOrEmpty()) {
            f += Finding(45, "Contient « @ » : le vrai site est « $host », pas ce qui est écrit avant le @")
        }
        if (ipv4.matches(host) || host.contains(':')) f += Finding(30, "Adresse IP brute au lieu d'un nom de site")
        if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) {
            f += Finding(40, "Caractères spéciaux/étrangers dans le nom : possible imitation visuelle d'un vrai site")
        }
        if (host in shorteners || domain in shorteners) {
            f += Finding(20, "Lien raccourci : la vraie destination est cachée (touchez « Voir la vraie destination »)")
        }
        val tld = host.substringAfterLast('.', "")
        if (tld in badTlds) f += Finding(20, "Extension .$tld très utilisée par les arnaques")
        if (host.count { it == '.' } >= 4) f += Finding(15, "Beaucoup de sous-domaines (technique pour cacher le vrai site)")
        if (label.count { it == '-' } >= 2) f += Finding(10, "Nom de domaine avec beaucoup de tirets")
        if (host.length > 50) f += Finding(10, "Nom de site anormalement long")
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) f += Finding(15, "Port réseau inhabituel (${uri.port})")

        // Imitation de marque / faute de frappe volontaire
        if (!isOfficial && host.isNotEmpty()) {
            val flatHost = host.replace("-", "")
            val tokens = host.split('.', '-')
            val flatLabel = label.replace("-", "")
            val deconfused = deconfuse(flatLabel)
            var brandHit = false
            for ((brand, official) in brands) {
                val real = official.first()
                val inHost = if (brand.length >= 6) flatHost.contains(brand) else brand in tokens
                when {
                    inHost -> f += Finding(45, "Utilise le nom « $brand » mais ce n'est PAS le site officiel ($real)")
                    deconfused == brand && flatLabel != brand ->
                        f += Finding(45, "Imite « $real » avec des chiffres/lettres trompeurs ($label)")
                    brand.length >= 5 && levenshtein(flatLabel, brand) in 1..2 ->
                        f += Finding(40, "Ressemble fortement à « $real » (faute de frappe volontaire ?)")
                    else -> continue
                }
                brandHit = true
                break
            }
            if (!brandHit) {
                brands.keys.firstOrNull { it.length >= 5 && pathAndQuery.contains(it) }?.let {
                    f += Finding(15, "Mentionne « $it » dans l'adresse alors que le site n'a rien à voir")
                }
            }
        }

        if (!isOfficial) {
            val hay = "$host $pathAndQuery"
            val words = phishingWords.filter { hay.contains(it) }
            if (words.isNotEmpty()) {
                f += Finding(minOf(25, 8 * words.size), "Mots typiques d'hameçonnage : ${words.take(4).joinToString(", ")}")
            }
        }

        val path = (uri.path ?: "").lowercase()
        when {
            path.endsWith(".apk") || path.endsWith(".xapk") ->
                f += Finding(50, "Télécharge une application Android (.apk) hors Play Store : c'est le moyen n°1 d'installer un logiciel espion")
            listOf(".exe", ".scr", ".bat", ".msi", ".vbs", ".jar", ".cmd").any { path.endsWith(it) } ->
                f += Finding(30, "Télécharge un fichier exécutable")
        }

        val score = f.sumOf { it.points }.coerceAtMost(100)
        val display = runCatching { IDN.toUnicode(host) }.getOrDefault(host)
        return UrlReport(
            url = url,
            host = host,
            displayHost = if (display != host) "$display  ($host)" else host,
            domain = domain,
            score = score,
            verdict = Verdict.ofLinkScore(score),
            findings = f.sortedByDescending { it.points },
        )
    }

    /** Ajoute un résultat externe (Safe Browsing, liste de blocage) et recalcule le verdict. */
    fun withFinding(r: UrlReport, f: Finding): UrlReport {
        val all = (r.findings + f).sortedByDescending { it.points }
        val s = all.sumOf { it.points }.coerceAtMost(100)
        return r.copy(score = s, verdict = Verdict.ofLinkScore(s), findings = all)
    }

    private fun deconfuse(s: String) = s
        .replace("rn", "m").replace("vv", "w")
        .replace('0', 'o').replace('1', 'l').replace('3', 'e')
        .replace('4', 'a').replace('5', 's').replace('7', 't')

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 2) return 99
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Vrai seulement si toutes les adresses IP du nom sont publiques (anti-SSRF). */
    fun isPublicHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal")) return false
        val addrs = runCatching { java.net.InetAddress.getAllByName(h).toList() }.getOrNull() ?: return false
        return addrs.isNotEmpty() && addrs.none { a ->
            val b = a.address
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
                // 100.64.0.0/10 (CGNAT) et fc00::/7 (IPv6 privé)
                (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) ||
                (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC)
        }
    }

    /**
     * Suit les redirections avec des requêtes HEAD (le contenu de la page n'est jamais
     * téléchargé ni exécuté). À appeler hors du thread principal.
     */
    fun resolveRedirects(start: String, maxHops: Int = 8): List<String> {
        val chain = mutableListOf(start)
        var current = start
        repeat(maxHops) {
            val u = URL(current)
            // Uniquement le web public : jamais de requête vers la box, le réseau local ou le téléphone lui-même
            if (u.protocol != "http" && u.protocol != "https") return chain
            if (!isPublicHost(u.host)) {
                if (chain.size == 1) error("adresse locale ou privée : Mirador ne la contacte pas")
                return chain
            }
            val conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Mirador")
            }
            try {
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code !in 300..399 || loc.isNullOrBlank()) return chain
                val next = try {
                    URL(URL(current), loc).toString()
                } catch (e: Exception) {
                    chain += loc // ex. intent:// ou market:// : on s'arrête là
                    return chain
                }
                chain += next
                current = next
            } finally {
                conn.disconnect()
            }
        }
        return chain
    }
}
