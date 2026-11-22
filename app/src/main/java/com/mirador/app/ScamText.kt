package com.mirador.app

data class TextReport(val score: Int, val reasons: List<String>, val worstUrl: UrlReport?) {
    val verdict: Verdict get() = Verdict.ofLinkScore(score)
}

/** Détecte les SMS d'arnaque courants en France (analyse locale). */
object ScamText {

    private val tlds = "com|fr|net|org|info|xyz|top|ly|io|co|me|app|link|live|online|site|shop|club|icu|cc|tk|ml|ga|cf|gq|be|ch|de|eu|us|re|click|vip|cfd|sbs|buzz"
    private val urlRegex = Regex("""(?i)\b((?:https?://|www\.)\S+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:$tlds)(?:/\S*)?)""")

    private val patterns: List<Triple<Regex, Int, String>> = listOf(
        Triple(Regex("(?i)colis|livraison|frais de (port|douane)|point relais|adresse incompl"), 15, "Faux colis / livraison"),
        Triple(Regex("(?i)amende|antai|contravention|avis de paiement|infraction"), 20, "Fausse amende"),
        Triple(Regex("(?i)carte vitale|ameli|assurance maladie|remboursement|mutuelle"), 20, "Faux remboursement / Ameli"),
        Triple(Regex("(?i)\\bcpf\\b|compte (personnel de )?formation"), 20, "Arnaque CPF"),
        Triple(Regex("(?i)compte (a été |est )?(bloqu|suspendu|désactiv|desactiv)|activit[ée] (inhabituelle|suspecte)|op[ée]ration suspecte"), 20, "Compte bloqué / activité suspecte"),
        Triple(Regex("(?i)urgent|dernier (rappel|avis)|sous 24 ?h|sous 48 ?h|imm[ée]diatement|avant ce soir"), 10, "Pression / urgence"),
        Triple(Regex("(?i)coucou (maman|papa)|\\b(maman|papa)\\b.*(nouveau num|t[ée]l[ée]phone)|nouveau num[ée]ro|j'ai cass[ée] mon"), 25, "Arnaque « coucou maman, nouveau numéro »"),
        Triple(Regex("(?i)f[ée]licitations|vous avez (gagn|[ée]t[ée] s[ée]lectionn)|cadeau|tirage au sort|r[ée]compense"), 15, "Faux gain / cadeau"),
        Triple(Regex("(?i)conseiller (bancaire|financier)|virement|\\biban\\b|\\brib\\b|carte bancaire"), 15, "Demande bancaire"),
        Triple(Regex("(?i)install(ez|er) (l'|cette )?app|t[ée]l[ée]charg(ez|er) (l'|cette )?app|\\.apk\\b"), 25, "Demande d'installer une application"),
        Triple(Regex("(?i)crypto|bitcoin|investissement garanti|rendement"), 15, "Arnaque à l'investissement"),
    )

    fun extractUrls(text: String): List<String> =
        urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '"', '\'') }
            .filter { it.length > 4 }
            .map { if (it.contains("://")) it else "https://$it" }
            .distinct().toList()

    fun analyze(raw: String): TextReport {
        // Limite la taille analysée (un message énorme ne doit pas ralentir le téléphone)
        val text = raw.take(3000)
        val reasons = mutableListOf<String>()
        var kw = 0
        for ((re, pts, why) in patterns) {
            if (re.containsMatchIn(text)) { kw += pts; reasons += why }
        }
        kw = kw.coerceAtMost(50)

        val urls = extractUrls(text).map { UrlAnalyzer.analyze(it) }
        val worst = urls.maxByOrNull { it.score }
        var score = kw
        if (worst != null) {
            score += worst.score
            if (worst.score >= 20) reasons += "Lien suspect : ${worst.host}"
            if (kw > 0) { score += 10; reasons += "Message alarmant + lien à cliquer" }
        }
        return TextReport(score.coerceAtMost(100), reasons, worst)
    }
}
