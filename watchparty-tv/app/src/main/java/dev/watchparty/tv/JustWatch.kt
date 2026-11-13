package dev.watchparty.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * Title → link, via JustWatch: the media session tells us the app and the
 * title (and duration); JustWatch knows each service's Android TV deep link
 * for that title. Same unofficial GraphQL API justwatch.com uses (and
 * Yamtrack's feat/justwatch-links branch); only the title is sent.
 *
 * Checked with tools/justwatch/jw.py on GitHub Actions: the Android TV deep
 * links match what started titles on a Sony Android TV 9 — Disney+
 * /play/<id> (Thunderbolts*, Iron Man), HBO Max play.hbomax.com/video/watch/
 * <id>, Apple TV movie pages (Watch Party adds ?action=play).
 */
object JustWatch {

    private const val API = "https://apis.justwatch.com/graphql"

    /** JustWatch package technical names per Android TV app (seen in
     *  JustWatch's own results). Others fall back to matching the app name. */
    private val TECHNICAL_NAMES = mapOf(
        MediaLink.DISNEY_PACKAGE to setOf("disneyplus"),
        MediaLink.HBO_PACKAGE to setOf("max", "hbomax"),
        MediaLink.APPLE_TV_PACKAGE to setOf("appletvplus", "appletv"),
        MediaLink.NETFLIX_TV_PACKAGE to setOf("netflix"),
        "com.netflix.mediaclient" to setOf("netflix"),
        "com.amazon.amazonvideo.livingroom" to setOf("amazonprime", "amazonprimevideowithads"),
        "com.google.android.youtube.tv" to setOf("youtube", "youtubefree"),
        "com.plexapp.android" to setOf("plex"),
    )

    data class Candidate(val id: String, val title: String, val year: Int, val runtimeMin: Int)
    data class Offer(val technicalName: String, val clearName: String, val deeplink: String?, val webUrl: String?)

    // ── queries (the shapes proven by tools/justwatch/jw.py) ──────────────

    private val SEARCH = """query S(${'$'}f: TitleFilter!, ${'$'}c: Country!, ${'$'}l: Language!, ${'$'}first: Int!) {
      popularTitles(country: ${'$'}c, filter: ${'$'}f, first: ${'$'}first, sortBy: POPULAR, sortRandomSeed: 0) {
        edges { node { id content(country: ${'$'}c, language: ${'$'}l) { title originalReleaseYear runtime } } }
      }
    }"""

    private val OFFERS = """query D(${'$'}id: ID!, ${'$'}c: Country!) {
      node(id: ${'$'}id) { ... on MovieOrShowOrSeasonOrEpisode {
        offers(country: ${'$'}c, platform: ANDROID_TV) {
          monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)
          package { technicalName clearName }
        }
      } }
    }"""

    /**
     * The link for [title] in the app [pkg], or null. [durationMs] (from the
     * media session, <= 0 if unknown) picks the right film among same-named
     * ones. Network — call off the main thread.
     */
    fun lookup(title: String, pkg: String, appName: String, durationMs: Long, country: String): String? {
        val search = post(SEARCH, JSONObject()
            .put("f", JSONObject().put("searchQuery", title).put("objectTypes", JSONArray().put("MOVIE").put("SHOW")))
            .put("c", country).put("l", "en").put("first", 5))
        val best = pickTitle(parseCandidates(search), title, durationMs) ?: return null
        val offers = parseOffers(post(OFFERS, JSONObject().put("id", best.id).put("c", country)))
        return linkFor(offers, pkg, appName)
    }

    // ── pure parts (unit-tested) ────────────────────────────────────────

    fun parseCandidates(response: JSONObject): List<Candidate> {
        val edges = response.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges")
            ?: return emptyList()
        val out = mutableListOf<Candidate>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val content = node.optJSONObject("content") ?: continue
            out += Candidate(node.optString("id"), content.optString("title"),
                content.optInt("originalReleaseYear"), content.optInt("runtime"))
        }
        return out
    }

    /** Same title (ignoring case and punctuation); among those, the runtime
     *  closest to the session's duration. */
    fun pickTitle(candidates: List<Candidate>, title: String, durationMs: Long): Candidate? {
        val wanted = simplify(title)
        val named = candidates.filter { simplify(it.title) == wanted }.ifEmpty { return null }
        if (durationMs <= 0) return named.first()
        val minutes = durationMs / 60_000.0
        return named.minByOrNull { Math.abs(it.runtimeMin - minutes) }
    }

    fun parseOffers(response: JSONObject): List<Offer> {
        val offers = response.optJSONObject("data")?.optJSONObject("node")?.optJSONArray("offers")
            ?: return emptyList()
        val out = mutableListOf<Offer>()
        for (i in 0 until offers.length()) {
            val o = offers.optJSONObject(i) ?: continue
            if (o.optString("monetizationType") !in setOf("FLATRATE", "FREE", "ADS", "FAST")) continue
            val p = o.optJSONObject("package") ?: continue
            out += Offer(p.optString("technicalName"), p.optString("clearName"),
                o.optString("deeplinkURL").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("standardWebURL").takeIf { it.isNotEmpty() && it != "null" })
        }
        return out
    }

    /** The playable link for [pkg] among [offers], normalized by MediaLink. */
    fun linkFor(offers: List<Offer>, pkg: String, appName: String): String? {
        val names = TECHNICAL_NAMES[pkg]
        val wantedName = simplify(appName)
        val offer = offers.firstOrNull { names != null && it.technicalName in names }
            ?: offers.firstOrNull { names == null && simplify(it.clearName) == wantedName }
            ?: return null
        val raw = offer.deeplink?.let { webLinkOf(it) } ?: offer.webUrl ?: return null
        return MediaLink.normalize(raw)?.link ?: raw
    }

    /** "intent://host/path?q#Intent;…;scheme=https;end" → "https://host/path?q". */
    fun webLinkOf(deeplink: String): String? {
        if (!deeplink.startsWith("intent://")) {
            return deeplink.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        val hash = deeplink.indexOf("#Intent;")
        if (hash < 0) return null
        val scheme = Regex(";scheme=([^;]+);").find(deeplink.substring(hash))?.groupValues?.get(1) ?: return null
        if (scheme != "https" && scheme != "http") return null
        return scheme + "://" + deeplink.substring("intent://".length, hash)
    }

    /** "Thunderbolts*" ~ "thunderbolts", "Disney+" ~ "Disney Plus". */
    fun simplify(s: String): String =
        s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    // ── network ─────────────────────────────────────────────────────────

    private fun post(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString().toByteArray(Charsets.UTF_8)
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            // JustWatch rejects default library user agents (as Yamtrack notes)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            conn.setRequestProperty("Origin", "https://www.justwatch.com")
            conn.setRequestProperty("Referer", "https://www.justwatch.com/")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
